package com.lingxi.chat.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val LATEST_URL = "https://api.github.com/repos/54188jk/lingxi-chat/releases/latest"
    private const val LIST_URL = "https://api.github.com/repos/54188jk/lingxi-chat/releases?per_page=30"
    private const val RELEASES_PAGE = "https://github.com/54188jk/lingxi-chat/releases"

    data class ReleaseInfo(
        val version: String,
        val notes: String,
        val downloadUrl: String,
        /** 发布时间，形如 2026-10-07 10:30:55（本地时区） */
        val publishedAt: String = "",
        /** 安装包字节数，未知为 0 */
        val sizeBytes: Long = 0L,
        val pageUrl: String = RELEASES_PAGE
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun fetchLatest(): ReleaseInfo? {
        return try {
            val req = Request.Builder()
                .url(LATEST_URL)
                .header("User-Agent", "LingxiChat-Android")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: return null)
                val tag = json.optString("tag_name", "").removePrefix("v")
                val notes = json.optString("body", "")
                var url = ""
                var size = 0L
                val assets = json.optJSONArray("assets")
                if (assets != null && assets.length() > 0) {
                    val asset = assets.getJSONObject(0)
                    url = asset.optString("browser_download_url", "")
                    size = asset.optLong("size", 0L)
                }
                if (url.isBlank()) url = RELEASES_PAGE
                if (tag.isBlank()) null
                else ReleaseInfo(
                    tag, notes, url,
                    publishedAt = formatPublished(json.optString("published_at", "")),
                    sizeBytes = size,
                    pageUrl = json.optString("html_url", RELEASES_PAGE)
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** GitHub 返回 ISO 时间，转成本地时区的 yyyy-MM-dd HH:mm:ss */
    private fun formatPublished(iso: String): String {
        if (iso.isBlank()) return ""
        return try {
            val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = parser.parse(iso) ?: return ""
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(date)
        } catch (e: Exception) {
            ""
        }
    }

    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 历史版本列表（含最新版，按时间倒序），失败返回空列表 */
    fun fetchReleases(): List<ReleaseInfo> {
        return try {
            val req = Request.Builder()
                .url(LIST_URL)
                .header("User-Agent", "LingxiChat-Android")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = org.json.JSONArray(resp.body?.string() ?: return emptyList())
                val out = mutableListOf<ReleaseInfo>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optBoolean("draft")) continue
                    val tag = o.optString("tag_name", "").removePrefix("v")
                    // 只要纯数字版本号，公告/测试之类的 tag 直接跳过
                    val parts = tag.split(".")
                    if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) continue
                    val version = tag
                    var url = ""
                    var size = 0L
                    o.optJSONArray("assets")?.let { assets ->
                        for (k in 0 until assets.length()) {
                            val a = assets.optJSONObject(k) ?: continue
                            if (a.optString("content_type", "").contains("android") ||
                                a.optString("name", "").endsWith(".apk")
                            ) {
                                url = a.optString("browser_download_url", "")
                                size = a.optLong("size", 0L)
                                break
                            }
                        }
                    }
                    if (url.isBlank()) continue
                    out.add(
                        ReleaseInfo(
                            version = version,
                            notes = o.optString("body", ""),
                            downloadUrl = url,
                            publishedAt = formatPublished(o.optString("published_at", "")),
                            sizeBytes = size,
                            pageUrl = o.optString("html_url", RELEASES_PAGE)
                        )
                    )
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
