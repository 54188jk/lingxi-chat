package com.lingxi.chat.control

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.lingxi.chat.data.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** 操控通道。AUTO 会按「无障碍 → Shizuku → Root」择优，并对不支持的动作自动降级 */
enum class ControlBackend(val key: String, val label: String) {
    AUTO("auto", "自动选择"),
    ACCESSIBILITY("a11y", "无障碍（免 root）"),
    ROOT("root", "Root / su"),
    SHIZUKU("shizuku", "Shizuku");

    companion object {
        fun fromKey(k: String): ControlBackend = entries.firstOrNull { it.key == k } ?: AUTO
    }
}

/** 一次读屏结果 */
data class Screen(
    val pkg: String,
    val width: Int,
    val height: Int,
    val elements: List<UiElement>,
    val text: String,
    val source: String
) {
    fun render(): String = buildString {
        append("前台=").append(pkg.ifBlank { "未知" })
        append(" 屏幕=").append(width).append("x").append(height)
        append(" 采集=").append(source).append("\n")
        if (elements.isEmpty()) {
            append("（没读到可交互元素，可能页面还在加载或该应用不开放节点）\n")
        } else {
            elements.take(70).forEach { append(it.render()).append("\n") }
            if (elements.size > 70) append("…还有 ${elements.size - 70} 个元素未列出\n")
        }
        if (text.isNotBlank()) append("屏上文字：").append(text)
    }
}

/** 一条动作的执行结论 */
data class Outcome(val ok: Boolean, val note: String) {

    fun feed(): String = (if (ok) "已执行" else "未执行") + "：" + note
}

/**
 * 设备操控统一入口：把同一个动作分发到无障碍 / su / Shizuku 三条通道。
 *
 * 安全阀（不做逐条人工确认，按用户要求）：
 * - 支付/转账类按钮默认拒绝（可在设置里放开）
 * - 密码框内容永不回传、不点击
 * - 单次任务最大步数限制
 * - 全局急停（AgentRunner.stop）
 */
class DeviceController(private val ctx: Context, private val store: ConfigStore) {

    // ---------------- 通道状态 ----------------

    fun accessibilityEnabled(): Boolean {
        val expected = serviceName()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun service(): ControlService? =
        if (accessibilityEnabled()) ControlService.instance else null

    fun shizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun rootReady(): Boolean = RootShell.available()

    /** 解析实际可用通道；返回 null 表示一条都不通 */
    fun resolve(): List<ControlBackend> {
        val wanted = ControlBackend.fromKey(store.controlBackend)
        val chain = ArrayList<ControlBackend>()
        fun add(b: ControlBackend) {
            val ok = when (b) {
                ControlBackend.ACCESSIBILITY -> service() != null
                ControlBackend.ROOT -> rootReady()
                ControlBackend.SHIZUKU -> shizukuReady()
                ControlBackend.AUTO -> false
            }
            if (ok && b !in chain) chain.add(b)
        }
        if (wanted == ControlBackend.AUTO) {
            add(ControlBackend.ACCESSIBILITY); add(ControlBackend.SHIZUKU); add(ControlBackend.ROOT)
        } else {
            add(wanted)
            if (wanted != ControlBackend.ACCESSIBILITY) add(ControlBackend.ACCESSIBILITY)
            if (wanted != ControlBackend.SHIZUKU) add(ControlBackend.SHIZUKU)
            if (wanted != ControlBackend.ROOT) add(ControlBackend.ROOT)
        }
        return chain
    }

    fun statusText(): String = buildString {
        append("无障碍：").append(if (accessibilityEnabled()) "已开启" else "未开启")
        append(" · Root：").append(if (rootReady()) "可用" else "无")
        append(" · Shizuku：")
        append(
            when {
                !shizukuRunning() -> "未运行"
                shizukuReady() -> "已授权"
                else -> "运行中但未授权"
            }
        )
    }

    // ---------------- 读屏 / 截图 ----------------

    /** 后台模式：不看屏幕、不占屏幕，只发指令 */
    fun headless(): Boolean = store.controlMode == "back"

    suspend fun readScreen(): Screen = withContext(Dispatchers.IO) {
        if (headless()) {
            Screen("", 0, 0, emptyList(), "", "后台模式不读屏")
        } else {
            readScreenReal()
        }
    }

    private fun readScreenReal(): Screen {
        val svc = service()
        if (svc != null) {
            return Screen(
                pkg = svc.foregroundPackage(),
                width = svc.screenWidth(),
                height = svc.screenHeight(),
                elements = svc.collectElements(),
                text = svc.visibleText(),
                source = "无障碍节点"
            )
        }
        val shell = firstShell()
        return if (shell == null) {
            Screen("", 0, 0, emptyList(), "", "不可用")
        } else {
            Uiautomator.read(ctx, shell)
        }
    }

    private fun firstShell(): Shell? = when {
        shizukuReady() -> ShizukuShell
        rootReady() -> RootShell
        else -> null
    }

    suspend fun capture(): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        if (headless()) return@withContext null
        service()?.screenshot()?.let { it to "image/jpeg" }
            ?: firstShell()?.let { shell ->
                val out = shell.exec("screencap -p")
                if (out.isNotEmpty()) out to "image/png" else null
            }
    }

