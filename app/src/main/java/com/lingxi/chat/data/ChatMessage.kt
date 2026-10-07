package com.lingxi.chat.data

import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(
    val role: String,
    var content: String,
    val imageBase64: String? = null,
    val imageMime: String? = null,
    val time: Long = System.currentTimeMillis(),
    /** 联网搜索命中的来源，气泡下方按序号列出可点击 */
    val sources: MutableList<Source> = mutableListOf()
) {

    /** 一条参考来源 */
    data class Source(val title: String, val url: String)

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("role", role)
        o.put("content", content)
        o.put("time", time)
        if (imageBase64 != null) o.put("imageBase64", imageBase64)
        if (imageMime != null) o.put("imageMime", imageMime)
        if (sources.isNotEmpty()) {
            val arr = JSONArray()
            sources.forEach { arr.put(JSONObject().put("title", it.title).put("url", it.url)) }
            o.put("sources", arr)
        }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): ChatMessage {
            val srcs = mutableListOf<Source>()
            o.optJSONArray("sources")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val so = arr.optJSONObject(i) ?: continue
                    val u = so.optString("url", "")
                    if (u.isNotBlank()) srcs.add(Source(so.optString("title", u), u))
                }
            }
            return ChatMessage(
                role = o.optString("role", "user"),
                content = o.optString("content", ""),
                imageBase64 = if (o.has("imageBase64")) o.getString("imageBase64") else null,
                imageMime = if (o.has("imageMime")) o.getString("imageMime") else null,
                time = o.optLong("time", System.currentTimeMillis()),
                sources = srcs
            )
        }
    }
}