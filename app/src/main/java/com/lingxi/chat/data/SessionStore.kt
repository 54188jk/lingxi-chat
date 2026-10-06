package com.lingxi.chat.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Session(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val messages: MutableList<ChatMessage> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.put("createdAt", createdAt)
        o.put("updatedAt", updatedAt)
        val arr = JSONArray()
        messages.forEach { arr.put(it.toJson()) }
        o.put("messages", arr)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Session {
            val msgs = mutableListOf<ChatMessage>()
            val arr = o.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until arr.length()) {
                msgs.add(ChatMessage.fromJson(arr.getJSONObject(i)))
            }
            return Session(
                id = o.optString("id", UUID.randomUUID().toString()),
                title = o.optString("title", ""),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                messages = msgs
            )
        }
    }
}

class SessionStore(context: Context) {

    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }

    fun save(session: Session) {
        session.updatedAt = System.currentTimeMillis()
        File(dir, "${session.id}.json").writeText(session.toJson().toString())
    }

    fun load(id: String): Session? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return try {
            Session.fromJson(JSONObject(f.readText()))
        } catch (e: Exception) {
            null
        }
    }

    fun list(): List<Session> {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                Session.fromJson(JSONObject(f.readText()))
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.updatedAt }
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
