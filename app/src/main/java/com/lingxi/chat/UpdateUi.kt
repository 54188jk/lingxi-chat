package com.lingxi.chat

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lingxi.chat.net.UpdateChecker
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.Locale

/**
 * 应用内更新：检查 → 展示更新页 → 镜像加速下载 → 授权安装。
 *
 * 全程免 Token，检查源是公开的 GitHub releases/latest 接口。
 */
object UpdateUi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var downloadCall: Call? = null
    private var pendingApk: File? = null

    private const val PREF_SKIP = "lingxi_update_skip"
    private const val KEY_SKIP_VERSION = "skip_version"

    fun check(activity: Activity, currentVersion: String, silent: Boolean) {
        if (!silent) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        Thread {
            val info = UpdateChecker.fetchLatest()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    info == null -> {
                        if (!silent) {
                            Toast.makeText(activity, "检查失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                    UpdateChecker.isNewer(info.version, currentVersion) -> {
                        if (isSkipped(activity, info.version) && silent) return@runOnUiThread
                        showUpdatePage(activity, info, currentVersion)
                    }
                    else -> {
                        if (!silent) showUpToDatePage(activity, currentVersion, info.publishedAt)
                    }
                }
            }
        }.start()
    }

    // ---------------- 忽略此版本 ----------------

    private fun prefs(activity: Activity): SharedPreferences =
        activity.getSharedPreferences(PREF_SKIP, Activity.MODE_PRIVATE)

    private fun isSkipped(activity: Activity, version: String): Boolean =
        prefs(activity).getString(KEY_SKIP_VERSION, "") == version

    private fun skipVersion(activity: Activity, version: String) {
        prefs(activity).edit().putString(KEY_SKIP_VERSION, version).apply()
    }

    // ---------------- 更新页 ----------------

    private class SlowSourceException : Exception("速度过慢")

    /** 下载完但文件不完整/损坏：直接换下一个源，不给用户装坏包的机会 */
    private class CorruptedApkException(msg: String) : Exception(msg)

    /**
     * 校验下载下来的 APK 是否可用。
     * 三道检查：大小与服务器声明一致、ZIP 能完整读取、里面有 manifest 和 dex。
     */
    private fun verifyApk(apk: File, expectedFromServer: Long, releaseSize: Long) {
        if (!apk.exists() || apk.length() <= 0) throw CorruptedApkException("文件为空")
        if (expectedFromServer > 0 && apk.length() != expectedFromServer) {
            throw CorruptedApkException("大小不符（${apk.length()}/$expectedFromServer）")
        }
        if (releaseSize > 0 && apk.length() != releaseSize) {
            throw CorruptedApkException("与发布包大小不符")
        }
        try {
            java.util.zip.ZipFile(apk).use { zip ->
                val names = zip.entries().toList().map { it.name }
                if (names.none { it == "AndroidManifest.xml" }) throw CorruptedApkException("缺少 manifest")
                if (names.none { it.matches(Regex("classes\\d*\\.dex")) }) throw CorruptedApkException("缺少 dex")
            }
        } catch (e: CorruptedApkException) {
            throw e
        } catch (e: Exception) {
            throw CorruptedApkException("包结构损坏：${e.message}")
        }
    }

    private fun mirrorCandidates(url: String): List<Pair<String, String>> {
        val official = "官方源"
        return listOf(
            ("https://ghfast.top/$url") to "镜像加速 1",
            ("https://gh-proxy.com/$url") to "镜像加速 2",
            url to official
        )
    }

    private fun inflate(activity: Activity): View =
        LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)

    private fun showUpdatePage(activity: Activity, info: UpdateChecker.ReleaseInfo, currentVersion: String) {
        val view = inflate(activity)
        val header = view.findViewById<TextView>(R.id.tvHeadTitle)
        val versionLine = view.findViewById<TextView>(R.id.tvHeadVersion)
        val tag = view.findViewById<TextView>(R.id.tvTag)
        val notes = view.findViewById<TextView>(R.id.tvNotes)
        val meta = view.findViewById<TextView>(R.id.tvMeta)
        val progressBox = view.findViewById<LinearLayout>(R.id.llProgress)
        val percent = view.findViewById<TextView>(R.id.tvPercent)
        val speed = view.findViewById<TextView>(R.id.tvSpeed)
        val bar = view.findViewById<ProgressBar>(R.id.pbDownload)
        val status = view.findViewById<TextView>(R.id.tvDownloadStatus)

        header.text = "发现新版本"
        versionLine.text = "v$currentVersion  →  v${info.version}"
        tag.text = "建议更新"

        // 更新说明：把「发布时间：xxx」那行摘出来放 meta，其余作为正文
        val (body, timeLine) = splitNotes(info.notes)
        notes.text = body
        meta.text = buildString {
            if (info.publishedAt.isNotBlank()) append("发布于 ${info.publishedAt}")
            else if (timeLine != null) append(timeLine)
            if (info.sizeBytes > 0) {
                if (isNotEmpty()) append(" · ")
                append("安装包 ${"%.1f".format(Locale.US, info.sizeBytes / 1024.0 / 1024.0)} MB")
            }
            append(" · 官方源 GitHub，自动走镜像加速")
        }

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("立即更新", null)
            .setNegativeButton("稍后再说", null)
            .create()
        dialog.show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (progressBox.visibility == View.VISIBLE) {
                downloadCall?.cancel()
                status.text = "已取消下载"
                return@setOnClickListener
            }
            progressBox.visibility = View.VISIBLE
            percent.text = "0%"
            speed.text = "0 KB/s"
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text = "取消下载"
            download(activity, info, bar, percent, speed, status) { ok, msg ->
                progressBox.visibility = View.GONE
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text = "立即更新"
                if (ok) {
                    dialog.dismiss()
                    installApk(activity, File(activity.cacheDir, "updates/lingxi-v${info.version}.apk"))
                } else if (msg != null) {
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            android.app.AlertDialog.Builder(activity)
                .setTitle("忽略这个版本？")
                .setMessage("将不再提示 v${info.version} 的更新，之后仍可在设置里手动检查。")
                .setPositiveButton("忽略此版本") { _, _ ->
                    skipVersion(activity, info.version)
                    dialog.dismiss()
                }
                .setNegativeButton("仅本次不更新", null)
                .show()
        }
    }

    /** 更新说明正文 + 发布时间行 */
    private fun splitNotes(notes: String): Pair<String, String?> {
        val lines = notes.lines()
        val time = lines.firstOrNull { it.trim().startsWith("发布时间") }?.trim()
        val body = lines.filterNot { it.trim().startsWith("发布时间") }
            .joinToString("\n")
            .trim()
        return (body.ifBlank { "修复已知bug" }) to time
    }

    private fun showUpToDatePage(activity: Activity, currentVersion: String, publishedAt: String) {
        val view = inflate(activity)
        view.findViewById<TextView>(R.id.tvHeadTitle).text = "已是最新版本"
        view.findViewById<TextView>(R.id.tvHeadVersion).text = "v$currentVersion"
        view.findViewById<TextView>(R.id.tvTag).text = "无需更新"
        view.findViewById<TextView>(R.id.tvNotes).text = "当前已是最新，没有新版本可安装。"
        view.findViewById<TextView>(R.id.tvMeta).text =
            if (publishedAt.isBlank()) "官方源 GitHub" else "上一版本发布于 $publishedAt"
        android.app.AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("好") { _, _ -> }
            .show()
    }

    // ---------------- 下载 ----------------

    private fun download(
        activity: Activity,
        info: UpdateChecker.ReleaseInfo,
        bar: ProgressBar,
        percent: TextView,
        speed: TextView,
        status: TextView,
        onEnd: (Boolean, String?) -> Unit
    ) {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "lingxi-v${info.version}.apk")
        Thread {
            var error: String? = null
            var done = false
            val candidates = mirrorCandidates(info.downloadUrl)
            for ((idx, pair) in candidates.withIndex()) {
                if (done) break
                val (url, label) = pair
                try {
                    activity.runOnUiThread {
                        status.text = if (idx == 0) "连接${label}…" else "${label}（${idx + 1}/${candidates.size}）…"
                    }
                    val serverSize = downloadOnce(activity, apk, url, label, bar, percent, speed, status)
                    verifyApk(apk, serverSize, info.sizeBytes)
                    done = true
                } catch (e: SlowSourceException) {
                    apk.delete()
                    continue
                } catch (e: CorruptedApkException) {
                    apk.delete()
                    error = "下载的文件不完整（${e.message}），已自动换源重试"
                    activity.runOnUiThread { status.text = "文件不完整，换源重试…" }
                    continue
                } catch (e: Exception) {
                    if (downloadCall?.isCanceled() == true) {
                        error = null
                        done = true
                    } else {
                        error = e.message
                        apk.delete()
                        continue
                    }
                }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    done && apk.exists() -> onEnd(true, null)
                    downloadCall?.isCanceled() == true -> {
                        apk.delete()
                        onEnd(false, null)
                    }
                    else -> onEnd(false, error ?: "下载失败：所有下载源均不可用")
                }
            }
        }.start()
    }

    private fun downloadOnce(
        activity: Activity,
        apk: File,
        url: String,
        label: String,
        bar: ProgressBar,
        percent: TextView,
        speed: TextView,
        status: TextView
    ): Long {
        val call = http.newCall(Request.Builder().url(url).build())
        downloadCall = call
        return call.execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val total = resp.body?.contentLength() ?: -1L
            val input = resp.body?.byteStream() ?: throw Exception("响应为空")
            val out = FileOutputStream(apk)
            val buf = ByteArray(64 * 1024)
            var read: Int
            var downloaded = 0L
            var lastPost = 0L
            val startTime = System.currentTimeMillis()
            while (input.read(buf).also { read = it } != -1) {
                out.write(buf, 0, read)
                downloaded += read
                val now = System.currentTimeMillis()
                val elapsedMs = now - startTime
                // 起手 8 秒内几乎没动静，判定这个源太慢，换下一个
                if (elapsedMs > 8000 && downloaded < 200 * 1024) {
                    out.close()
                    throw SlowSourceException()
                }
                if (now - lastPost > 200) {
                    lastPost = now
                    val d = downloaded
                    val seconds = (elapsedMs / 1000).coerceAtLeast(1)
                    val kbps = d / 1024 / seconds
                    val remain = if (total > 0 && kbps > 0) (total - d) / 1024 / kbps else -1L
                    activity.runOnUiThread {
                        val progress: String
                        if (total > 0) {
                            val pct = (d * 100 / total).toInt()
                            bar.progress = pct
                            percent.text = "$pct%"
                            progress = "已下载 ${d / 1024 / 1024} MB / ${total / 1024 / 1024} MB" +
                                    if (remain > 0) " · 约剩 ${remain}s" else ""
                        } else {
                            percent.text = "${d / 1024 / 1024} MB"
                            progress = "已下载 ${d / 1024} KB（服务器未给总大小）"
                        }
                        speed.text = "$kbps KB/s"
                        status.text = "$label · $progress"
                    }
                }
            }
            out.flush()
            out.close()
            // 返回服务端声明的总大小，供上层做完整性校验
            total
        }
    }

    // ---------------- 历史版本 ----------------

    /**
     * 下载并安装指定版本（历史版本列表用），复用同一套更新页与镜像加速逻辑。
     * 降级安装系统会拒绝，调用方需先提示用户卸载当前版本。
     */
    fun openVersionPage(activity: Activity, info: UpdateChecker.ReleaseInfo, isDowngrade: Boolean) {
        if (isDowngrade) {
            android.app.AlertDialog.Builder(activity)
                .setTitle("这是历史版本 v${info.version}")
                .setMessage(
                    "你当前已是更新版本，安装旧版需要先卸载「灵犀AI」，" +
                            "卸载会一并清空本机的会话记录和模型配置。\n\n" +
                            "确定要安装 v${info.version} 吗？"
                )
                .setPositiveButton("仍然安装") { _, _ -> showDownloadPage(activity, info, "历史版本") }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        showDownloadPage(activity, info, "历史版本")
    }

    private fun showDownloadPage(activity: Activity, info: UpdateChecker.ReleaseInfo, tagText: String) {
        val view = inflate(activity)
        val notes = view.findViewById<TextView>(R.id.tvNotes)
        val meta = view.findViewById<TextView>(R.id.tvMeta)
        view.findViewById<TextView>(R.id.tvHeadTitle).text = "下载 v${info.version}"
        view.findViewById<TextView>(R.id.tvHeadVersion).text = "历史版本"
        view.findViewById<TextView>(R.id.tvTag).text = tagText
        notes.text = splitNotes(info.notes).first
        meta.text = buildString {
            if (info.publishedAt.isNotBlank()) append("发布于 ${info.publishedAt}")
            if (info.sizeBytes > 0) {
                if (isNotEmpty()) append(" · ")
                append("安装包 ${"%.1f".format(Locale.US, info.sizeBytes / 1024.0 / 1024.0)} MB")
            }
        }
        val progressBox = view.findViewById<LinearLayout>(R.id.llProgress)
        val percent = view.findViewById<TextView>(R.id.tvPercent)
        val speed = view.findViewById<TextView>(R.id.tvSpeed)
        val bar = view.findViewById<ProgressBar>(R.id.pbDownload)
        val status = view.findViewById<TextView>(R.id.tvDownloadStatus)

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("开始下载", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            progressBox.visibility = View.VISIBLE
            percent.text = "0%"
            speed.text = "0 KB/s"
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text = "取消下载"
            download(activity, info, bar, percent, speed, status) { ok, msg ->
                progressBox.visibility = View.GONE
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text = "开始下载"
                if (ok) {
                    dialog.dismiss()
                    installApk(activity, File(activity.cacheDir, "updates/lingxi-v${info.version}.apk"))
                } else if (msg != null) {
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------------- 安装 ----------------

    private fun installApk(activity: Activity, apk: File) {
        if (!apk.exists()) {
            Toast.makeText(activity, "安装包已失效，请重新检查更新", Toast.LENGTH_SHORT).show()
            return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            android.app.AlertDialog.Builder(activity)
                .setTitle("需要安装权限")
                .setMessage("新版本已下载完成（${"%.1f".format(Locale.US, apk.length() / 1024.0 / 1024.0)} MB）。\n\n请在下一页允许「灵犀AI」安装应用，返回后将自动继续安装。")
                .setPositiveButton("去授权") { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    } catch (e: Exception) {
                        Toast.makeText(activity, "请在系统设置中允许安装未知应用", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        launchInstaller(activity, apk)
    }

    private fun launchInstaller(activity: Activity, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "com.lingxi.chat.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            pendingApk = null
        } catch (e: Exception) {
            Toast.makeText(activity, "无法调起安装：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 从安装授权页返回时自动续装 */
    fun resumeInstallIfNeeded(activity: Activity) {
        val apk = pendingApk ?: return
        if (apk.exists() && activity.packageManager.canRequestPackageInstalls()) {
            launchInstaller(activity, apk)
        }
    }
}