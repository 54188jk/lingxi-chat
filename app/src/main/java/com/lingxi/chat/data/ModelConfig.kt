package com.lingxi.chat.data

import org.json.JSONObject
import java.util.UUID

data class ModelConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var baseUrl: String,
    var apiKey: String,
    var model: String,
    var vision: Boolean = false,
    var sttModel: String = ""
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("baseUrl", baseUrl)
        o.put("apiKey", apiKey)
        o.put("model", model)
        o.put("vision", vision)
        o.put("sttModel", sttModel)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject) = ModelConfig(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", ""),
            baseUrl = o.optString("baseUrl", ""),
            apiKey = o.optString("apiKey", ""),
            model = o.optString("model", ""),
            vision = o.optBoolean("vision", false),
            sttModel = o.optString("sttModel", "")
        )
    }
}
