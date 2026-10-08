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
        // 流式响应不能设整体超时，但 90 秒收不到任何数据就是断了，
        // 否则服务端悄悄挂掉时界面上的「思考中」会一直转
        .readTimeout(90, TimeUnit.SECONDS)
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
                        send(Event.Failed(describeHttpError(resp.code, errBody)))
                        return@use
                    }
                    val source = resp.body?.source() ?: run {
                        send(Event.Failed("响应体为空：这家服务商没有返回任何内容"))
                        return@use
                    }
                    var gotAny = false
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
                        if (!delta.isNullOrEmpty()) {
                            gotAny = true
                            send(Event.Delta(delta))
                        }
                    }
                    if (!gotAny) {
                        // 空回复直接说明白，否则界面上只剩一个空气泡，用户以为卡住了
                        send(Event.Failed("模型没有返回内容，可能是这个模型名填错了、内容被安全策略拦下，或服务商暂时异常，换模型或稍后再试"))
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

    /** 把常见 HTTP 错误码翻成一句普通人能照着做的话 */
    private fun describeHttpError(code: Int, body: String): String {
        val detail = parseErr(body)
        val hint = when (code) {
            400 -> "接口地址或参数不对：检查 Base URL 是否以 /v1 结尾、模型名是否填错"
            401, 403 -> "API Key 不对或没有权限：到 设置 → 模型配置 里重新粘贴 Key"
            404 -> "找不到这个模型或接口：Base URL 末尾一般要带 /v1，模型名要和服务商页面写的一致"
            408 -> "服务商响应超时，稍后再试"
            429 -> "被限流了（请求太频繁或额度用完）：等一会儿再试，或换一个有额度的模型"
            in 500..599 -> "服务商自己出问题了（$code），不是本机设置错误，稍后再试"
            else -> "服务商返回了错误（$code）"
        }
        return if (detail.isBlank()) hint else "$hint\n服务商原文：$detail"
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