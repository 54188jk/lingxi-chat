package com.lingxi.chat.control

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.lingxi.chat.data.ChatMessage
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.data.ModelConfig
import com.lingxi.chat.net.OpenAiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

/**
 * 操控循环：读屏 → 让模型出一个动作 → 执行 → 再读屏回传，直到模型给出 answer / fail。
 *
 * 不做逐条人工确认（按用户要求），改为这几条硬约束：
 * 步数上限、支付类拦截、密码框不可读不可点、任意时刻 stop() 急停、
 * 老屏幕快照自动丢弃（只保留最近几张，控 token 也控泄露面）。
 */
class AgentRunner(
    private val ctx: Context,
    private val store: ConfigStore,
    private val controller: DeviceController
) {

    private val client = OpenAiClient()

    @Volatile
    private var stopped = false

    /** 每步回调：序号、动作摘要、执行结论 */
    fun interface Step {
        fun onStep(index: Int, action: String, note: String)
    }

    fun stop() {
        stopped = true
        client.cancel()
    }

    suspend fun run(task: String, cfg: ModelConfig, onStep: Step): String {
        stopped = false
        val history = ArrayList<Turn>()
        var screen = controller.readScreen()
        var jsonErrors = 0
        var finalText = ""

        for (step in 1..store.controlMaxSteps) {
            if (stopped) return "已急停，任务未完成。"

            val msgs = buildMessages(task, screen, history, cfg)
            val reply = ask(cfg, msgs)
            if (stopped) return "已急停，任务未完成。"

            when (val parsed = DeviceAction.parse(reply)) {
                is Result.Error -> {
                    jsonErrors++
                    history.add(Turn(reply, "格式错误：${parsed.message}。请重新只输出一个 JSON 对象。", null))
                    onStep.onStep(step, "格式错误", parsed.message)
                    if (jsonErrors >= 3) {
                        return "模型连续 ${jsonErrors} 次没有按格式输出动作，已停止。最后一次回复：${reply.take(160)}"
                    }
                    continue
                }

                is Result.Ok -> {
                    val a = parsed.action
                    jsonErrors = 0

                    if (a.type == "answer" || a.type == "fail") {
                        val tag = if (a.type == "answer") "答复" else "未完成"
                        onStep.onStep(step, tag, a.result)
                        return a.result
                    }

                    val outcome = controller.execute(a)
                    onStep.onStep(step, summarize(a, screen), outcome.feed())
                    history.add(Turn(reply, outcome.feed(), null))

                    val hint = if (!outcome.ok && a.type in setOf("tap", "long_press")) {
                        "提醒：这个动作没生效，别再重复点同一位置；可以 scroll 换视野、返回上一级，" +
                            "或 answer 把情况交给用户。"
                    } else ""
                    history[history.lastIndex] = history.last().let {
                        it.copy(result = it.result + hint)
                    }

                    delay(store.controlThinkMs.toLong().coerceIn(200, 4000))
                    screen = controller.readScreen()
                }
            }
        }
        finalText = "已用完 ${store.controlMaxSteps} 步仍未完成，任务中止。最后屏幕：${screen.pkg}"
        return finalText
    }

    private fun summarize(a: DeviceAction, screen: Screen): String {
        val e = if (a.index in 1..screen.elements.size) screen.elements.first { it.index == a.index } else null
        return when (a.type) {
            "tap" -> "点击 ${e?.let { "「${it.label.ifBlank { it.cls }}」" } ?: "(${a.x},${a.y})"}"
            "long_press" -> "长按 ${e?.let { "「${it.label}」" } ?: "(${a.x},${a.y})"}"
            "swipe" -> "滑动 (${a.x},${a.y})→(${a.x2},${a.y2})"
            "scroll" -> "向${if (a.direction == "up") "上" else "下"}滚动"
            "type" -> "输入「${a.text.take(20)}」"
            "key" -> "按键 ${a.key}"
            "open_app" -> "打开 ${a.target}"
            "intent" -> "直达 ${a.target.take(40)}"
            "shell" -> "命令 ${a.text.take(40)}"
            "fs" -> "文件 ${a.op} ${a.target.take(40)}" + if (a.text.isNotBlank()) " → ${a.text.take(30)}" else ""
            "wait" -> "等待 ${a.ms}ms"
            "read" -> "重新读屏"
            else -> a.type
        }
    }

    /** 一轮对话的消息：协议提示 + 任务 + 最近屏幕 + 动作历史（老快照丢弃） */
    private suspend fun buildMessages(
        task: String,
        screen: Screen,
        history: List<Turn>,
        cfg: ModelConfig
    ): MutableList<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        out.add(ChatMessage("system", systemPrompt(screen.width, screen.height)))
        out.add(ChatMessage("user", "任务：$task"))

        val keepFrom = (history.size - store.controlMemory).coerceAtLeast(0)
        history.subList(0, keepFrom).forEach { t ->
            out.add(ChatMessage("assistant", t.reply))
            out.add(ChatMessage("user", "执行结果：${t.result}\n（更早的屏幕已省略）"))
        }
        history.subList(keepFrom, history.size).forEach { t ->
            out.add(ChatMessage("assistant", t.reply))
            out.add(ChatMessage("user", "执行结果：${t.result}"))
        }

        val shot = if (cfg.vision && store.controlVision && !controller.headless()) controller.capture() else null
        val b64 = shot?.let { Base64.encodeToString(shrinkShot(it.first), Base64.NO_WRAP) }
        val ask = if (controller.headless()) {
            "现在是后台模式：我看不到屏幕，也不碰屏幕。请给出下一步的唯一个 JSON 动作。"
        } else {
            "当前屏幕：\n${screen.render()}\n\n请给出下一步的唯一个 JSON 动作。"
        }
        out.add(
            ChatMessage(
                "user",
                ask,
                b64,
                shot?.second
            )
        )
        return out
    }

    /** 模型调用失败时把真实原因带出去，别让网络问题被误报成「输出格式错误」 */
    class ModelCallFailed(message: String) : Exception(message)

    private suspend fun ask(cfg: ModelConfig, msgs: List<ChatMessage>): String {
        val sb = StringBuilder()
        var failure: String? = null
        try {
            client.streamChat(cfg, msgs).collect { ev ->
                when (ev) {
                    is OpenAiClient.Event.Delta -> sb.append(ev.text)
                    is OpenAiClient.Event.Failed -> failure = ev.message
                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = failure ?: "连接中断：${e.message}"
        }
        if (sb.isBlank() && failure != null) throw ModelCallFailed(failure)
        return sb.toString()
    }

    private fun systemPrompt(w: Int, h: Int): String =
        if (controller.headless()) headlessPrompt() else frontPrompt(w, h)

    private fun frontPrompt(w: Int, h: Int): String = """
你是「糯叽操控」，通过读取屏幕和像人一样点击、滑动、输入来操作这台安卓手机上的任意应用。

输出规则（最重要）：
- 每次回复只输出一个 JSON 对象，不要解释、不要输出多个动作、不要用代码块以外的文字。
- 格式：{"action":"…","index":N,"reason":"一句话"}

可用 action：
- tap：点击元素，优先给 index（下面屏幕列表里的编号）；也可用 x、y 像素坐标（屏幕 ${w}x$h）
- long_press：长按，同样用 index 或 x/y
- swipe：滑动，x,y 起点 → x2,y2 终点
- scroll：翻页，direction 取 up 或 down
- type：向当前已聚焦的输入框写入 text（先用 tap 让输入框获得焦点）
- key：系统键，key 取 back / home / recents / enter
- open_app：打开应用，package 填应用名或包名
- fs：整理手机上的文件和文件夹，op 选操作、path 填位置、text 填目标或内容。${FileOps.HELP}
- wait：等待，ms 为毫秒
- read：只重新读屏，不做任何操作
- answer：任务完成或需要用户接手，result 写给用户的答复
- fail：确实做不到，result 说明原因

行为规则：
1. 先看屏幕再动手；每次执行后我都会把新的屏幕发给你，屏幕变了就要重新判断。
2. 找不到目标就 scroll 或返回上一级；同一位置连点两次没变化就别再点，改用别的路径，实在不行 answer 交给用户。
3. 需要输入时先点输入框，确认聚焦后再 type；type 的内容要精确，别带多余空格和标点。
4. 涉及付款、转账、发送验证码/隐私信息这类不可逆动作，不要替用户完成，直接 answer 让用户自己点。
5. 密码框内容我看不到也不该读，遇到密码页请 answer 让用户输入。
6. 只用界面看得见的元素完成任务，不要臆造菜单路径；不输出任何 shell 命令或系统设置改动。
""".trimIndent()

    /** 后台模式：不看屏、不点屏，只能发直达指令和只读命令 */
    private fun headlessPrompt(): String = """
你是「糯叽操控」的后台模式。你看不到屏幕，也不会替用户点击任何地方，用户的手机照常自己用。
你能做的是：整理手机里的文件和文件夹、拉起应用、打开链接或深链、发起网页搜索、在用户授予 Root/Shizuku 时执行只读查询命令。

输出规则（最重要）：
- 每次回复只输出一个 JSON 对象，不要解释、不要输出多个动作。
- 格式：{"action":"…","package":"…","text":"…","reason":"一句话"}

可用 action：
- open_app：打开应用，package 填应用名或包名（拉起后界面留给用户）
- intent：打开链接/深链，package 填 https://… 、tel:… 、mailto:… 、geo:… 之类；填普通文字时会自动转为网页搜索
- shell：执行只读命令，text 填命令；只允许 dumpsys / pm list / getprop / settings get / ls / cat / id / wm size / date 这类查询，
  任何写操作、删除、模拟点击、改设置的命令都会被拒绝
- fs：整理手机里的文件和文件夹，op 填操作、path 填位置、需要第二个参数时填在 text。可用 op：list / info / mkdir / rename / move / copy / delete / restore / purge / search / read / write / append / open / free。
  path 可以写「下载」「文档」「图片」「内部储存」「应用文件夹」「回收站」，也可以写「下载/糯叽归档」这样的相对路径
- wait：等待，ms 为毫秒
- answer：任务完成或需要用户接手，result 写给用户的答复
- fail：确实做不到，result 说明原因

行为规则：
1. 需要看见界面、需要逐个点击输入的任务，后台模式做不了——直接 answer，告诉用户切到「前台操作」模式再来一次。
2. 整理文件是后台模式的强项：先 fs list 看清现状，再一步一个 move / copy / mkdir / rename；别一上来就删。
3. fs 的 delete 只是放进回收站，用户还能还原；「彻底删除」只有在东西已经在回收站里时才允许，且要 answer 跟用户确认过。
4. 能用一条 open_app / intent / shell / fs 解决就别多步；拿不准就 answer 说明你打算做什么。
5. shell 的输出我会原样回给你，回答时只挑用户关心的信息，别把整段日志贴给用户。
6. 涉及付款、转账、发送验证码/隐私信息这类不可逆动作，不要替用户完成，直接 answer。
7. 最多八步；同一条命令重复执行没有新信息时，直接 answer 收尾。
""".trimIndent()

    /** 一次「模型输出 + 执行结论」 */
    private data class Turn(val reply: String, val result: String, val image: String?)
}

/** 截图喂给视觉模型前统一压一遍：长边不超过 1000，转 JPEG */
internal fun shrinkShot(bytes: ByteArray, maxEdge: Int = 1000): ByteArray {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
    val longEdge = maxOf(bmp.width, bmp.height)
    if (longEdge <= maxEdge && bytes.size < 260_000) {
        bmp.recycle()
        return bytes
    }
    val scale = maxEdge.toFloat() / longEdge
    val scaled = Bitmap.createScaledBitmap(
        bmp,
        (bmp.width * scale).toInt().coerceAtLeast(1),
        (bmp.height * scale).toInt().coerceAtLeast(1),
        true
    )
    val stream = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 58, stream)
    if (scaled !== bmp) scaled.recycle()
    bmp.recycle()
    return stream.toByteArray()
}
