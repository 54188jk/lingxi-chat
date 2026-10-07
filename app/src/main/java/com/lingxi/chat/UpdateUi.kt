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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lingxi.chat.net.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale

/**
 * 应用内更新：检查 → 展示更新页 → 镜像加速下载 → 授权安装。
 *
 * 全程免 Token，检查源是公开的 GitHub releases/latest 接口。
 */
object UpdateUi {

    // 下载专用：连接 10 秒、读 15 秒——任何源卡住会被立刻判死并由其他源顶上
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 一个下载候选源的状态：实时字节数、服务端声明总大小、失败原因 */
    private class RaceSource(val url: String, val label: String) {
        val downloaded = AtomicLong(0)
        @Volatile var total: Long = -1
        @Volatile var failed: String? = null
    }

    private val activeCalls = java.util.Collections.synchronizedList(mutableListOf<Call>())
    private val downloadCancelled = AtomicBoolean(false)
    private var pendingApk: File? = null

    private const val PREF_SKIP = "lingxi_update_skip"
    private const val KEY_SKIP_VERSION = "skip_version"

    fun check(activity: Activity, currentVersion: String, silent: Boolean) {
        if (!silent) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        val owner = activity as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            when {
                info == null -> {
                    if (!silent) {
                        Toast.makeText(activity, "检查失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
                    }
                }
                UpdateChecker.isNewer(info.version, currentVersion) -> {
                    if (isSkipped(activity, info.version) && silent) return@launch
                    showUpdatePage(activity, info, currentVersion)
                }
                else -> {
                    if (!silent) showUpToDatePage(activity, currentVersion, info.publishedAt)
                }
            }
        }
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

    /**
     * 生成下载候选源，按国内实测速度排序。
     *
     * @param giteeUrl Gitee 侧的准确资产直链（来自 API）。优先用它，避免按约定拼名猜错；
     *                 为空时才退化到按版本号拼接。
     */
    private fun mirrorCandidates(
        url: String,
        version: String = "",
        giteeUrl: String = ""
    ): List<Pair<String, String>> {
        // 顺序按国内实测速度排：Gitee 直连最快，其后是 GitHub 加速镜像，最后才是 GitHub 官方源
        val list = mutableListOf<Pair<String, String>>()
        val direct = giteeUrl.ifBlank {
            if (version.isNotBlank()) com.lingxi.chat.net.UpdateChecker.giteeDownloadUrl(version) else ""
        }
        if (direct.isNotBlank()) list.add(direct to "Gitee 直连")
        list.add(("https://ghfast.top/$url") to "加速源 1")
        list.add(("https://gh-proxy.com/$url") to "加速源 2")
        list.add(("https://ghproxy.net/$url") to "加速源 3")
        list.add(("https://gh.ddlc.top/$url") to "加速源 4")
        list.add(url to "官方源")
        return list
    }

    /**
     * 下载进度条二选一（每次点击下载随机）：
     * 普通横条保持原样，贪吃蛇版由蛇头逐格吃掉豆子推进。
     * 返回进度设置器（0-100），调用方无需关心选了哪一款。
     */
    private fun pickProgressView(view: View): (Int) -> Unit {
        val normal = view.findViewById<ProgressBar>(R.id.pbDownload)
        val snake = view.findViewById<SnakeProgressView>(R.id.spSnake)
        val useSnake = kotlin.random.Random.nextBoolean()
        normal.visibility = if (useSnake) View.GONE else View.VISIBLE
        snake.visibility = if (useSnake) View.VISIBLE else View.GONE
        normal.progress = 0
        snake.progress = 0
        return { pct -> if (useSnake) snake.progress = pct else normal.progress = pct }
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
            append(" · 多源并行下载，自动取最快")
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("立即更新", null)
            .setNegativeButton("稍后再说", null)
            .create()
        dialog.show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (progressBox.visibility == View.VISIBLE) {
                cancelDownload()
                status.text = "正在取消下载…"
                return@setOnClickListener
            }
            progressBox.visibility = View.VISIBLE
            percent.text = "0%"
            speed.text = "0 KB/s"
            val setProgress = pickProgressView(view)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).text = "取消下载"
            download(activity, info, setProgress, percent, speed, status) { ok, msg ->
                progressBox.visibility = View.GONE
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).text = "立即更新"
                if (ok) {
                    dialog.dismiss()
                    installApk(activity, File(activity.cacheDir, "updates/lingxi-v${info.version}.apk"))
                } else if (msg != null) {
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(activity)
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
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("好") { _, _ -> }
            .show()
    }

    // ---------------- 下载 ----------------

    private fun cancelDownload() {
        downloadCancelled.set(true)
        val snapshot = synchronized(activeCalls) { activeCalls.toList() }
        snapshot.forEach { it.cancel() }
    }

    private fun briefError(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "连接或传输超时"
        is javax.net.ssl.SSLException -> "连接安全校验失败"
        else -> e.message?.take(40) ?: e.javaClass.simpleName
    }

    /**
     * 全源并行竞速下载：
     * 1. 所有候选源（Gitee 直连 + 4 个加速镜像 + 官方源）同时发起，不再串行等待慢源
     * 2. 任意源卡住 15 秒即被 OkHttp 超时判死，其余源不受影响
     * 3. 第一个下载完成并通过完整性校验的源立即胜出，其余源全部取消
     * 4. 进度、速度、剩余时间实时展示当前最快源
     */
    private fun download(
        activity: Activity,
        info: UpdateChecker.ReleaseInfo,
        setProgress: (Int) -> Unit,
        percent: TextView,
        speed: TextView,
        status: TextView,
        onEnd: (Boolean, String?) -> Unit
    ) {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "lingxi-v${info.version}.apk")
        apk.delete()
        // 检查阶段若 Gitee 胜出，downloadUrl 本身就是 Gitee 的准确资产直链；GitHub 官方链接按版本号可拼出
        val githubUrl = if (info.sourceName == "Gitee") {
            "https://github.com/54188jk/lingxi-chat/releases/download/v${info.version}/lingxi-v${info.version}.apk"
        } else info.downloadUrl
        val candidates = mirrorCandidates(
            githubUrl,
            info.version,
            giteeUrl = if (info.sourceName == "Gitee") info.downloadUrl else ""
        ).distinctBy { it.first }.map { RaceSource(it.first, it.second) }

        downloadCancelled.set(false)
        synchronized(activeCalls) { activeCalls.clear() }
        val finished = AtomicBoolean(false)
        val winnerTaken = AtomicBoolean(false)
        val remaining = AtomicInteger(candidates.size)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()

        fun finishOnce(ok: Boolean, err: String?) {
            if (!finished.compareAndSet(false, true)) return
            cancelRunningCalls()
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onEnd(ok, err)
            }
        }

