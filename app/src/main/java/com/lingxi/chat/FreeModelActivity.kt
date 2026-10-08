package com.lingxi.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.data.ModelConfig
import com.lingxi.chat.databinding.ActivityFreeModelBinding

/**
 * 免费模型接入页。
 *
 * 任务顺序就三步：拿网址注册 → 拿到 sk- 开头的 Key → 回设置填三项。
 * 本页负责第一步和第三步的准备工作：复制并打开官网、把服务地址和模型名先填成一条配置，
 * 用户只剩「粘贴 Key」一件事要做。
 */
class FreeModelActivity : BaseActivity() {

    private lateinit var b: ActivityFreeModelBinding
    private lateinit var store: ConfigStore

    /** 用户是否已经走过「复制并打开」这一步，决定设置页要不要弹粘贴框 */
    private var siteCopied = false

    companion object {
        private const val SITE = "https://vsllm.cc"
        private const val BASE_URL = "https://vsllm.cc/v1"
        private const val MODEL = "auto"
        private const val CONFIG_NAME = "vsllm 免费模型"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFreeModelBinding.inflate(layoutInflater)
        setContentView(b.root)
        store = ConfigStore(this)

        b.btnBack.setOnClickListener { finish() }
        b.btnCopyAndOpen.setOnClickListener { copyAndOpenSite() }
        b.rowBaseUrl.setOnClickListener { copy(BASE_URL, "服务地址已复制") }
        b.rowModelName.setOnClickListener { copy(MODEL, "模型名称已复制") }
        b.tvBaseUrl.setOnClickListener { copy(BASE_URL, "服务地址已复制") }
        b.tvModelName.setOnClickListener { copy(MODEL, "模型名称已复制") }
        b.btnGoSettings.setOnClickListener { openSettings() }
        b.btnApplyPreset.setOnClickListener { applyPreset() }
        refreshPresetButton()
    }

    /** 主按钮：地址进剪贴板，同时把官网拉起来，省得用户自己抄网址 */
    private fun copyAndOpenSite() {
        siteCopied = true
        copy(SITE, "官网地址已复制")
        val opened = try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE)))
            true
        } catch (e: Exception) {
            false
        }
        b.tvSiteState.text = if (opened)
            "已复制 $SITE 并打开浏览器。请在网页上注册账号，注册完回来。"
        else
            "地址已复制到剪贴板（$SITE），但没找到能打开网页的浏览器，可以粘贴到浏览器地址栏。"
        refreshPresetButton()
    }

    private fun copy(text: String, tip: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("lingxi", text))
        toast(tip)
    }

    /**
     * 已经把这套地址存进配置时，按钮就该只做剩下那一件事：
     * 提醒去粘贴 Key，而不是重复添加一条一样的配置。
     */
    private fun refreshPresetButton() {
        val has = store.loadModels().any { it.baseUrl.contains("vsllm.cc") }
        b.btnApplyPreset.text = if (has) "已填过，去粘贴 Key" else "先把地址存进设置"
    }

    private fun applyPreset() {
        val models = store.loadModels()
        val existing = models.firstOrNull { it.baseUrl.contains("vsllm.cc") }
        if (existing != null) {
            store.setActiveModel(existing.id)
            siteCopied = true
            openSettings()
            return
        }
        models.add(
            ModelConfig(
                name = CONFIG_NAME,
                baseUrl = BASE_URL,
                apiKey = "",
                model = MODEL,
                vision = false
            )
        )
        store.saveModels(models)
        store.setActiveModel(models.last().id)
        siteCopied = true
        toast("已存好地址和模型名，只剩粘贴 Key")
        openSettings()
    }

    /** 带着「来粘 Key」的意图过去，设置页会直接把那条配置编辑框弹出来 */
    private fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra("paste_key_for", if (siteCopied) "vsllm" else "")
        )
    }
}
