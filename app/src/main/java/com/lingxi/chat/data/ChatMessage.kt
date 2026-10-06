package com.lingxi.chat.data

import org.json.JSONObject

data class ChatMessage(
    val role: String,
    var content: String,
    val imageBase64: String? = null,
    val imageMime: String? = null,
    val time: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("role", role)
        o.put("content", content)
        o.put("time", time)
        if (imageBase64 != null) o.put("imageBase64", imageBase64)
        if (imageMime != null) o.put("imageMime", imageMime)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): ChatMessage {
            return ChatMessage(
                role = o.optString("role", "user"),
                content = o.optString("content", ""),
                imageBase64 = if (o.has("imageBase64")) o.getString("imageBase64") else null,
                imageMime = if (o.has("imageMime")) o.getString("imageMime") else null,
                time = o.optLong("time", System.currentTimeMillis())
            )
        }
    }
}
