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
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.data.ModelConfig
import com.lingxi.chat.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var store: ConfigStore
    private var models = mutableListOf<ModelConfig>()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = ConfigStore(this)
        b.btnBack.setOnClickListener { finish() }
        b.rvModels.layoutManager = LinearLayoutManager(this)
        b.btnAddModel.setOnClickListener { editModel(null) }

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
