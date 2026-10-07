package com.lingxi.chat.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 免 root 操控通道：系统无障碍服务。
 *
 * 只有用户在本机「设置 → 无障碍」里主动开启后系统才会绑定本服务，
 * 绑定后所有点击/滑动/取词都走 Accessibility 官方接口，不需要 root、不装任何插件。
 */
class ControlService : AccessibilityService() {

    companion object {
        /** 服务实例；未开启时为 null，调用方据此判定通道可用性 */
        @Volatile
        var instance: ControlService? = null

        /** 单次读屏最多回传多少个可交互元素，防止超长上下文 */
        const val ELEMENT_CAP = 90
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ---------------- 屏幕几何 ----------------

    fun screenWidth(): Int = resources.displayMetrics.widthPixels

    fun screenHeight(): Int = resources.displayMetrics.heightPixels

    // ---------------- 手势 ----------------

    /** 点按。duration 给足时间更接近真人按下-抬起，也便于长按判定 */
    fun tap(x: Int, y: Int, durationMs: Long = 160): Boolean =
        gesture(x, y, x, y, durationMs)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 320): Boolean =
        gesture(x1, y1, x2, y2, durationMs)

    private fun gesture(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            if (x1 != x2 || y1 != y2) lineTo(x2.toFloat(), y2.toFloat()) else lineTo(x1 + 0.1f, y1 + 0.1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, maxOf(60L, durationMs))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** 系统键：返回 / 桌面 / 多任务 */
    fun globalKey(which: String): Boolean {
        val action = when (which) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(action)
    }

    /** 向当前聚焦输入框写入文本（含中文，比 shell 的 input text 可靠） */
    fun setText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = findFocusedEditable(root) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return ok || pasteInto(field, text)
    }

    /** ACTION_SET_TEXT 在个别输入框上不生效时的兜底：剪贴板 + 粘贴 */
    private fun pasteInto(field: AccessibilityNodeInfo, text: String): Boolean {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        return runCatching {
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("lingxi", text))
            field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && (node.isFocused || node.className?.toString()
                ?.contains("EditText") == true)
        ) return node
        for (i in 0 until node.childCount) {
            findFocusedEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    // ---------------- 读屏 ----------------

    /** 当前前台应用包名 */
    fun foregroundPackage(): String {
        val active = runCatching { windows.firstOrNull { it.isActive }?.root }.getOrNull()
        return active?.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()
            ?: ""
    }

    /**
     * 采集屏幕可交互元素：按「上→下、左→右」排序并编号，供模型用 index 精确点按。
     * 密码类节点只标注被遮蔽，不回传任何内容。
     */
    fun collectElements(): List<UiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<UiElement>()
        walk(root, out)
        return out.sortedWith(compareBy({ it.top }, { it.left }))
            .mapIndexed { i, e -> e.copy(index = i + 1) }
    }

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<UiElement>) {
        if (node == null || out.size >= ELEMENT_CAP) return
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val interesting = node.isClickable || node.isEditable ||
            !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        if (interesting && bounds.width() > 0 && bounds.height() > 0 && node.isVisibleToUser) {
            val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            val masked = node.isPassword || label.contains("密码") ||
                node.viewIdResourceName?.toString()?.contains("password") == true
            out.add(
                UiElement(
                    index = 0,
                    label = if (masked) "（已遮蔽的密码框）" else label.take(60),
                    desc = if (masked) "" else (node.contentDescription?.toString() ?: "").take(40),
                    cls = shortClass(node.className?.toString()),
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    sensitive = masked,
                    checked = node.isChecked
                )
            )
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), out)
    }

    private fun shortClass(cls: String?): String =
        cls?.substringAfterLast('.')?.take(22) ?: "View"

    /** 屏幕上可读到的纯文本（去重、限长），用于快速判断页面内容 */
    fun visibleText(limit: Int = 1600): String {
        val root = rootInActiveWindow ?: return ""
        val parts = LinkedHashSet<String>()
        collectText(root, parts)
        return parts.joinToString(" / ").take(limit)
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (node == null || out.size >= 120) return
        if (node.isVisibleToUser && !node.isPassword) {
            node.text?.toString()?.trim()?.let { if (it.length > 1) out.add(it) }
            node.contentDescription?.toString()?.trim()?.let { if (it.length > 1) out.add("[描述]$it") }
        }
        for (i in 0 until node.childCount) collectText(node.getChild(i), out)
    }

    // ---------------- 应用控制 ----------------

    fun launch(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(intent); true }.getOrDefault(false)
    }

    /** API 30+ 才有无障碍截图接口；低版本返回 null，调用方降级为纯文本读屏 */
    suspend fun screenshot(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            var resumed = false
            val executor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) mainExecutor else {
                object : java.util.concurrent.Executor {
                    private val h = Handler(Looper.getMainLooper())
                    override fun execute(r: Runnable) { h.post(r) }
                }
            }
            val cb = object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    if (resumed) return
                    resumed = true
                    val bytes = try {
                        val bmp = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer, screenshot.colorSpace
                        )
                        val soft = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                        val stream = ByteArrayOutputStream()
                        (soft ?: bmp)?.compress(Bitmap.CompressFormat.JPEG, 62, stream)
                        soft?.recycle()
                        stream.toByteArray()
                    } catch (_: Exception) {
                        null
                    } finally {
                        screenshot.hardwareBuffer.close()
                    }
                    cont.resume(bytes)
                }

                override fun onFailure(errorCode: Int) {
                    if (resumed) return
                    resumed = true
                    cont.resume(null)
                }
            }
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                cb
            )
        }
    }
}

/** 读屏得到的一个可交互元素 */
data class UiElement(
    val index: Int,
    val label: String,
    val desc: String,
    val cls: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val sensitive: Boolean,
    val checked: Boolean
) {
    val cx: Int get() = (left + right) / 2
    val cy: Int get() = (top + bottom) / 2

    fun render(): String = buildString {
        append("[").append(index).append("] ")
        append(if (editable) "输入框" else if (clickable) "可点" else "文本")
        append(" ")
        append(cls)
        append(" ")
        if (label.isNotBlank()) append("「").append(label).append("」")
        if (desc.isNotBlank() && desc != label) append("〈").append(desc).append("〉")
        if (checked) append(" ✓已选中")
        append(" (").append(cx).append(",").append(cy).append(")")
    }
}
