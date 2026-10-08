package com.lingxi.chat

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lingxi.chat.control.AgentBus
import com.lingxi.chat.control.AgentControl
import com.lingxi.chat.control.ControlBackend
import com.lingxi.chat.control.DeviceController
import com.lingxi.chat.control.FileOps
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.data.HistoryStore
import com.lingxi.chat.databinding.ActivityControlBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 操控台：一眼看清「现在能不能动手、用哪条路动手」，能自检、能一键交办、能急停。
 * 三通道（免 Root 的系统无障碍 / Root / Shizuku）状态都是现场实测出来的，不是读设置项。
 */
class ControlActivity : BaseActivity() {

    private lateinit var b: ActivityControlBinding
    private val store by lazy { ConfigStore(this) }
    private val controller by lazy { DeviceController(this, store) }

    private class Quick(val label: String, val task: String, val needFront: Boolean)

    private val quicks = listOf(
        Quick("按类型整理下载", "把「下载」里的文件按类型整理进子文件夹：安装包、压缩包、文档、图片、视频、其他；先告诉我你会怎么分，再动手。", false),
        Quick("新建归档文件夹", "在内部储存里新建一个文件夹，名字叫「灵犀归档」。", false),
        Quick("清理旧安装包", "找出「下载」里的安装包文件，把一个月前又不是当前版本的列出来给我看，先别删。", false),
        Quick("看剩余空间", "看看内部储存还剩多少空间，并列出最占地方的几个顶层文件夹。", false),
        Quick("找文件", "在整个内部储存里找名字包含「报告」的文件，把位置和大小列出来。", false),
        Quick("拉起微信", "打开微信。", false),
        Quick("把这段发出去", "打开微信，进入最近一个聊天，把我口述的内容发出去；发送前必须停下来让我自己确认。", true),
        Quick("填表单", "打开浏览器，把我说的内容一项项填进网页表单里；提交之前先停下来交给我。", true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityControlBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnA11y.setOnClickListener { openAccessibility() }
        b.btnAdv.setOnClickListener { showBackendHelp() }
        b.btnProbe.setOnClickListener { probe() }
        b.btnAdvancedSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            animateForward()
        }
        b.cardFront.setOnClickListener { pickMode("front") }
        b.cardBack.setOnClickListener { pickMode("back") }
        b.btnFiles.setOnClickListener {
            startActivity(Intent(this, FilesActivity::class.java))
            animateForward()
        }
        b.btnFilePerm.setOnClickListener { askStorage() }
        b.btnHalt.setOnClickListener {
            AgentControl.halt(this)
            b.tvLog.text = "已发出急停，正在停下当前这一步…"
        }
        b.swFileOps.setOnCheckedChangeListener { _, checked ->
            store.controlFileOps = checked
            refreshFileRow()
        }
        buildQuicks()
    }

    override fun onResume() {
        super.onResume()
        b.swFileOps.setOnCheckedChangeListener(null)
        b.swFileOps.isChecked = store.controlFileOps
        AgentBus.listener = { line -> runOnUiThread { appendLog(line) } }
        b.tvLog.text = if (AgentBus.stepLines.isEmpty()) defaultLog() else AgentBus.stepLines.joinToString("\n")
        refresh()
    }

    override fun onPause() {
        AgentBus.listener = null
        super.onPause()
    }

    private fun defaultLog() = "还没有任务。点「马上做一件」里的任何一条，或者回聊天页把要办的事说出来。"

    private fun appendLog(line: String) {
        val cur = b.tvLog.text.toString()
        if (cur.contains(line)) return
        b.tvLog.text = (if (cur == defaultLog()) "" else cur + "\n") + line
        scrollToEnd()
    }

    private fun scrollToEnd() {
        (b.tvLog.parent.parent as? android.widget.ScrollView)?.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
    }



    // ---------------- 状态 ----------------

