package com.lingxi.chat.net

import com.lingxi.chat.data.ChatMessage
import com.lingxi.chat.data.ModelConfig
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentCall: Call? = null

    fun cancel() {
        currentCall?.cancel()
    }

    fun streamChat(
        config: ModelConfig,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val body = buildBody(config, messages)
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = http.newCall(req)
        currentCall = call

        Thread {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string()?.take(500) ?: ""
                        onError("请求失败 HTTP ${resp.code}：${parseErr(errBody)}")
                        return@use
                    }
                    val source = resp.body?.source() ?: run {
                        onError("响应体为空")
                        return@use
                    }
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: continue
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        try {
                            val json = JSONObject(data)
                            val delta = json.optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                                ?.optString("content", "")
                            if (!delta.isNullOrEmpty()) onDelta(delta)
                        } catch (_: Exception) {
                        }
                    }
                    onDone()
                }
            } catch (e: IOException) {
                if (call.isCanceled()) onError("已停止") else onError("网络错误：${e.message}")
            } catch (e: Exception) {
                onError("出错：${e.message}")
            }
        }.start()
    }

    private fun buildBody(config: ModelConfig, messages: List<ChatMessage>): JSONObject {
        val body = JSONObject()
        body.put("model", config.model)
        body.put("stream", true)
        val arr = JSONArray()
        messages.forEach { m ->
            val o = JSONObject()
            o.put("role", m.role)
            if (m.role == "user" && m.imageBase64 != null && config.vision) {
                val content = JSONArray()
                val textPart = JSONObject()
                textPart.put("type", "text")
                textPart.put("text", m.content.ifBlank { "描述一下这张图片" })
                content.put(textPart)
                val imgPart = JSONObject()
                imgPart.put("type", "image_url")
                val imgUrl = JSONObject()
                imgUrl.put("url", "data:${m.imageMime ?: "image/jpeg"};base64,${m.imageBase64}")
                imgPart.put("image_url", imgUrl)
                content.put(imgPart)
                o.put("content", content)
            } else {
                val text = if (m.imageBase64 != null) {
                    if (m.content.isBlank()) "[图片]" else m.content + "\n[附带图片]"
                } else m.content
                o.put("content", text)
            }
            arr.put(o)
        }
        body.put("messages", arr)
        return body
    }

    private fun parseErr(body: String): String {
        return try {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message") ?: body.take(200)
        } catch (e: Exception) {
            body.take(200)
        }
    }
}
