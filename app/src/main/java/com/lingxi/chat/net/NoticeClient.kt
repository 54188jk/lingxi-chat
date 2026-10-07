package com.lingxi.chat.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 公告：读仓库里的 ANNOUNCEMENT.md（公开仓、免 Token）。
 *
 * 速度优化：
 * 1. 源顺序按国内实测速度排：jsDelivr → ghfast → raw 直连
 * 2. 超时压到 4s 连接 / 6s 读，慢源快速失败换下一个，不干等
 * 3. 带 ETag 条件请求，内容没变服务端直接回 304，秒回
 * 4. 成功内容本地缓存，下次直接读缓存渲染，再后台刷新
 */
object NoticeClient {

    private const val PATH = "54188jk/lingxi-chat/master/ANNOUNCEMENT.md"

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Notice(
        val title: String,
        val publishedAt: String,
        val body: String,
        /** 正文指纹，用来判断公告有没有更新 */
        val hash: String
    )

    /** 拉取结果：可能来自网络（网络优先），也可能来自缓存 */
    data class Result(val notice: Notice, val fromCache: Boolean)

    // 本地缓存（app 级 SharedPreferences，由 init 注入）
    private var cacheTitle = ""
    private var cacheTime = ""
    private var cacheBody = ""
    private var cacheEtag = ""

    fun initCache(prefs: android.content.SharedPreferences) {
        cacheTitle = prefs.getString("title", "") ?: ""
        cacheTime = prefs.getString("time", "") ?: ""
        cacheBody = prefs.getString("body", "") ?: ""
        cacheEtag = prefs.getString("etag", "") ?: ""
    }

    fun saveCache(prefs: android.content.SharedPreferences, notice: Notice, etag: String) {
        prefs.edit()
            .putString("title", notice.title)
            .putString("time", notice.publishedAt)
            .putString("body", notice.body)
            .putString("etag", etag)
            .apply()
        cacheTitle = notice.title
        cacheTime = notice.publishedAt
        cacheBody = notice.body
        cacheEtag = etag
    }

    fun cached(): Notice? {
        if (cacheBody.isBlank()) return null
        return Notice(cacheTitle, cacheTime, cacheBody, hash(cacheTitle, cacheTime, cacheBody))
    }

    /** 先读缓存（毫秒级），再尝试网络刷新；网络失败就用缓存顶上 */
    fun load(): Result? {
        val c = cached()
        val n = fetch()
        return when {
            n != null -> Result(n, fromCache = false)
            c != null -> Result(c, fromCache = true)
            else -> null
        }
    }

    private fun sources(): List<String> = listOf(
        "https://cdn.jsdelivr.net/gh/$PATH",
        "https://ghfast.top/https://raw.githubusercontent.com/$PATH",
        "https://raw.githubusercontent.com/$PATH"
    )

    fun fetch(): Notice? {
        for (url in sources()) {
            var notice: Notice? = null
            var etag = ""
            try {
                http.newCall(
                    Request.Builder().url(url)
                        .header("User-Agent", "LingxiChat-Android")
                        .apply { if (cacheEtag.isNotBlank()) header("If-None-Match", cacheEtag) }
                        .build()
                ).execute().use { resp ->
                    when {
                        resp.code == 304 -> notice = cached()
                        resp.isSuccessful -> {
                            notice = parse(resp.body?.string() ?: "")
                            etag = resp.header("ETag").orEmpty()
                        }
                    }
                }
            } catch (e: Exception) {
                // 换下一个源
            }
            val got = notice
            if (got != null) {
                lastEtag = if (etag.isNotBlank()) etag else cacheEtag
                return got
            }
        }
        return null
    }

    private var lastEtag: String = ""

    /** 最近一次 fetch 拿到的 ETag，交给调用方写缓存 */
    fun lastEtag(): String = if (lastEtag.isNotBlank()) lastEtag else cacheEtag

    private fun parse(raw: String): Notice? {
        val text = raw.trim()
        if (text.isBlank() || text.length < 8) return null
        val lines = text.lines()
        var title = "公告"
        var publishedAt = ""
        val body = mutableListOf<String>()
        for ((i, line) in lines.withIndex()) {
            val l = line.trim()
            when {
                i == 0 && l.startsWith("#") -> title = l.removePrefix("#").trim().ifBlank { title }
                l.startsWith(">") -> {
                    val v = l.removePrefix(">").trim()
                    if (v.startsWith("发布")) publishedAt = v.removePrefix("发布").trim(':').trim()
                }
                else -> body.add(line)
            }
        }
        val content = body.joinToString("\n").trim()
        if (content.isBlank() && publishedAt.isBlank()) return null
        return Notice(title, publishedAt, content.ifBlank { "（无正文）" }, hash(title, publishedAt, content))
    }

    private fun hash(title: String, time: String, body: String): String =
        (title + time + body).hashCode().toString()
}