        fun onSourceSettled(src: RaceSource) {
            src.failed?.let { errors.add("${src.label}：$it") }
            if (remaining.decrementAndGet() == 0 && !winnerTaken.get()) {
                val err = if (downloadCancelled.get()) null
                else "下载失败：" + (errors.toList().take(3).joinToString("；").ifBlank { "所有下载源均不可用" })
                finishOnce(false, err)
            }
        }

        fun startSource(idx: Int, src: RaceSource) {
            val part = File(dir, "${apk.name}.part$idx")
            part.delete()
            val call = http.newCall(
                Request.Builder().url(src.url)
                    .header("User-Agent", "LingxiChat-Android")
                    .build()
            )
            synchronized(activeCalls) { if (!finished.get()) activeCalls.add(call) }
            call.enqueue(object : Callback {
                override fun onFailure(c: Call, e: IOException) {
                    part.delete()
                    if (!downloadCancelled.get() && !c.isCanceled()) src.failed = briefError(e)
                    onSourceSettled(src)
                }

                override fun onResponse(c: Call, resp: Response) {
                    try {
                        resp.use { r ->
                            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                            src.total = r.body?.contentLength() ?: -1L
                            val input = r.body?.byteStream() ?: throw IOException("响应为空")
                            FileOutputStream(part).use { out ->
                                val buf = ByteArray(64 * 1024)
                                var n: Int
                                while (input.read(buf).also { n = it } != -1) {
                                    out.write(buf, 0, n)
                                    src.downloaded.addAndGet(n.toLong())
                                }
                            }
                        }
                        verifyApk(part, src.total.coerceAtLeast(0L), info.sizeBytes)
                        if (winnerTaken.compareAndSet(false, true)) {
                            if (apk.exists()) apk.delete()
                            if (!part.renameTo(apk)) {
                                winnerTaken.set(false)
                                src.failed = "文件保存失败"
                            }
                        } else {
                            part.delete()
                        }
                    } catch (e: CorruptedApkException) {
                        part.delete()
                        src.failed = "文件不完整（${e.message}）"
                    } catch (e: Exception) {
                        part.delete()
                        if (!downloadCancelled.get() && !c.isCanceled()) src.failed = briefError(e)
                    }
                    if (winnerTaken.get() && src.failed == null) finishOnce(true, null)
                    else onSourceSettled(src)
                }
            })
        }

