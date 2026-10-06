package com.lingxi.chat.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val LATEST_URL = "https://api.github.com/repos/54188jk/lingxi-chat/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/54188jk/lingxi-chat/releases"

    data class ReleaseInfo(
        val version: String,
        val notes: String,
        val downloadUrl: String
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
                val assets = json.optJSONArray("assets")
                if (assets != null && assets.length() > 0) {
                    url = assets.getJSONObject(0).optString("browser_download_url", "")
                }
                if (url.isBlank()) url = RELEASES_PAGE
                if (tag.isBlank()) null else ReleaseInfo(tag, notes, url)
            }
        } catch (e: Exception) {
            null
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
}
