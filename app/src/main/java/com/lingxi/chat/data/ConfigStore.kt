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

    companion object {
        private const val KEY_MODELS = "models"
        private const val KEY_ACTIVE = "active_model"
        private const val KEY_SEARCH_PROVIDER = "search_provider"
        private const val KEY_SEARCH_KEY = "search_key"
        private const val KEY_SEARCH_ON = "search_on"
    }
}
