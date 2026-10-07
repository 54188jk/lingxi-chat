package com.lingxi.chat.control

import org.json.JSONObject

/**
 * 模型下发的一步操作。协议只允许「一次一个动作」，字段见 [parse]。
 */
data class DeviceAction(
    val type: String,
    val index: Int = -1,
    val x: Int = 0,
    val y: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val text: String = "",
    val direction: String = "down",
    val key: String = "back",
    val target: String = "",
    val ms: Int = 600,
    val reason: String = "",
    val result: String = ""
) {
    companion object {

        /** 模型必须从这些里选一个 */
        val TYPES = setOf(
            "tap", "long_press", "swipe", "scroll", "type", "key",
            "open_app", "wait", "read", "screenshot", "intent", "shell",
            "answer", "fail"
        )

        /** 需要占用屏幕的动作，后台模式一律不执行 */
        val SCREEN_ACTIONS = setOf("tap", "long_press", "swipe", "scroll", "type", "key", "read", "screenshot")

        /** 模型有时把答复写成字符串，有时写成数组，统一收成一段文本 */
        private fun resultText(json: JSONObject): String {
            val arr = json.optJSONArray("result")
            if (arr != null) {
                return (0 until arr.length()).joinToString("\n") { arr.opt(it)?.toString().orEmpty() }
            }
            return json.optString("result", json.optString("answer", ""))
        }

        /**
         * 从模型回复里取第一个 JSON 对象。
         * 返回 success 时携带动作；失败时给出可直接回喂模型的纠错说明。
         */
        fun parse(raw: String): Result {
            val json = firstJsonObject(raw)
                ?: return Result.Error(
                    "没有解析到 JSON。请只输出一个 JSON 对象，不要加解释文字。"
                )
            val type = json.optString("action").lowercase().trim()
            if (type !in TYPES) {
                return Result.Error(
                    "action「$type」不存在。可用值：${TYPES.joinToString("/")}"
                )
            }
            val a = DeviceAction(
                type = type,
                index = json.optInt("index", -1),
                x = json.optInt("x", 0),
                y = json.optInt("y", 0),
                x2 = json.optInt("x2", 0),
                y2 = json.optInt("y2", 0),
                text = json.optString("text", ""),
                direction = json.optString("direction", "down"),
                key = json.optString("key", "back"),
                target = json.optString("package", json.optString("target", "")),
                ms = json.optInt("ms", 600).coerceIn(0, 10_000),
                reason = json.optString("reason", "").take(120),
                result = resultText(json),
            )
            val problem = when (type) {
                "tap", "long_press" ->
                    if (a.index < 0 && (a.x <= 0 || a.y <= 0)) "tap 需要 index 或 x/y" else ""
                "swipe" -> if (a.x <= 0 || a.y2 <= 0) "swipe 需要 x/y 起点与 x2/y2 终点" else ""
                "type" -> if (a.text.isEmpty()) "type 缺少 text" else ""
                "open_app" -> if (a.target.isBlank()) "open_app 缺少 package（包名或应用名）" else ""
                "intent" -> if (a.target.isBlank()) "intent 缺少 package（要打开的链接或直达地址）" else ""
                "shell" -> if (a.text.isBlank()) "shell 缺少 text（要执行的命令）" else ""
                "scroll" -> if (a.direction !in setOf("up", "down", "left", "right")) {
                    "scroll 的 direction 只能是 up/down/left/right"
                } else ""
                "answer", "fail" -> if (a.result.isBlank()) "${type} 需要 result（给用户的答复）" else ""
                else -> ""
            }
            return if (problem.isEmpty()) Result.Ok(a) else Result.Error(problem)
        }

        /** 括号配对扫描，容忍模型把 JSON 包在代码块或前后废话里 */
        private fun firstJsonObject(raw: String): JSONObject? {
            val start = raw.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inStr = false
            var esc = false
            for (i in start until raw.length) {
                val c = raw[i]
                when {
                    esc -> esc = false
                    c == '\\' && inStr -> esc = true
                    c == '"' -> inStr = !inStr
                    !inStr && c == '{' -> depth++
                    !inStr && c == '}' -> {
                        depth--
                        if (depth == 0) {
                            val slice = raw.substring(start, i + 1)
                            runCatching { return JSONObject(slice) }
                        }
                    }
                }
            }
            return null
        }
    }
}

sealed interface Result {
    data class Ok(val action: DeviceAction) : Result
    data class Error(val message: String) : Result
}