    private fun refresh() {
        val a11y = controller.accessibilityEnabled()
        b.tvA11yState.text = if (a11y) "现在的状态：已经开启，可以直接用" else "现在的状态：没开启"
        b.btnA11y.text = if (a11y) "重新检查" else "去开启"
        b.btnA11y.setBackgroundResource(if (a11y) R.drawable.bg_btn_white else R.drawable.bg_btn_primary)
        b.btnA11y.setTextColor(
            ContextCompat.getColor(
                this,
                if (a11y) R.color.btn_outline_fg else R.color.btn_solid_fg
            )
        )

        val root = controller.rootReady()
        val shizukuRun = controller.shizukuRunning()
        val shizukuOk = controller.shizukuReady()
        b.tvAdvState.text = buildString {
            append("Root：").append(if (root) "可用" else "没有")
            append(" · Shizuku：")
            append(if (!shizukuRun) "没在运行" else if (shizukuOk) "已授权" else "在运行但还没授权")
        }
        b.btnAdv.text = if (root || shizukuOk) "详情" else "怎么装"

        val chain = controller.resolve()
        b.tvRunning.text = if (AgentBus.running) "正在做事" else "空闲"
        b.tvRunning.setTextColor(
            ContextCompat.getColor(this, if (AgentBus.running) R.color.accent else R.color.text_secondary)
        )
        b.btnHalt.alpha = if (AgentBus.running) 1f else 0.4f

        val want = ControlBackend.fromKey(store.controlBackend)
        b.tvA11yHint.text = if (chain.isEmpty())
            "现在一条路都不通，先开启下面这个开关"
        else "现在实际会用到的：" + chain.joinToString(" → ") { it.label } +
                if (want == ControlBackend.AUTO) "" else "（你指定了优先走「${want.label}」）"

        val front = store.controlMode != "back"
        b.tvFrontState.text = if (front) "正在用这种" else "点这里切换"
        b.tvBackState.text = if (!front) "正在用这种" else "点这里切换"
        b.cardFront.setBackgroundResource(if (front) R.drawable.bg_card_active else R.drawable.bg_card)
        b.cardBack.setBackgroundResource(if (!front) R.drawable.bg_card_active else R.drawable.bg_card)
        refreshFileRow()
    }

    private fun refreshFileRow() {
        val can = FileOps.canUseStorage(this)
        b.tvFileState.text = buildString {
            append(if (store.controlFileOps) "已允许整理文件" else "已禁止整理文件")
            append(" · ").append(if (can) "存储权限：已给，能整理下载、文档这些位置" else "存储权限：还没给，只能整理灵犀自己的文件夹")
            append(" · ").append(FileOps.freeGb(this@ControlActivity))
        }
        b.btnFilePerm.visibility = if (can) View.GONE else View.VISIBLE
    }

    private fun pickMode(key: String) {
        if (store.controlMode == key) {
            toast(if (key == "back") "后台办事：不读屏、不占屏，手机你继续用" else "前台操作：会读屏并替你点，这期间先别碰手机")
            return
        }
        if (key == "front" && !controller.accessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("前台操作需要系统无障碍")
                .setMessage(
                    "要替你看屏幕、点按、输入，得先在系统的无障碍列表里把「灵犀AI」打开。\n\n" +
                            "现在去开吗？"
                )
                .setPositiveButton("去开启") { _, _ ->
                    openAccessibility()
                    store.controlMode = key
                    refresh()
                }
                .setNegativeButton("先用后台", { _, _ ->
                    store.controlMode = "back"
                    refresh()
                })
                .show()
            return
        }
        store.controlMode = key
        refresh()
        toast(if (key == "back") "已切到后台办事" else "已切到前台操作")
    }

