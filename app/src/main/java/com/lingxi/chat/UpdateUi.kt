package com.lingxi.chat

import android.app.Activity
import android.content.Intent
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

    // 下载专用：连接 10 秒、读 45 秒；监视线程另有 10 秒无进展即换源的判定
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 首字节/读超时收紧到 45s：配合监视线程的 10s 无进展换源判定，
        // 避免一个半死连接把界面卡三分钟不动
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 一个下载候选源的状态：实时字节数、服务端声明总大小、失败原因 */
    private class RaceSource(val url: String, val label: String) {
        val downloaded = AtomicLong(0)
        @Volatile var total: Long = -1
        @Volatile var failed: String? = null
        @Volatile var call: Call? = null
        @Volatile var settled = false
        @Volatile var lastBytes = 0L
        @Volatile var lastChangeAt = System.currentTimeMillis()
    }

    private val activeCalls = java.util.Collections.synchronizedList(mutableListOf<Call>())
    private val downloadCancelled = AtomicBoolean(false)
    private val downloadingNow = AtomicBoolean(false)
    private var pendingApk: File? = null

    /** 记下待安装的时刻：超过 15 分钟就当作用户已经放弃，不再自动续装 */
    private var pendingApkAt = 0L

    /** 旧版本残留的「忽略此版本」标记文件名，这个功能已取消，启动时顺手清掉 */
    private const val LEGACY_SKIP_PREFS = "lingxi_update_skip"

    fun check(activity: Activity, currentVersion: String, silent: Boolean) {
        clearLegacySkip(activity)
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
                UpdateChecker.isNewer(info.version, currentVersion) -> showUpdatePage(activity, info, currentVersion)
                else -> {
                    if (!silent) showUpToDatePage(activity, currentVersion, info.publishedAt)
                }
            }
        }
    }

    private fun clearLegacySkip(activity: Activity) {
        val p = activity.getSharedPreferences(LEGACY_SKIP_PREFS, Activity.MODE_PRIVATE)
        if (!p.all.isNullOrEmpty()) p.edit().clear().apply()
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
        list.add(("https://github.moeyy.xyz/$url") to "加速源 5")
        list.add(("https://mirror.ghproxy.com/$url") to "加速源 6")
        list.add(url to "官方源")
        return list
    }

    /** 一款进度条样式：名字 + 进度设置器（0-100） */
    class ProgressStyle(val name: String, val set: (Int) -> Unit)

    /**
     * 下载进度条十选一（每次点击下载随机）：
     * 普通横条、贪吃蛇、像素方块、液体波动、圆环表盘、吃豆人、小火车、电池充电、火柴人、流星拉尾。
     * 布局里缺哪个就自动跳过哪个；没被选中的一款保持 GONE，动画不会空转。
     */
    internal fun pickProgressView(view: View): ProgressStyle {
        val candidates: List<Triple<String, View, (Int) -> Unit>> = listOfNotNull(
            view.findViewById<ProgressBar>(R.id.pbDownload)?.let { bar ->
                Triple("普通横条", bar, { p: Int -> bar.progress = p })
            },
            view.findViewById<SnakeProgressView>(R.id.spSnake)?.let { s ->
                Triple("贪吃蛇", s, { p: Int -> s.progress = p })
            },
            view.findViewById<BlocksProgressView>(R.id.spBlocks)?.let { b ->
                Triple("像素方块", b, { p: Int -> b.progress = p })
            },
            view.findViewById<WaveProgressView>(R.id.spWave)?.let { w ->
                Triple("液体波动", w, { p: Int -> w.progress = p })
            },
            view.findViewById<RingProgressView>(R.id.spRing)?.let { r ->
                Triple("圆环表盘", r, { p: Int -> r.progress = p })
            },
            view.findViewById<PacmanProgressView>(R.id.spPacman)?.let { p ->
                Triple("吃豆人", p, { v: Int -> p.progress = v })
            },
            view.findViewById<TrainProgressView>(R.id.spTrain)?.let { t ->
                Triple("小火车", t, { p: Int -> t.progress = p })
            },
            view.findViewById<BatteryProgressView>(R.id.spBattery)?.let { b ->
                Triple("电池充电", b, { p: Int -> b.progress = p })
            },
            view.findViewById<RunnerProgressView>(R.id.spRunner)?.let { r ->
                Triple("火柴人跑步", r, { p: Int -> r.progress = p })
            },
            view.findViewById<CometProgressView>(R.id.spComet)?.let { c ->
                Triple("流星拉尾", c, { p: Int -> c.progress = p })
            }
        )
        if (candidates.isEmpty()) return ProgressStyle("无", {})
        val chosen = candidates.random()
        candidates.forEach { (_, v, set) ->
            v.visibility = if (v === chosen.second) View.VISIBLE else View.GONE
            set(0)
        }
        return ProgressStyle(chosen.first, chosen.third)
    }

    /** 全部样式名，预览页用来列清单 */
    internal val progressStyleNames = listOf(
        "普通横条", "贪吃蛇", "像素方块", "液体波动", "圆环表盘",
        "吃豆人", "小火车", "电池充电", "火柴人跑步", "流星拉尾"
    )

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
            val cached = cachedApk(activity, info)
            if (cached != null) {
                dialog.dismiss()
                showFreshDialog(activity, info.version, cached)
                return@setOnClickListener
            }
            progressBox.visibility = View.VISIBLE
            percent.text = "0%"
            speed.text = "0 KB/s"
            val style = pickProgressView(view)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).text = "取消下载"
            download(activity, info, style.set, percent, speed, status) { ok, msg ->
                progressBox.visibility = View.GONE
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).text = "立即更新"
                if (ok) {
                    dialog.dismiss()
                    onDownloaded(activity, info, archive = false)
                } else if (msg != null) {
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            dialog.dismiss()
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
     * 1. 所有候选源（Gitee 直连 + 6 个加速镜像 + 官方源）同时发起，不再串行等待慢源
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
        if (!downloadingNow.compareAndSet(false, true)) {
            onEnd(false, "已经有一个下载正在进行，请先把它取消或等它完成")
            return
        }
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "lingxi-v${info.version}.apk")
        apk.delete()
        // 上一次下载留下的分片和其他版本残留都清掉，缓存不该越堆越大
        dir.listFiles()?.forEach { stale ->
            if (stale.name.endsWith(".part") || stale.name.contains(".part")) stale.delete()
        }
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
            downloadingNow.set(false)
            cancelRunningCalls()
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onEnd(ok, err)
            }
        }

        fun onSourceSettled(src: RaceSource) {
            src.settled = true
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
            src.call = call
            src.lastChangeAt = System.currentTimeMillis()
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
                // 10 秒没有新字节进账的源直接掐掉：它已经半死了，把连接和带宽让给别的源
                val tick = System.currentTimeMillis()
                candidates.forEach { s ->
                    if (s.settled || s.failed != null) return@forEach
                    val seen = s.downloaded.get()
                    when {
                        seen != s.lastBytes -> {
                            s.lastBytes = seen
                            s.lastChangeAt = tick
                        }
                        tick - s.lastChangeAt > 10_000L -> {
                            s.failed = "10 秒没有速度，已切走"
                            s.call?.cancel()
                        }
                    }
                }
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
            val style = pickProgressView(view)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).text = "取消下载"
            askArchiveChoice(activity)
            download(activity, info, style.set, percent, speed, status) { ok, msg ->
                progressBox.visibility = View.GONE
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).text = "开始下载"
                if (ok) {
                    dialog.dismiss()
                    onDownloaded(activity, info, archive = true)
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

    /** 供设置页/历史列表使用：走同一套「已存档版本 → 安装 + 授权引导」流程 */
    fun installFromHistory(activity: Activity, item: com.lingxi.chat.data.HistoryStore.Item): Boolean {
        val apk = item.installableFile(activity)
        if (apk == null || !apk.exists()) {
            Toast.makeText(activity, "这个存档读不到了，请重新下载", Toast.LENGTH_LONG).show()
            return false
        }
        val store = com.lingxi.chat.data.ConfigStore(activity)
        if (store.pendingInstallVersion == item.version) store.pendingInstallVersion = ""
        installApk(activity, apk)
        return true
    }

    /** 第一次从历史版本下载前问一次：存档放内部储存根目录，还是免权限的下载子目录 */
    private fun askArchiveChoice(activity: Activity) {
        val store = com.lingxi.chat.data.ConfigStore(activity)
        if (store.archiveLocation != "ask") return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            // Android 10 及以下：直接弹系统授权，点了允许就能在内部储存建「历史记录」
            store.archiveLocation = "root"
            com.lingxi.chat.data.HistoryStore.requestRuntimePermission(activity)
            return
        }
        if (com.lingxi.chat.data.HistoryStore.canUseRoot(activity)) {
            store.archiveLocation = "root"
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("历史安装包存到哪里")
            .setMessage(
                "从「历史版本」下载的安装包会在本机留一份，方便以后查看或重装（普通更新不存档）。\n\n" +
                        "· 内部储存/历史记录：目录最直观，需要授予一次「所有文件访问」权限（仅用于写这个文件夹）\n" +
                        "· 内部储存/下载/历史记录：不用授权，但会混在下载文件里"
            )
            .setPositiveButton("存到历史记录（去授权）") { _, _ ->
                store.archiveLocation = "root"
                com.lingxi.chat.data.HistoryStore.openRootPermissionSettings(activity)
            }
            .setNegativeButton("存到下载目录") { _, _ -> store.archiveLocation = "download" }
            .show()
    }

    /**
     * 下载完成后分两条路：
     * · 历史版本下载（archive = true）：往内部储存「历史记录」放一份，装不装由用户点，
     *   存成功就删掉缓存副本，避免同一份 2.6MB 存两处；之后还能从 设置 → 历史安装包 里装。
     * · 更新下载（archive = false）：不写历史记录，包只留在应用缓存里，装完即可。
     */
    private fun onDownloaded(
        activity: Activity,
        info: UpdateChecker.ReleaseInfo,
        archive: Boolean
    ) {
        val cacheApk = File(activity.cacheDir, "updates/lingxi-v${info.version}.apk")
        if (!cacheApk.exists()) {
            Toast.makeText(activity, "下载显示完成但文件不见了，请重新下载", Toast.LENGTH_LONG).show()
            return
        }
        if (!archive) {
            showFreshDialog(activity, info.version, cacheApk)
            return
        }
        val history = com.lingxi.chat.data.HistoryStore
        val existing = history.list(activity).firstOrNull { it.version == info.version }
        val saved = if (existing != null) com.lingxi.chat.data.HistoryStore.Saved(
            existing.displayPath, existing.file, existing.uri, existing.version
        ) else history.save(activity, cacheApk, info.version)
        // 存档里确实是这个文件了，缓存副本就没必要再占一份空间
        if (saved != null && saved.file?.exists() == true) cacheApk.delete()
        com.lingxi.chat.data.ConfigStore(activity).pendingInstallVersion = info.version
        showArchivedDialog(activity, info.version, saved)
    }

    /**
     * 缓存里是否已有下全的这个包（上一次下载后选了「稍后再装」）：
     * 结构校验过就直接进安装提示，不重复下载；校验失败就删掉脏文件。
     */
    private fun cachedApk(activity: Activity, info: UpdateChecker.ReleaseInfo): File? {
        val apk = File(activity.cacheDir, "updates/lingxi-v${info.version}.apk")
        if (!apk.exists()) return null
        return try {
            verifyApk(apk, 0L, info.sizeBytes)
            apk
        } catch (e: Exception) {
            apk.delete()
            null
        }
    }

    /** 更新包：临时留在缓存，不进历史记录 */
    private fun showFreshDialog(activity: Activity, version: String, apk: File) {
        val upgrade = UpdateChecker.isNewer(version, BuildConfig.VERSION_NAME)
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("v$version 已下载，还没安装")
            .setMessage(
                "这份是更新包，只临时放在应用缓存里，不会写进内部储存的「历史记录」——" +
                        "那个文件夹是留给历史版本存档的。\n\n" +
                        "· 想现在换版本：点「立即安装」\n" +
                        "· 暂时不装：包会留在缓存里，但系统清理缓存后就没有了，届时重新检查更新再下一次即可\n" +
                        (if (upgrade) "" else "\n注意：这是旧版本，安装前需要先卸载当前版本，本机会话和配置会被清空，建议先到 设置 → 数据备份 备份。")
            )
            .setPositiveButton("立即安装") { _, _ -> installApk(activity, apk) }
            .setNegativeButton("稍后再装") { _, _ ->
                Toast.makeText(activity, "没安装，包先留在缓存里", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 存档完成的提示：说清楚「还没安装」以及之后怎么装 */
    private fun showArchivedDialog(
        activity: Activity,
        version: String,
        saved: com.lingxi.chat.data.HistoryStore.Saved?
    ) {
        val where = saved?.displayPath ?: "应用缓存目录（没能写进历史记录）"
        val upgrade = UpdateChecker.isNewer(version, BuildConfig.VERSION_NAME)
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("v$version 已下载，还没安装")
            .setMessage(
                "安装包已存到「$where」，文件名 灵犀AI-v$version.apk。\n\n" +
                        "· 现在就想换版本：点「立即安装」\n" +
                        "· 暂时不装：它一直留在那个文件夹里，之后从 设置 → 历史安装包 里点一下就能装\n" +
                        "· 也可以在文件管理器打开这个文件夹，点文件自己安装\n" +
                        (if (upgrade) "" else "\n注意：这是旧版本，安装前需要先卸载当前版本，本机会话和配置会被清空，建议先到 设置 → 数据备份 备份。")
            )
            .setPositiveButton("立即安装") { _, _ ->
                val apk = saved?.installableFile(activity)
                if (apk == null || !apk.exists()) {
                    Toast.makeText(activity, "找不到已保存的安装包，请重新下载", Toast.LENGTH_LONG).show()
                } else {
                    installApk(activity, apk)
                }
            }
            .setNegativeButton("稍后再装", null)
            .show()
    }

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
                        pendingApkAt = System.currentTimeMillis()
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
                .setNegativeButton("取消") { _, _ -> pendingApk = null }
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
        val fresh = System.currentTimeMillis() - pendingApkAt < 15 * 60 * 1000L
        if (!fresh || !apk.exists()) {
            pendingApk = null
            return
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
                activity.packageManager.canRequestPackageInstalls()
        ) {
            launchInstaller(activity, apk)
        }
    }
}