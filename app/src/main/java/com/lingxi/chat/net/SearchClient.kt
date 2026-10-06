package com.lingxi.chat.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object SearchClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun search(query: String): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = fetchHtml("https://html.duckduckgo.com/html/?q=$q")
            ?: return "未找到相关结果"
        return parseDdgHtml(html)
    }

    private fun fetchHtml(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private fun parseDdgHtml(html: String): String {
        val results = mutableListOf<String>()
        val titleRe = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>""")
        val snippetRe = Regex("""<a[^>]+class="result__snippet"[^>]*>([\s\S]*?)</a>""")
        val titles = titleRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).toList()
        for (i in 0 until minOf(titles.size, snippets.size, 5)) {
            val href = titles[i].groupValues[1]
            val title = stripHtml(titles[i].groupValues[2])
            val snippet = stripHtml(snippets[i].groupValues[1])
            val link = decodeDdgLink(href)
            results.add("$title\n$link\n$snippet")
        }
        if (results.isEmpty()) return "未找到相关结果"
        return results.joinToString("\n\n")
    }

    private fun decodeDdgLink(href: String): String {
        if (href.startsWith("//duckduckgo.com/l/?")) {
            val m = Regex("uddg=([^&]+)").find(href)
            if (m != null) return java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
        }
        return href
    }

    private fun stripHtml(raw: String): String {
        return raw.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }
}
