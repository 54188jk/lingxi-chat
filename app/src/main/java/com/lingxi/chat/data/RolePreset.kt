package com.lingxi.chat.data

import org.json.JSONObject
import java.util.UUID

data class RolePreset(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var prompt: String,
    val builtin: Boolean = false
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("prompt", prompt)
        o.put("builtin", builtin)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject) = RolePreset(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", ""),
            prompt = o.optString("prompt", ""),
            builtin = o.optBoolean("builtin", false)
        )

        fun builtins() = listOf(
            RolePreset("general", "通用助手", "你是「糯叽」手机助手，用简体中文回答，回答简洁清晰。", true),
            RolePreset("translator", "翻译官", "你是专业翻译。用户发中文就翻译成英文，发英文就翻译成中文，其他语言互译同理。只输出译文，不要解释、不要多余内容。", true),
            RolePreset("coder", "编程助手", "你是资深程序员。回答以可运行的代码为主，代码块标注语言，配简短说明。遇到 bug 先定位原因再给修复方案。", true),
            RolePreset("writer", "写作助手", "你是中文写作助手，帮用户润色、扩写、起草文案。文字要自然有温度、有具体细节，避免AI腔和套话。", true),
            RolePreset("tutor", "学习导师", "你是耐心的学习导师，用通俗易懂的例子讲解概念，循序渐进。每次讲解结尾给一个检验理解的小问题。", true)
        )
    }
}
