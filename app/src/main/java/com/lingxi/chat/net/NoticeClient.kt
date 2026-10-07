package com.lingxi.chat.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 公告：读仓库里的 ANNOUNCEMENT.md（公开仓、免 Token）。
 *
 * 三个地址轮换，主源慢/挂了就自动换：
 * raw 直连 → jsDelivr → ghfast 代理。
 * 文件格式约定：
 *   第一行 # 标题
 *   第二行 > 发布：2026-10-07 12:00:00
 *   之后正文（支持简单 Markdown，链接自动可点）
 */
object NoticeClient {

    private const val PATH = "54188jk/lingxi-chat/master/ANNOUNCEMENT.md"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Notice(
        val title: String,
        val publishedAt: String,
        val body: String,
        /** 正文指纹，用来判断公告有没有更新 */
        val hash: String
    )

    private fun sources(): List<String> = listOf(
        "https://raw.githubusercontent.com/$PATH",
        "https://cdn.jsdelivr.net/gh/$PATH",
        "https://ghfast.top/https://raw.githubusercontent.com/$PATH"
    )

    fun fetch(): Notice? {
        for (url in sources()) {
            val text = try {
                http.newCall(
                    Request.Builder().url(url)
                        .header("User-Agent", "LingxiChat-Android")
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()
                }
            } catch (e: Exception) {
                null
            } ?: continue
            return parse(text) ?: continue
        }
        return null
    }

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
        val hash = (title + publishedAt + content).hashCode().toString()
        return Notice(title, publishedAt, content.ifBlank { "（无正文）" }, hash)
    }
}