    // ---------------- 执行 ----------------

    suspend fun execute(a: DeviceAction): Outcome {
        if (a.type == "answer" || a.type == "fail") {
            return Outcome(true, a.result)
        }
        val chain = resolve()
        if (headless() && a.type in DeviceAction.SCREEN_ACTIONS) {
            return Outcome(
                false,
                "现在是后台模式，不读屏也不动你的屏幕。可改用 open_app / intent / shell，" +
                    "或让用户到 设置 → 系统操控 切换为前台模式。"
            )
        }
        if (chain.isEmpty() && a.type != "intent") {
            return Outcome(false, "没有可用操控通道。请在 设置 → 系统操控 里开启无障碍，或授予 Root / Shizuku 权限。")
        }
        if (a.type == "intent") return openIntent(a)
        if (a.type == "shell") return runShell(a)

        // 读屏供 index 解析与禁点判定
        val screen = readScreen()
        val target = elementFor(a, screen)

        if (store.controlBlockPayments && target != null && isPayment(target)) {
            return Outcome(
                false,
                "「${target.label}」属于支付/转账类操作，已按你的安全设置拦截。请在手机上自行完成，" +
                    "或到 设置 → 系统操控 关闭「禁止支付类操作」。"
            )
        }
        if (target != null && target.sensitive && a.type != "type") {
            return Outcome(false, "目标是密码输入框，无障碍通道不允许读取或点按密码控件。")
        }

        val res = runChain(chain, a, screen, target)
        // 界面稳定等待：不等够时间下一步常会点到旧界面
        delay(store.controlSettleMs.toLong() + (0..180).random())
        return res
    }

