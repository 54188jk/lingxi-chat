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

    // ---------------- 手机操控（无障碍 / Root / Shizuku）----------------

    /** 操控通道：auto / a11y / root / shizuku */
    var controlBackend: String
        get() = sp.getString(KEY_CTRL_BACKEND, "auto") ?: "auto"
        set(v) = sp.edit().putString(KEY_CTRL_BACKEND, v).apply()

    /** 操控方式：front 接管屏幕点按 / back 不占屏只发指令 */
    var controlMode: String
        get() = sp.getString(KEY_CTRL_MODE, "front") ?: "front"
        set(v) = sp.edit().putString(KEY_CTRL_MODE, v).apply()

    /** 单个任务最多走多少步，防止模型无限点下去 */
    var controlMaxSteps: Int
        get() = sp.getInt(KEY_CTRL_STEPS, 14)
        set(v) = sp.edit().putInt(KEY_CTRL_STEPS, v.coerceIn(3, 40)).apply()

    /** 动作落地后等界面稳定的时间 */
    var controlSettleMs: Int
        get() = sp.getInt(KEY_CTRL_SETTLE, 450)
        set(v) = sp.edit().putInt(KEY_CTRL_SETTLE, v.coerceIn(150, 4000)).apply()

    /** 两次动作之间的间隔 */
    var controlThinkMs: Int
        get() = sp.getInt(KEY_CTRL_THINK, 700)
        set(v) = sp.edit().putInt(KEY_CTRL_THINK, v.coerceIn(200, 6000)).apply()

    /** 视觉模型是否回传截图 */
    var controlVision: Boolean
        get() = sp.getBoolean(KEY_CTRL_VISION, true)
        set(v) = sp.edit().putBoolean(KEY_CTRL_VISION, v).apply()

    /** 保留最近几屏上下文 */
    var controlMemory: Int
        get() = sp.getInt(KEY_CTRL_MEMORY, 4)
        set(v) = sp.edit().putInt(KEY_CTRL_MEMORY, v.coerceIn(1, 10)).apply()

    /** 禁止点按支付/转账类按钮 */
    var controlBlockPayments: Boolean
        get() = sp.getBoolean(KEY_CTRL_PAY, true)
        set(v) = sp.edit().putBoolean(KEY_CTRL_PAY, v).apply()

    /** 聊天页的「操控」开关状态 */
    var controlEnabled: Boolean
        get() = sp.getBoolean(KEY_CTRL_ON, false)
        set(v) = sp.edit().putBoolean(KEY_CTRL_ON, v).apply()

    /** 允许 AI 整理文件和文件夹（列目录、建文件夹、挪动、复制、删进回收站） */
    var controlFileOps: Boolean
        get() = sp.getBoolean(KEY_CTRL_FS, true)
        set(v) = sp.edit().putBoolean(KEY_CTRL_FS, v).apply()

    /** 桌面「最近」键要显示的页面，最新的排前面，只留四个 */
    var deskRecents: List<String>
        get() = (sp.getString(KEY_DESK_RECENTS, "") ?: "").split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString(KEY_DESK_RECENTS, v.take(4).joinToString(",")).apply()

    /** 手机桌面的壁纸：内置款的名字，或 custom（用户自己选的一张图） */
    var wallpaperKey: String
        get() = sp.getString(KEY_WALLPAPER, "crimson") ?: "crimson"
        set(v) = sp.edit().putString(KEY_WALLPAPER, v).apply()

    /** 应用商店里用户自己添加的应用，JSON 数组 [{"name":..,"url":..,"size":..}] */
    var storeApps: String
        get() = sp.getString(KEY_STORE_APPS, "[]") ?: "[]"
        set(v) = sp.edit().putString(KEY_STORE_APPS, v).apply()

    /** 文件页的排序方式：0 名字 / 1 时间 / 2 大小 */
    var fileSort: Int
        get() = sp.getInt(KEY_FILE_SORT, 0)
        set(v) = sp.edit().putInt(KEY_FILE_SORT, v.coerceIn(0, 2)).apply()

    // ---------------- 历史安装包归档 ----------------

    /** 归档位置：ask 还没问过用户 / root 内部储存/历史记录 / download 内部储存/下载/历史记录 */
    var archiveLocation: String
        get() = sp.getString(KEY_ARCHIVE_LOC, "ask") ?: "ask"
        set(v) = sp.edit().putString(KEY_ARCHIVE_LOC, v).apply()

    /** 已下载但还没安装的版本，聊天页启动时据此提醒一次 */
    var pendingInstallVersion: String
        get() = sp.getString(KEY_ARCHIVE_PENDING, "") ?: ""
        set(v) = sp.edit().putString(KEY_ARCHIVE_PENDING, v).apply()

    /** 这个版本的「还没安装」提醒已经弹过一次，不再重复唠叨 */
    var installReminderFor: String
        get() = sp.getString(KEY_INSTALL_REMINDED, "") ?: ""
        set(v) = sp.edit().putString(KEY_INSTALL_REMINDED, v).apply()

    /** 刚在设置页恢复过备份：聊天页要按最新落盘内容重载当前会话，否则内存里的旧会话会盖掉导入结果 */
    var sessionsReloadPending: Boolean
        get() = sp.getBoolean(KEY_SESSIONS_RELOAD, false)
        set(v) = sp.edit().putBoolean(KEY_SESSIONS_RELOAD, v).apply()

    /** 最近一次归档成功的路径，设置页要显示 */
    var lastArchivePath: String
        get() = sp.getString(KEY_ARCHIVE_PATH, "") ?: ""
        set(v) = sp.edit().putString(KEY_ARCHIVE_PATH, v).apply()

    fun loadRoles(): MutableList<RolePreset> {        val customs = mutableListOf<RolePreset>()
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
        private const val KEY_SEARCH_ON = "search_on"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_ROLES = "roles"
        private const val KEY_ACTIVE_ROLE = "active_role"
        private const val KEY_ENTER_SEND = "enter_to_send"
        private const val KEY_DRAFT = "input_draft"
        private const val KEY_CTRL_BACKEND = "ctrl_backend"
        private const val KEY_CTRL_MODE = "ctrl_mode"
        private const val KEY_CTRL_STEPS = "ctrl_max_steps"
        private const val KEY_CTRL_SETTLE = "ctrl_settle_ms"
        private const val KEY_CTRL_THINK = "ctrl_think_ms"
        private const val KEY_CTRL_VISION = "ctrl_vision"
        private const val KEY_CTRL_MEMORY = "ctrl_memory"
        private const val KEY_CTRL_PAY = "ctrl_block_pay"
        private const val KEY_CTRL_ON = "ctrl_enabled"
        private const val KEY_CTRL_FS = "ctrl_file_ops"
        private const val KEY_FILE_SORT = "file_sort"
        private const val KEY_DESK_RECENTS = "desk_recents"
        private const val KEY_WALLPAPER = "desk_wallpaper"
        private const val KEY_STORE_APPS = "store_apps"
        private const val KEY_ARCHIVE_LOC = "archive_location"
        private const val KEY_ARCHIVE_PATH = "archive_last_path"
        private const val KEY_ARCHIVE_PENDING = "archive_pending_version"
        private const val KEY_SESSIONS_RELOAD = "sessions_reload_pending"
        private const val KEY_INSTALL_REMINDED = "install_reminder_for"
    }
}
