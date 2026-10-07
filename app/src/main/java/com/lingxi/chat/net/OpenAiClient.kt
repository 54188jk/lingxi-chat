package com.lingxi.chat.net

import com.lingxi.chat.data.ChatMessage
import com.lingxi.chat.data.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容接口客户端。
 *
 * 流式对话用冷流（callbackFlow）暴露：收集协程被取消时网络请求自动中断，
 * 因此「停止生成」只需取消收集方，不再需要手工管理线程与回调。
 */
class OpenAiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var currentCall: Call? = null

    /** 主动中断当前流式请求（用户点「停止生成」） */
    fun cancel() {
        currentCall?.cancel()
    }

    /** 流式事件：增量文本 / 正常结束 / 出错 */
    sealed interface Event {
        data class Delta(val text: String) : Event
        data object Done : Event
        data class Failed(val message: String) : Event
    }

    /**
     * 流式对话。返回冷流，取消收集即中断请求。
     *
     * @param messages 已构造好的完整消息列表（含 system 提示）
     */
    fun streamChat(config: ModelConfig, messages: List<ChatMessage>): Flow<Event> = callbackFlow {
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

        val job = launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string()?.take(500) ?: ""
                        send(Event.Failed("请求失败 HTTP ${resp.code}：${parseErr(errBody)}"))
                        return@use
                    }
                    val source = resp.body?.source() ?: run {
                        send(Event.Failed("响应体为空"))
                        return@use
                    }
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: continue
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val delta = try {
                            JSONObject(data).optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                                ?.optString("content", "")
                        } catch (_: Exception) {
                            null
                        }
                        if (!delta.isNullOrEmpty()) send(Event.Delta(delta))
                    }
                    send(Event.Done)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (!call.isCanceled()) send(Event.Failed("网络错误：${e.message}"))
            } catch (e: Exception) {
                send(Event.Failed("出错：${e.message}"))
            } finally {
                close()
            }
        }
        awaitClose {
            call.cancel()
            job.cancel()
            if (currentCall === call) currentCall = null
        }
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