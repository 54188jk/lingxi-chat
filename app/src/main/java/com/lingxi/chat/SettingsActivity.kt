package com.lingxi.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import com.lingxi.chat.control.ControlBackend
import com.lingxi.chat.control.DeviceController
import com.lingxi.chat.control.ShizukuShell
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.data.ModelConfig
import com.lingxi.chat.data.RolePreset
import com.lingxi.chat.data.SessionStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.lingxi.chat.databinding.ActivitySettingsBinding

class SettingsActivity : BaseActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var store: ConfigStore
    private var models = mutableListOf<ModelConfig>()
    private var roles = mutableListOf<RolePreset>()

    private val presets = listOf(
        Preset("自定义", "", "", false),
        Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", vision = false),
        Preset("DeepSeek（推理 R1）", "https://api.deepseek.com/v1", "deepseek-reasoner", vision = false),
        Preset("OpenAI GPT-4o", "https://api.openai.com/v1", "gpt-4o", vision = true),
        Preset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", vision = false),
        Preset("通义千问 VL（视觉）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-vl-max", vision = true),
        Preset("智谱 GLM-4V（视觉）", "https://open.bigmodel.cn/api/paas/v4", "glm-4v", vision = true),
        Preset("智谱 GLM-4-Air", "https://open.bigmodel.cn/api/paas/v4", "glm-4-air", vision = false),
        Preset("Kimi 月之暗面", "https://api.moonshot.cn/v1", "moonshot-v1-8k", vision = false)
    )

    // 免费语音服务预设：注册即送免费额度，接口与 OpenAI 的 /audio/transcriptions 兼容
    private val freeSttPresets = listOf(
        ModelConfig(
            name = "免费语音·硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            apiKey = "",
            model = "Qwen/Qwen2.5-7B-Instruct",
            sttModel = "FunAudioLLM/SenseVoiceSmall"
        ),
        ModelConfig(
            name = "免费语音·Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            apiKey = "",
            model = "llama-3.3-70b-versatile",
            sttModel = "whisper-large-v3-turbo"
        )
    )

    private val themes = listOf(
        "dark" to "深色",
        "light" to "浅色",
        "system" to "跟随系统"
    )

    private val fontScales = listOf(
        0.85f to "小",
        1.0f to "标准",
        1.15f to "大",
        1.3f to "特大"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = ConfigStore(this)
        b.btnBack.setOnClickListener { finish() }
        b.rvModels.layoutManager = LinearLayoutManager(this)
        b.rvRoles.layoutManager = LinearLayoutManager(this)
        b.btnAddModel.setOnClickListener { editModel(null) }
        b.btnAddRole.setOnClickListener { editRole(null) }
        b.btnAddFreeStt.setOnClickListener { pickFreeStt() }

        b.swEnterSend.isChecked = store.enterToSend
        b.swEnterSend.setOnCheckedChangeListener { _, checked ->
            store.enterToSend = checked
            toast(if (checked) "回车将直接发送" else "回车改为换行")
        }

        b.btnHistoryVersion.setOnClickListener {
            VersionsUi.open(this, BuildConfig.VERSION_NAME)
        }

        b.btnBackupSessions.setOnClickListener {
            val n = sessionStore.count()
            if (n == 0) {
                toast("当前没有会话可备份")
            } else {
                createBackupDoc.launch("lingxi-sessions-${System.currentTimeMillis()}.json")
            }
        }
        b.btnRestoreSessions.setOnClickListener {
            openBackupDoc.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        refreshBackupInfo()

        b.spTheme.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, themes.map { it.second })
        b.spTheme.setSelection(maxOf(0, themes.indexOfFirst { it.first == store.themeMode }))
        b.spTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val mode = themes[pos].first
                if (mode != store.themeMode) {
                    store.themeMode = mode
                    AppCompatDelegate.setDefaultNightMode(
                        when (mode) {
                            "light" -> AppCompatDelegate.MODE_NIGHT_NO
                            "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                            else -> AppCompatDelegate.MODE_NIGHT_YES
                        }
                    )
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        b.spFontScale.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fontScales.map { it.second })
        b.spFontScale.setSelection(maxOf(0, fontScales.indexOfFirst { it.first == store.fontScale }))
        b.spFontScale.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val scale = fontScales[pos].first
                if (scale != store.fontScale) {
                    store.fontScale = scale
                    recreate()
                    toast("字号已调整")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        refreshModels()
        refreshRoles()
        setupControl()

        b.tvVersion.text = "当前版本 v${BuildConfig.VERSION_NAME}"
        b.btnCheckUpdate.setOnClickListener {
            UpdateUi.check(this, BuildConfig.VERSION_NAME, silent = false)
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚在系统设置里开了无障碍 / 授权了 Shizuku，回到本页要重新探测
        refreshControlStatus()
    }

    // ---------------- 系统操控 ----------------

    private fun setupControl() {
        val modes = listOf(
            "前台接管屏幕（会读屏、会点击）" to "front",
            "后台只发指令（不占屏幕）" to "back"
        )
        b.spControlMode.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes.map { it.first })
        b.spControlMode.setSelection(maxOf(0, modes.indexOfFirst { it.second == store.controlMode }))
        b.tvControlModeHint.text = modeHint(store.controlMode)
        b.spControlMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val key = modes[pos].second
                if (key != store.controlMode) {
                    store.controlMode = key
                    b.tvControlModeHint.text = modeHint(key)
                    toast(if (key == "back") "后台模式：不读屏、不点击，只发指令" else "前台模式：读屏并替你操作")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val backends = ControlBackend.entries.map { it.label }
        b.spControlBackend.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, backends)
        b.spControlBackend.setSelection(
            maxOf(0, ControlBackend.entries.indexOfFirst { it.key == store.controlBackend })
        )
        b.spControlBackend.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val key = ControlBackend.entries[pos].key
                if (key != store.controlBackend) {
                    store.controlBackend = key
                    refreshControlStatus()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        b.spControlSteps.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, controlSteps.map { "$it 步" })
        b.spControlSteps.setSelection(maxOf(0, controlSteps.indexOf(store.controlMaxSteps)))
        b.spControlSteps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                store.controlMaxSteps = controlSteps[pos]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        b.spControlPace.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, paces.map { it.first })
        b.spControlPace.setSelection(
            maxOf(0, paces.indexOfFirst { it.second.first == store.controlSettleMs })
        )
        b.spControlPace.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                store.controlSettleMs = paces[pos].second.first
                store.controlThinkMs = paces[pos].second.second
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        b.swControlVision.isChecked = store.controlVision
        b.swControlVision.setOnCheckedChangeListener { _, checked -> store.controlVision = checked }

        b.swControlPayment.isChecked = store.controlBlockPayments
        b.swControlPayment.setOnCheckedChangeListener { _, checked ->
            store.controlBlockPayments = checked
            toast(if (checked) "支付类按钮将被拦截" else "已放开支付类操作，请自行把关")
        }

        b.btnOpenAccessibility.setOnClickListener {
            runCatching {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }.onFailure {
                toast("跳转失败，请到 系统设置 → 无障碍 → 已下载的服务 里找「灵犀AI」")
            }
            toast("在列表里选「灵犀AI」→ 打开开关并允许")
        }

        b.btnRequestShizuku.setOnClickListener {
            when {
                !ShizukuShell.running() ->
                    toast("Shizuku 没在运行：先用无线调试或 Root 把它启动起来")

                ShizukuShell.authorized() -> toast("Shizuku 已授权，把上方通道选成 Shizuku 即可")
                else -> {
                    ShizukuShell.requestAuth()
                    toast("已发起授权请求，请在 Shizuku 弹窗里点允许")
                }
            }
            refreshControlStatus()
        }

        b.btnControlTest.setOnClickListener {
            val controller = DeviceController(this, store)
            b.tvControlStatus.text = "读屏中…"
            lifecycleScope.launch {
                val screen = withContext(Dispatchers.IO) { controller.readScreen() }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("读屏结果（来源：${screen.source}）")
                    .setMessage(
                        screen.render().take(1500).ifBlank { "什么都没读到" } +
                            "\n\n说明：现在看到的就是本设置页自己的界面，能列出元素即代表通道可用。"
                    )
                    .setPositiveButton("好", null)
                    .show()
                refreshControlStatus()
            }
        }

        refreshControlStatus()
    }

    private fun refreshControlStatus() {
        val controller = DeviceController(this, store)
        val chain = controller.resolve()
        b.tvControlStatus.text = buildString {
            append(controller.statusText())
            append("\n实际会用：")
            append(if (chain.isEmpty()) "无（请先开启无障碍，或授予 Root / Shizuku）" else chain.joinToString(" → ") { it.label })
        }
    }

    private val controlSteps = listOf(6, 10, 14, 20, 30)

    private val paces = listOf(
        "稳一点（慢，适合动画多的应用）" to (800 to 1200),
        "标准" to (450 to 700),
        "快（简单界面够用）" to (250 to 350)
    )

    private fun modeHint(mode: String): String = if (mode == "back") {
        "后台模式：灵犀不读屏也不碰屏幕，只能拉起应用、打开链接或搜索、执行只读查询命令，你可以照常用手机。"
    } else {
        "前台接管：灵犀读屏并替你看清、点按、输入，期间请尽量不要碰屏幕。"
    }

    private fun refreshModels() {
        models = store.loadModels()
        val active = store.getActiveModel()
        b.rvModels.adapter = ModelAdapter(
            models, active?.id,
            onClick = { m -> editModel(m) },
            onLongClick = { m -> showModelActions(m) }
        )
    }

    private fun refreshRoles() {
        roles = store.loadRoles()
        val active = store.getActiveRole()
        b.rvRoles.adapter = RoleAdapter(
            roles, active.id,
            onClick = { r ->
                store.setActiveRole(r.id)
                refreshRoles()
                toast("当前角色：${r.name}")
            },
            onLongClick = { r ->
                if (r.builtin) {
                    editRole(r)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(r.name)
                        .setItems(arrayOf("编辑", "删除")) { _, which ->
                            if (which == 0) {
                                editRole(r)
                            } else {
                                roles.removeAll { it.id == r.id }
                                store.saveCustomRoles(roles)
                                refreshRoles()
                            }
                        }
                        .show()
                }
            }
        )
    }

    private fun editRole(existing: RolePreset?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_role_edit, null)
        val etName = view.findViewById<EditText>(R.id.etRoleName)
        val etPrompt = view.findViewById<EditText>(R.id.etRolePrompt)
        existing?.let {
            etName.setText(it.name)
            etPrompt.setText(it.prompt)
            if (it.builtin) {
                etName.isEnabled = false
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加角色" else "编辑角色")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val prompt = etPrompt.text.toString().trim()
                if (name.isBlank() || prompt.isBlank()) {
                    toast("名称和设定都不能为空")
                    return@setPositiveButton
                }
                if (existing == null) {
                    roles.add(RolePreset(name = name, prompt = prompt))
                } else {
                    existing.name = name
                    existing.prompt = prompt
                }
                store.saveCustomRoles(roles)
                refreshRoles()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showModelActions(m: ModelConfig) {
        val items = arrayOf("设为默认", "删除此配置")
        AlertDialog.Builder(this)
            .setTitle(m.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        store.setActiveModel(m.id)
                        refreshModels()
                        toast("已设为默认模型")
                    }
                    1 -> {
                        models.removeAll { it.id == m.id }
                        store.saveModels(models)
                        refreshModels()
                    }
                }
            }
            .show()
    }

    private fun pickFreeStt() {
        AlertDialog.Builder(this)
            .setTitle("选择免费语音服务")
            .setItems(arrayOf("硅基流动 SiliconFlow（免费额度，中文识别好）", "Groq（免费额度，速度快）")) { _, which ->
                val p = freeSttPresets[which]
                editModel(null, p)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editModel(existing: ModelConfig?, prefill: ModelConfig? = null) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_model_edit, null)
        val spPreset = view.findViewById<Spinner>(R.id.spPreset)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etBaseUrl = view.findViewById<EditText>(R.id.etBaseUrl)
        val etApiKey = view.findViewById<EditText>(R.id.etApiKey)
        val etModel = view.findViewById<EditText>(R.id.etModel)
        val etSttModel = view.findViewById<EditText>(R.id.etSttModel)
        val cbVision = view.findViewById<CheckBox>(R.id.cbVision)

        spPreset.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            presets.map { it.name }
        )
        spPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val p = presets[pos]
                if (p.baseUrl.isNotBlank()) {
                    etBaseUrl.setText(p.baseUrl)
                    etModel.setText(p.model)
                    cbVision.isChecked = p.vision
                    if (etName.text.isBlank()) etName.setText(p.name)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        (existing ?: prefill)?.let {
            etName.setText(it.name)
            etBaseUrl.setText(it.baseUrl)
            etApiKey.setText(it.apiKey)
            etModel.setText(it.model)
            cbVision.isChecked = it.vision
            etSttModel.setText(it.sttModel)
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加模型配置" else "编辑模型配置")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val baseUrl = etBaseUrl.text.toString().trim().trimEnd('/')
                val key = etApiKey.text.toString().trim()
                val model = etModel.text.toString().trim()
                if (name.isBlank() || baseUrl.isBlank() || model.isBlank()) {
                    toast("名称、API 地址、模型名都不能为空")
                    return@setPositiveButton
                }
                if (existing == null) {
                    val cfg = ModelConfig(
                        name = name,
                        baseUrl = baseUrl,
                        apiKey = key,
                        model = model,
                        vision = cbVision.isChecked,
                        sttModel = etSttModel.text.toString().trim()
                    )
                    models.add(cfg)
                    store.saveModels(models)
                    if (models.size == 1) store.setActiveModel(cfg.id)
                } else {
                    existing.name = name
                    existing.baseUrl = baseUrl
                    existing.apiKey = key
                    existing.model = model
                    existing.vision = cbVision.isChecked
                    existing.sttModel = etSttModel.text.toString().trim()
                    store.saveModels(models)
                }
                refreshModels()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------- 会话备份 / 恢复 ----------------

    private val sessionStore by lazy { SessionStore(this) }

    private val createBackupDoc = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = sessionStore.exportAll()
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val n = sessionStore.count()
            toast("已备份 $n 个会话")
        } catch (e: Exception) {
            toast("备份失败：${e.message}")
        }
    }

    private val openBackupDoc = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val raw = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@registerForActivityResult toast("读取文件失败")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("恢复会话")
                .setMessage("选择恢复方式：\n\n· 合并：保留现有会话，同 id 的被覆盖（推荐）\n· 覆盖：先清空本机全部会话再导入")
                .setPositiveButton("合并") { _, _ -> doRestore(raw, true) }
                .setNeutralButton("覆盖") { _, _ -> doRestore(raw, false) }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            toast("读取失败：${e.message}")
        }
    }

    private fun doRestore(raw: String, merge: Boolean) {
        try {
            val n = sessionStore.importAll(raw, merge)
            refreshBackupInfo()
            toast("已恢复 $n 个会话")
        } catch (e: Exception) {
            toast("恢复失败：文件格式不正确")
        }
    }

    private fun refreshBackupInfo() {
        val n = sessionStore.count()
        val latest = sessionStore.list().firstOrNull()
        b.tvSessionBackupInfo.text = if (n == 0) "当前本机共 0 个会话" else {
            "当前本机共 $n 个会话" + (latest?.let { "，最近更新 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it.updatedAt))}" } ?: "")
        }
    }

    data class Preset(val name: String, val baseUrl: String, val model: String, val vision: Boolean)
}
