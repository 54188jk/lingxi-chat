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
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.data.ModelConfig
import com.lingxi.chat.data.RolePreset
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

    private val searchProviders = listOf(
        "tavily" to "Tavily（推荐，每月 1000 次免费）",
        "bocha" to "博查（国内直连）",
        "serper" to "Serper（Google 结果）"
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

        val spAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            searchProviders.map { it.second }
        )
        b.spSearchProvider.adapter = spAdapter
        val curIdx = searchProviders.indexOfFirst { it.first == store.searchProvider }
        if (curIdx >= 0) b.spSearchProvider.setSelection(curIdx)
        b.etSearchKey.setText(store.searchKey)
        b.btnSaveSearch.setOnClickListener {
            store.searchProvider = searchProviders[b.spSearchProvider.selectedItemPosition].first
            store.searchKey = b.etSearchKey.text.toString().trim()
            toast("搜索设置已保存")
        }

        refreshModels()
        refreshRoles()

        b.tvVersion.text = "当前版本 v${BuildConfig.VERSION_NAME}"
        b.btnCheckUpdate.setOnClickListener {
            UpdateUi.check(this, BuildConfig.VERSION_NAME, silent = false)
        }
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

    private fun editModel(existing: ModelConfig?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_model_edit, null)
        val spPreset = view.findViewById<Spinner>(R.id.spPreset)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etBaseUrl = view.findViewById<EditText>(R.id.etBaseUrl)
        val etApiKey = view.findViewById<EditText>(R.id.etApiKey)
        val etModel = view.findViewById<EditText>(R.id.etModel)
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

        existing?.let {
            etName.setText(it.name)
            etBaseUrl.setText(it.baseUrl)
            etApiKey.setText(it.apiKey)
            etModel.setText(it.model)
            cbVision.isChecked = it.vision
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
                        vision = cbVision.isChecked
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
                    store.saveModels(models)
                }
                refreshModels()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    data class Preset(val name: String, val baseUrl: String, val model: String, val vision: Boolean)
}
