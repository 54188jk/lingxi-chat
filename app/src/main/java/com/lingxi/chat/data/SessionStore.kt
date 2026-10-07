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

    fun count(): Int = dir.listFiles { f -> f.extension == "json" }?.size ?: 0

    /**
     * 导出全部会话为一个 JSON 文本（用于换签名重装、换机、备份）。
     * 格式：{"app":"lingxi-chat","version":1,"exportedAt":时间戳,"sessions":[...]}
     */
    fun exportAll(): String {
        val arr = JSONArray()
        list().forEach { arr.put(it.toJson()) }
        val root = JSONObject()
        root.put("app", "lingxi-chat")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("count", arr.length())
        root.put("sessions", arr)
        return root.toString()
    }

    /**
     * 从导出的 JSON 恢复会话。
     * merge=true 保留现有会话并按 id 覆盖同 id；merge=false 先清空再导入。
     * 返回实际导入的会话数。
     */
    fun importAll(raw: String, merge: Boolean): Int {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("sessions") ?: return 0
        if (!merge) {
            dir.listFiles { f -> f.extension == "json" }?.forEach { it.delete() }
        }
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = try {
                Session.fromJson(o)
            } catch (e: Exception) {
                continue
            }
            if (s.id.isBlank()) continue
            File(dir, "${s.id}.json").writeText(s.toJson().toString())
            n++
        }
        return n
    }
}
