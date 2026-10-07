package com.lingxi.chat.data

import android.content.Context
import org.json.JSONArray

class ConfigStore(context: Context) {

    private val sp = context.getSharedPreferences("lingxi_config", Context.MODE_PRIVATE)

    fun loadModels(): MutableList<ModelConfig> {
        val raw = sp.getString(KEY_MODELS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<ModelConfig>()
        for (i in 0 until arr.length()) {
            list.add(ModelConfig.fromJson(arr.getJSONObject(i)))
        }
        return list
    }

    fun saveModels(list: List<ModelConfig>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY_MODELS, arr.toString()).apply()
    }

    fun getActiveModel(): ModelConfig? {
        val list = loadModels()
        if (list.isEmpty()) return null
        val activeId = sp.getString(KEY_ACTIVE, null)
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    fun setActiveModel(id: String) {
        sp.edit().putString(KEY_ACTIVE, id).apply()
    }

    var searchProvider: String
        get() = sp.getString(KEY_SEARCH_PROVIDER, "tavily") ?: "tavily"
        set(v) = sp.edit().putString(KEY_SEARCH_PROVIDER, v).apply()

    var searchKey: String
        get() = sp.getString(KEY_SEARCH_KEY, "") ?: ""
        set(v) = sp.edit().putString(KEY_SEARCH_KEY, v).apply()

    var searchEnabled: Boolean
        get() = sp.getBoolean(KEY_SEARCH_ON, false)
        set(v) = sp.edit().putBoolean(KEY_SEARCH_ON, v).apply()

    var themeMode: String
        get() = sp.getString(KEY_THEME, "dark") ?: "dark"
        set(v) = sp.edit().putString(KEY_THEME, v).apply()

    var fontScale: Float
        get() = sp.getFloat(KEY_FONT_SCALE, 1.0f)
        set(v) = sp.edit().putFloat(KEY_FONT_SCALE, v).apply()

    /** 软键盘回车是发送还是换行，默认发送 */
    var enterToSend: Boolean
        get() = sp.getBoolean(KEY_ENTER_SEND, true)
        set(v) = sp.edit().putBoolean(KEY_ENTER_SEND, v).apply()

    /** 输入框草稿：切页面/退后台不丢，发送后清空 */
    var draft: String
        get() = sp.getString(KEY_DRAFT, "") ?: ""
        set(v) = sp.edit().putString(KEY_DRAFT, v).apply()

    fun loadRoles(): MutableList<RolePreset> {
        val customs = mutableListOf<RolePreset>()
        val raw = sp.getString(KEY_ROLES, "[]") ?: "[]"
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            customs.add(RolePreset.fromJson(arr.getJSONObject(i)))
        }
        return (RolePreset.builtins() + customs).toMutableList()
    }

    fun saveCustomRoles(all: List<RolePreset>) {
        val arr = JSONArray()
        all.filter { !it.builtin }.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY_ROLES, arr.toString()).apply()
    }

    fun getActiveRole(): RolePreset {
        val roles = loadRoles()
        val activeId = sp.getString(KEY_ACTIVE_ROLE, "general")
        return roles.firstOrNull { it.id == activeId } ?: roles.first()
    }

    fun setActiveRole(id: String) {
        sp.edit().putString(KEY_ACTIVE_ROLE, id).apply()
    }

    companion object {
        private const val KEY_MODELS = "models"
        private const val KEY_ACTIVE = "active_model"
        private const val KEY_SEARCH_PROVIDER = "search_provider"
        private const val KEY_SEARCH_KEY = "search_key"
        private const val KEY_SEARCH_ON = "search_on"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_ROLES = "roles"
        private const val KEY_ACTIVE_ROLE = "active_role"
        private const val KEY_ENTER_SEND = "enter_to_send"
        private const val KEY_DRAFT = "input_draft"
    }
}