        candidates.forEachIndexed(::startSource)

        // UI 监视线程：每 250ms 汇报当前最快源的进度
        Thread {
            val declaredTotal = info.sizeBytes
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            var shownKbps = 0L
            while (!finished.get() && !activity.isFinishing && !activity.isDestroyed) {
                Thread.sleep(250)
                if (finished.get()) break
                val running = candidates.filter { it.failed == null }
                val leader = running.maxByOrNull { it.downloaded.get() } ?: break
                val d = leader.downloaded.get()
                val now = System.currentTimeMillis()
                val dt = (now - lastTime).coerceAtLeast(1L)
                val inst = (d - lastBytes) * 1000 / dt
                lastBytes = d
                lastTime = now
                if (inst > 0) shownKbps = inst
                val total = if (leader.total > 0) leader.total else declaredTotal
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (d == 0L) {
                        status.text = "正在并行连接 ${candidates.size} 个下载源…"
                    } else {
                        if (total > 0) {
                            val pct = (d * 100 / total).toInt().coerceAtMost(if (d >= total) 100 else 99)
                            setProgress(pct)
                            percent.text = "$pct%"
                            val mb = "%.1f".format(Locale.US, d / 1024.0 / 1024.0)
                            val totalMb = "%.1f".format(Locale.US, total / 1024.0 / 1024.0)
                            val kbps = shownKbps / 1024
                            val remain = if (kbps > 0) ((total - d) / 1024 / kbps) else -1L
                            status.text = if (d >= total) "${leader.label} · 下载完成，正在校验…"
                            else "${leader.label} · ${mb}/$totalMb MB" +
                                    (if (remain in 0..600) " · 约剩 ${remain}s" else "")
                        } else {
                            percent.text = "${d / 1024 / 1024} MB"
                            status.text = "${leader.label} · 已下载 ${d / 1024} KB（服务器未给总大小）"
                        }
                        speed.text = "${shownKbps / 1024} KB/s"
                    }
                }
            }
        }.start()
    }

    private fun cancelRunningCalls() {
        val snapshot = synchronized(activeCalls) { activeCalls.toList() }
        snapshot.forEach { it.cancel() }
    }

    // ---------------- 历史版本 ----------------

    /**
     * 下载并安装指定版本（历史版本列表用），复用同一套更新页与镜像加速逻辑。
     * 降级安装系统会拒绝，调用方需先提示用户卸载当前版本。
     */
    fun openVersionPage(activity: Activity, info: UpdateChecker.ReleaseInfo, isDowngrade: Boolean) {
        if (isDowngrade) {
            androidx.appcompat.app.AlertDialog.Builder(activity)
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
        val status = view.findViewById<TextView>(R.id.tvDownloadStatus)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton("开始下载", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (progressBox.visibility == View.VISIBLE) {
                cancelDownload()
                status.text = "正在取消下载…"
                return@setOnClickListener
            }
            progressBox.visibility = View.VISIBLE
            percent.text = "0%"
            speed.text = "0 KB/s"
            val setProgress = pickProgressView(view)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).text = "取消下载"
            download(activity, info, setProgress, percent, speed, status) { ok, msg ->
                progressBox.visibility = View.GONE
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).text = "开始下载"
                if (ok) {
                    dialog.dismiss()
                    installApk(activity, File(activity.cacheDir, "updates/lingxi-v${info.version}.apk"))
                } else if (msg != null) {
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if (progressBox.visibility == View.VISIBLE) cancelDownload()
        }
    }

    // ---------------- 安装 ----------------

    private fun installApk(activity: Activity, apk: File) {
        if (!apk.exists()) {
            Toast.makeText(activity, "安装包已失效，请重新检查更新", Toast.LENGTH_SHORT).show()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("需要安装权限")
                .setMessage("新版本已下载完成（${"%.1f".format(Locale.US, apk.length() / 1024.0 / 1024.0)} MB）。\n\n请在下一页允许「灵犀AI」安装应用，返回后将自动继续安装。")
                .setPositiveButton("去授权") { _, _ ->
                    try {
                        pendingApk = apk
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    } catch (e: Exception) {
                        pendingApk = null
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
        if (apk.exists() && (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
                    activity.packageManager.canRequestPackageInstalls())) {
            launchInstaller(activity, apk)
        }
    }
}