    /**
     * 直达：用 Intent 打开链接/深链，或直接发起搜索。
     * 只走 VIEW/SEARCH，从不执行 shell，所以无障碍通道也支持。
     */
    private fun openIntent(a: DeviceAction): Outcome {
        val raw = a.target.trim()
        if (store.controlBlockPayments &&
            listOf("alipay", "tenpay", "wechatpay", "wx://", "uppay", "bank").any { raw.contains(it, true) }
        ) {
            return Outcome(false, "该链接指向支付/收款，已按你的安全设置拦截。")
        }
        val url = when {
            raw.contains("://") || raw.startsWith("tel:") || raw.startsWith("mailto:") ||
                raw.startsWith("geo:") || raw.startsWith("file:") -> raw
            else -> "https://" + raw
        }
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { ctx.startActivity(intent) }.isSuccess) {
            return Outcome(true, "已打开 ${url.take(80)}")
        }
        if (a.type == "intent" && !raw.contains("://")) {
            val search = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra("query", raw.take(200))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { ctx.startActivity(search) }.isSuccess) {
                return Outcome(true, "没有可直接打开的应用，已改为网页搜索「${raw.take(50)}」")
            }
        }
        return Outcome(false, "系统里没有能打开「${url.take(60)}」的应用")
    }

    /** 后台命令：只有 Root / Shizuku 通道能跑，且只允许只读类命令 */
    private fun runShell(a: DeviceAction): Outcome {
        val shell = firstShell()
            ?: return Outcome(false, "执行命令需要 Root 或 Shizuku 授权；只有无障碍时请改用 open_app / intent")
        val cmd = a.text.trim().replace("\n", " ")
        val risk = SHELL_DENIED.firstOrNull { cmd.contains(it, true) }
        if (risk != null) {
            return Outcome(false, "命令包含「$risk」，属于会改动设备或冒充点击的高危操作，已拒绝执行。")
        }
        if (!SHELL_ALLOWED.any { cmd.contains(it, true) }) {
            return Outcome(false, "后台命令只允许只读查询（dumpsys / pm list / getprop / settings get / ls / cat / id / whoami / uname）。")
        }
        val out = String(shell.exec("$cmd 2>&1"), Charsets.UTF_8).trim()
        return if (out.isBlank()) Outcome(false, "命令没有输出：${cmd.take(60)}")
        else Outcome(true, "${cmd.take(60)} → ${out.take(1200)}")
    }

    private fun elementFor(a: DeviceAction, screen: Screen): UiElement? {
        if (a.index in 1..screen.elements.size) {
            return screen.elements.first { it.index == a.index }
        }
        return screen.elements.firstOrNull {
            it.clickable && it.left <= a.x && a.x <= it.right && it.top <= a.y && a.y <= it.bottom
        }
    }

    private fun isPayment(e: UiElement): Boolean {
        val s = (e.label + e.desc)
        return listOf("支付", "付款", "转账", "汇款", "立即还款", "确认扣款", "免密支付", "提现")
            .any { s.contains(it) }
    }

    private suspend fun runChain(
        chain: List<ControlBackend>,
        a: DeviceAction,
        screen: Screen,
        target: UiElement?
    ): Outcome {
        var last = Outcome(false, "通道都不支持该动作")
        for (backend in chain) {
            val r = runOne(backend, a, screen, target)
            if (r.ok) return if (backend != chain.first()) r.copy(note = r.note + "（由 ${backend.label} 完成）") else r
            last = r
        }
        return last
    }

    private suspend fun runOne(
        backend: ControlBackend,
        a: DeviceAction,
        screen: Screen,
        target: UiElement?
    ): Outcome = withContext(Dispatchers.IO) {
        val cx = target?.cx ?: a.x
        val cy = target?.cy ?: a.y
        val w = screen.width.takeIf { it > 0 } ?: 1080
        val h = screen.height.takeIf { it > 0 } ?: 1920
        when (backend) {
            ControlBackend.ACCESSIBILITY -> {
                val svc = service() ?: return@withContext Outcome(false, "无障碍服务未运行")
                when (a.type) {
                    "tap" -> o(svc.tap(cx, cy, 160), "点击 ($cx,$cy)")
                    "long_press" -> o(svc.tap(cx, cy, 700), "长按 ($cx,$cy)")
                    "swipe" -> o(svc.swipe(a.x, a.y, a.x2, a.y2, 320), "滑动 (${a.x},${a.y})→(${a.x2},${a.y2})")
                    "scroll" -> {
                        val dir = if (a.direction == "up") 1 else -1
                        val ok = svc.swipe(w / 2, h * 3 / 4, w / 2, h * 3 / 4 + dir * h / 3, 360)
                        o(ok, "向${if (a.direction == "up") "上" else "下"}滚动")
                    }
                    "type" -> o(svc.setText(a.text), "输入「${a.text.take(24)}」")
                    "key" -> o(svc.globalKey(a.key), "系统键 ${a.key}")
                    "open_app" -> {
                        val pkg = resolvePackage(a.target)
                        if (pkg == null) Outcome(false, "找不到应用「${a.target}」")
                        else o(svc.launch(pkg), "打开 ${a.target}（$pkg）")
                    }
                    "wait" -> {
                        delay(a.ms.toLong())
                        Outcome(true, "等待 ${a.ms}ms")
                    }
                    "read", "screenshot" -> Outcome(true, screen.render().take(200))
                    else -> Outcome(false, "无障碍不支持 ${a.type}")
                }
            }

            ControlBackend.ROOT, ControlBackend.SHIZUKU -> {
                val shell = if (backend == ControlBackend.SHIZUKU) ShizukuShell else RootShell
                when (a.type) {
                    "tap" -> o(shell.ok("input tap $cx $cy"), "点击 ($cx,$cy)")
                    "long_press" -> o(shell.ok("input swipe $cx $cy ${cx + 1} ${cy + 1} 700"), "长按 ($cx,$cy)")
                    "swipe" -> o(
                        shell.ok("input swipe ${a.x} ${a.y} ${a.x2} ${a.y2} 320"),
                        "滑动 (${a.x},${a.y})→(${a.x2},${a.y2})"
                    )
                    "scroll" -> {
                        val y2 = if (a.direction == "up") h / 4 else h * 3 / 4
                        val y1 = if (a.direction == "up") h * 3 / 4 else h / 4
                        o(shell.ok("input swipe ${w / 2} $y1 ${w / 2} $y2 360"), "滚动")
                    }
                    "type" -> when {
                        !a.text.all { it.code < 128 } -> Outcome(
                            false,
                            "${backend.label} 通道的 input text 打不了中文"
                        )

                        else -> o(shell.ok("input text '${shellQuote(a.text)}'"), "输入「${a.text.take(24)}」")
                    }
                    "key" -> {
                        val code = when (a.key) {
                            "back" -> "KEYCODE_BACK"
                            "home" -> "KEYCODE_HOME"
                            "recents" -> "KEYCODE_APP_SWITCH"
                            "enter" -> "KEYCODE_ENTER"
                            else -> "KEYCODE_${a.key.uppercase()}"
                        }
                        o(shell.ok("input keyevent $code"), "按键 $code")
                    }
                    "open_app" -> {
                        val pkg = resolvePackage(a.target)
                        if (pkg == null) Outcome(false, "找不到应用「${a.target}」")
                        else o(
                            shell.ok("monkey -p $pkg -c android.intent.category.LAUNCHER 1"),
                            "打开 $pkg"
                        )
                    }
                    "wait" -> {
                        delay(a.ms.toLong()); Outcome(true, "等待 ${a.ms}ms")
                    }
                    "read", "screenshot" -> Outcome(true, "已读屏")
                    else -> Outcome(false, "${backend.label} 不支持 ${a.type}")
                }
            }

            ControlBackend.AUTO -> Outcome(false, "内部错误：AUTO 不该被执行")
        }
    }

    private fun o(ok: Boolean, note: String) = Outcome(ok, note)

    companion object {
        /** 后台命令白名单：只允许读取信息 */
        private val SHELL_ALLOWED = listOf(
            "dumpsys", "pm list", "pm dump", "getprop", "settings get",
            "cmd app", "cmd role", "ls", "cat", "id", "whoami", "uname",
            "df", "top -n", "ip route", "ifconfig", "wm size", "wm density", "date"
        )

        /** 明确拒绝：改动设备、清理数据、模拟点击、耗电扰民 */
        private val SHELL_DENIED = listOf(
            "rm ", "rmdir", "mkfs", "dd ", ">", "pm clear", "pm uninstall", "pm install",
            "settings put", "settings delete", "input ", "reboot", "shutdown", "svc ",
            "am force-stop", "am kill", "kill", "pkill", "monkey", "su ", "chmod", "chown",
            "setprop", "mount", "umount", "content insert", "content delete", "content update",
            ";", "&&", "||", "|", "sh ", " bash", "eval", "wget", "curl", "am start"
        )
    }

    private fun shellQuote(s: String): String =
        s.replace("'", "").replace(" ", "%s")

    /** 包名或应用名 → 包名 */
    private fun resolvePackage(query: String): String? {
        if (query.isBlank()) return null
        if (query.contains('.') && ctx.packageManager.getLaunchIntentForPackage(query) != null) return query
        val q = query.lowercase().trim()
        val candidates = runCatching {
            ctx.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
        }.getOrDefault(emptyList())
        candidates.forEach { ri ->
            val label = ri.loadLabel(ctx.packageManager).toString().lowercase()
            val pkg = ri.activityInfo.packageName.lowercase()
            if (label == q || pkg == q) return ri.activityInfo.packageName
        }
        candidates.forEach { ri ->
            val label = ri.loadLabel(ctx.packageManager).toString().lowercase()
            val pkg = ri.activityInfo.packageName.lowercase()
            if (label.contains(q) || q.contains(label) || pkg.contains(q)) return ri.activityInfo.packageName
        }
        return null
    }

    private fun serviceName(): String =
        "${ctx.packageName}/${ControlService::class.java.name}"
}
