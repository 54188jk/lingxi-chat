package com.lingxi.chat.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object SearchClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun search(provider: String, key: String, query: String): String {
        return when (provider) {
            "serper" -> serper(key, query)
            "bocha" -> bocha(key, query)
            else -> tavily(key, query)
        }
    }

    private fun tavily(key: String, query: String): String {
        val body = JSONObject()
            .put("api_key", key)
            .put("query", query)
            .put("max_results", 5)
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://api.tavily.com/search").post(body).build()
        val resp = http.newCall(req).execute()
        val json = JSONObject(resp.body?.string() ?: "")
        if (!resp.isSuccessful) throw Exception("Tavily 搜索失败 HTTP ${resp.code}")
        val results = json.optJSONArray("results") ?: return "没有找到相关结果"
        val sb = StringBuilder()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            sb.append("${i + 1}. ${r.optString("title")}\n${r.optString("url")}\n${r.optString("content")}\n\n")
        }
        return sb.toString().ifBlank { "没有找到相关结果" }
    }

    private fun serper(key: String, query: String): String {
        val body = JSONObject()
            .put("q", query)
            .put("num", 5)
            .put("gl", "cn")
            .put("hl", "zh-cn")
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://google.serper.dev/search")
            .header("X-API-KEY", key)
            .post(body)
            .build()
        val resp = http.newCall(req).execute()
        val json = JSONObject(resp.body?.string() ?: "")
        if (!resp.isSuccessful) throw Exception("Serper 搜索失败 HTTP ${resp.code}")
        val results = json.optJSONArray("organic") ?: return "没有找到相关结果"
        val sb = StringBuilder()
        for (i in 0 until minOf(results.length(), 5)) {
            val r = results.getJSONObject(i)
            sb.append("${i + 1}. ${r.optString("title")}\n${r.optString("link")}\n${r.optString("snippet")}\n\n")
        }
        return sb.toString().ifBlank { "没有找到相关结果" }
    }

    private fun bocha(key: String, query: String): String {
        val body = JSONObject()
            .put("query", query)
            .put("count", 5)
            .toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.bochaai.com/v1/web-search")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        val resp = http.newCall(req).execute()
        val json = JSONObject(resp.body?.string() ?: "")
        if (!resp.isSuccessful) throw Exception("博查搜索失败 HTTP ${resp.code}")
        val results = json.optJSONObject("data")
            ?.optJSONObject("webPages")
            ?.optJSONArray("value") ?: return "没有找到相关结果"
        val sb = StringBuilder()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            sb.append("${i + 1}. ${r.optString("name")}\n${r.optString("url")}\n${r.optString("snippet")}\n\n")
        }
        return sb.toString().ifBlank { "没有找到相关结果" }
    }
}
