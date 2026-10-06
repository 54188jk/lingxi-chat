package com.lingxi.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
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

object UpdateUi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var downloadCall: Call? = null
    private var pendingApk: File? = null

    fun check(activity: Activity, currentVersion: String, silent: Boolean) {
        if (!silent) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        Thread {
            val info = UpdateChecker.fetchLatest()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    info == null -> {
                        if (!silent) Toast.makeText(activity, "检查失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
                    }
                    UpdateChecker.isNewer(info.version, currentVersion) -> {
                        showUpdateDialog(activity, info)
                    }
                    else -> {
                        if (!silent) Toast.makeText(activity, "已是最新版本（v$currentVersion）", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateChecker.ReleaseInfo) {
        val notes = info.notes.ifBlank { "修复已知bug" }
        android.app.AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${info.version}")
            .setMessage(notes)
            .setPositiveButton("下载更新") { _, _ ->
                startDownload(activity, info)
            }
            .setNegativeButton("以后再说", null)
            .show()
    }

    private class SlowSourceException : Exception("速度过慢")

    private fun mirrorCandidates(url: String): List<String> {
        return listOf(
            "https://ghfast.top/$url",
            "https://gh-proxy.com/$url",
            url
        )
    }

    private fun startDownload(activity: Activity, info: UpdateChecker.ReleaseInfo) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_download, null)
        val pb = view.findViewById<ProgressBar>(R.id.pbDownload)
        val tv = view.findViewById<TextView>(R.id.tvDownloadProgress)
        view.findViewById<TextView>(R.id.tvDownloadTitle).text = "正在下载 v${info.version}"

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton("取消") { _, _ ->
                downloadCall?.cancel()
            }
            .setCancelable(true)
            .create()
        dialog.show()

        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "lingxi-v${info.version}.apk")

        Thread {
            var error: String? = null
            var done = false
            val candidates = mirrorCandidates(info.downloadUrl)
            for ((idx, url) in candidates.withIndex()) {
                if (done) break
                try {
                    activity.runOnUiThread {
                        tv.text = if (idx == 0) "连接下载源…" else "切换下载源（${idx + 1}/${candidates.size}）…"
                    }
                    downloadOnce(activity, apk, url, pb, tv)
                    done = true
                } catch (e: SlowSourceException) {
                    apk.delete()
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
                dialog.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    done && apk.exists() -> {
                        pendingApk = apk
                        installApk(activity, apk)
                    }
                    downloadCall?.isCanceled() == true -> apk.delete()
                    else -> Toast.makeText(activity, "下载失败：${error ?: "所有下载源均不可用"}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun downloadOnce(activity: Activity, apk: File, url: String, pb: ProgressBar, tv: TextView) {
        val call = http.newCall(Request.Builder().url(url).build())
        downloadCall = call
        call.execute().use { resp ->
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
                val elapsed = now - startTime
                if (elapsed > 8000 && downloaded < 200 * 1024) {
                    out.close()
                    throw SlowSourceException()
                }
                if (now - lastPost > 200) {
                    lastPost = now
                    val d = downloaded
                    activity.runOnUiThread {
                        if (total > 0) {
                            val pct = (d * 100 / total).toInt()
                            pb.progress = pct
                            tv.text = "$pct%（${d / 1024} KB / ${total / 1024 / 1024} MB）"
                        } else {
                            tv.text = "已下载 ${d / 1024} KB"
                        }
                    }
                }
            }
            out.flush()
            out.close()
        }
    }

    private fun fail(activity: Activity, dialog: android.app.AlertDialog, apk: File, msg: String?) {
        activity.runOnUiThread {
            dialog.dismiss()
            apk.delete()
            if (msg != null) Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun installApk(activity: Activity, apk: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            android.app.AlertDialog.Builder(activity)
                .setTitle("需要安装权限")
                .setMessage("新版本已下载完成。请在下一页允许「灵犀AI」安装应用，返回后将自动继续安装。")
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

    fun resumeInstallIfNeeded(activity: Activity) {
        val apk = pendingApk ?: return
        if (apk.exists() && activity.packageManager.canRequestPackageInstalls()) {
            launchInstaller(activity, apk)
        }
    }
}