    private fun openAccessibility() {
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("在列表里找「灵犀AI」→ 打开开关")
        }.onFailure { toast("跳转失败，请到 系统设置 → 无障碍 里找「灵犀AI」") }
    }

    private fun askStorage() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            HistoryStore.openRootPermissionSettings(this)
        } else {
            HistoryStore.requestRuntimePermission(this)
        }
    }

    // ---------------- 自检 ----------------

    private fun probe() {
        b.btnProbe.text = "检查中…"
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val sb = StringBuilder()
                if (store.controlMode == "back") {
                    sb.append("现在是后台办事：不去读屏，也不会碰你的屏幕。\n")
                    val chain = controller.resolve()
                    sb.append("能用的路：").append(if (chain.isEmpty()) "暂时一条都没有" else chain.joinToString("、") { it.label }).append("\n")
                    sb.append("文件整理：").append(if (store.controlFileOps && FileOps.canUseStorage(this@ControlActivity)) "可以用" else "用不了（缺权限或被关掉）")
                    sb.append("\n\n").append(FileOps.run(this@ControlActivity, "list", "内部储存", "").note.take(600))
                    sb.toString()
                } else {
                    val screen = controller.readScreen()
                    sb.append("读屏来源：").append(screen.source).append("\n")
                    sb.append("看到屏幕上有 ").append(screen.elements.size).append(" 个可以互动的东西。\n")
                    if (screen.elements.isEmpty()) {
                        sb.append("\n没读到东西通常是这个页面还没开放节点，换一个应用再试（比如桌面或设置）。")
                    } else {
                        sb.append("\n前面几个：\n")
                        screen.elements.take(6).forEach { sb.append("· ").append(it.label.ifBlank { it.cls }).append('\n') }
                        sb.append("\n能列出东西就说明通道可用。")
                    }
                    sb.append("\n\n通道实测：").append(controller.statusText())
                    sb.toString()
                }
            }
            b.btnProbe.text = "试一眼：能不能看见屏幕"
            if (isFinishing || isDestroyed) return@launch
            AlertDialog.Builder(this@ControlActivity)
                .setTitle("自检结果")
                .setMessage(text)
                .setPositiveButton("知道了", null)
                .setNeutralButton("去聊天页发任务") { _, _ -> backToChat("") }
                .show()
        }
    }

    private fun showBackendHelp() {
        AlertDialog.Builder(this)
            .setTitle("三条路，任选一条")
            .setMessage(
                "1. 免 Root（推荐）：系统的「无障碍」里打开「灵犀AI」。能看屏幕、能点按输入，绝大多数任务都够用。\n\n" +
                        "2. Root：手机已经 root 过，灵犀可以直接下指令，动作更快更稳。\n\n" +
                        "3. Shizuku：没 root 但装了 Shizuku 并在运行，且给灵犀授过权。\n\n" +
                        "在 更多操控设置 里可以指定优先走哪条；走不通会自动退回能用的一条。"
            )
            .setNeutralButton("更多操控设置") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
                animateForward()
            }
            .setPositiveButton("去开无障碍") { _, _ -> openAccessibility() }
            .setNegativeButton("知道了", null)
            .show()
    }

    // ---------------- 一键交办 ----------------

    private fun buildQuicks() {
        b.llQuick.removeAllViews()
        val density = resources.displayMetrics.density
        val cols = 2
        var row: LinearLayout? = null
        quicks.forEachIndexed { idx, q ->
            if (idx % cols == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = (6 * density).toInt() }
                }
                b.llQuick.addView(row)
            }
            val chip = TextView(this).apply {
                text = q.label + if (q.needFront) "（前台）" else ""
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@ControlActivity, R.color.text_primary))
                setBackgroundResource(R.drawable.bg_tool_cell)
                gravity = Gravity.CENTER
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { hand(q) }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (idx % cols == 1) lp.marginStart = (6 * density).toInt()
            row?.addView(chip, lp)
        }
    }

    private fun hand(q: Quick) {
        if (store.getActiveModel() == null) {
            AlertDialog.Builder(this)
                .setTitle("还没配模型")
                .setMessage("灵犀要先把一个云端模型配好才办事。现在去配吗？")
                .setPositiveButton("去配置") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                    animateForward()
                }
                .setNegativeButton("稍后", null)
                .show()
            return
        }
        if (q.needFront && store.controlMode == "back") {
            AlertDialog.Builder(this)
                .setTitle("这件事需要前台操作")
                .setMessage("它会读屏幕并替你点按，中途请你先别碰手机。切到前台并开始吗？")
                .setPositiveButton("切过去开始") { _, _ ->
                    store.controlMode = "front"
                    if (controller.accessibilityEnabled()) {
                        store.controlEnabled = true
                        startTask(q.task)
                    } else {
                        refresh()
                        openAccessibility()
                        toast("先在系统无障碍里打开「灵犀AI」，回来再点一次")
                    }
                }
                .setNegativeButton("算了", null)
                .show()
            return
        }
        if (q.needFront && !controller.accessibilityEnabled()) {
            toast("前台操作要先开系统无障碍")
            openAccessibility()
            return
        }
        if (AgentBus.running) {
            toast("已经有一件事在做了，先点下面的紧急停止")
            return
        }
        store.controlEnabled = true
        startTask(q.task)
    }

    private fun startTask(task: String) {
        AgentControl.launch(this, task)
        b.tvLog.text = "已交办：$task"
        refresh()
        (b.root.parent as? View)?.postDelayed({ refresh() }, 1200)
    }

    private fun backToChat(text: String) {
        val i = Intent(this, MainActivity::class.java)
        if (text.isNotBlank()) i.putExtra("prefill_text", text)
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(i)
    }
}
