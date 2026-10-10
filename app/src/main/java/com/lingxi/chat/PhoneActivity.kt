package com.lingxi.chat

import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.data.DevicePerf
import com.lingxi.chat.databinding.ActivityPhoneBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 打开应用先是一台手机的桌面：整屏就是屏幕，状态栏、时钟组件、Dock、三键都在该在的位置。
 * 桌面只摆一个「糯叽」，其余页面收进上滑抽屉；返回 / Home / 最近三个键按真机手感工作。
 */
class PhoneActivity : BaseActivity() {

    private lateinit var b: ActivityPhoneBinding
    private val store by lazy { ConfigStore(this) }

    /** key：动作标识；label/icon 画格子；kind：chat/new/类名/update/anim */
    private data class Entry(val key: String, val label: String, val icon: Int, val kind: String)

    private val entries = listOf(
        Entry("chat", "糯叽", R.mipmap.ic_launcher, "chat"),
        Entry("newchat", "新会话", R.drawable.ic_add_circle, "new"),
        Entry("sessions", "历史会话", R.drawable.ic_history, "sessions"),
        Entry("settings", "设置", R.drawable.ic_settings, "settings"),
        Entry("files", "文件与文件夹", R.drawable.ic_folder, "files"),
        Entry("control", "操控台", R.drawable.ic_control, "control"),
        Entry("free", "免费模型", R.drawable.ic_sparkle, "free"),
        Entry("update", "检查更新", R.drawable.ic_update, "update"),
        Entry("anim", "下载动画", R.drawable.ic_emoji, "anim"),
        Entry("store", "应用商店", R.drawable.ic_search, "store"),
        Entry("wallpaper", "换壁纸", R.drawable.ic_image, "wallpaper")
    )

    /** 内置壁纸：key → 名字与素材，顺序即选择列表顺序 */
    private val walls = listOf(
        "crimson" to "玫红夜色",
        "ink" to "墨黑",
        "moss" to "苔绿",
        "sand" to "暖沙",
        "rose" to "灰玫",
        "charcoal" to "石墨"
    )

    private val pickWall = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = runCatching {
            val dir = java.io.File(filesDir, "wallpaper").apply { if (!exists()) mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = java.io.File(dir, "custom.img")
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: error("读不到这张图")
            true
        }.getOrDefault(false)
        if (!ok) {
            toast("这张图用不了，换一张试试")
        } else {
            store.wallpaperKey = "custom"
            applyWallpaper()
            toast("壁纸换好了")
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            renderClock()
            b.tvBigClock.postDelayed(this, 15_000L)
        }
    }

    private var density = 1f
    private var recentsOpen = false

    /** 小组件那行末尾的电量文字，读不到就留空 */
    private var battery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPhoneBinding.inflate(layoutInflater)
        setContentView(b.root)
        density = resources.displayMetrics.density

        buildDeskGrid()
        b.appNuoji.setOnClickListener { launch("chat", it) }
        b.dockHistory.setOnClickListener { launch("sessions", it) }
        b.dockSettings.setOnClickListener { launch("settings", it) }
        b.dockFiles.setOnClickListener { launch("files", it) }
        b.dockControl.setOnClickListener { launch("control", it) }
        b.cardDeskWidget.setOnClickListener { launch("settings", it) }

        applyWallpaper()
        b.deskRoot.setOnLongClickListener { askWallpaper(); true }
        b.btnNavBack.setOnClickListener {
            if (recentsOpen) openRecents(false) else moveTaskToBack(true)
        }
        b.btnNavHome.setOnClickListener {
            openRecents(false)
            bounce(it)
            renderStatus()
        }
        b.btnNavRecent.setOnClickListener { openRecents(!recentsOpen) }

        b.panelRecents.setOnClickListener { openRecents(false) }
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        renderStatus()
        b.tvBigClock.post(ticker)
        if (!enteredOnce) {
            enteredOnce = true
            playEntry()
        }
    }

    override fun onPause() {
        if (::b.isInitialized) b.tvBigClock.removeCallbacks(ticker)
        super.onPause()
    }

    private var enteredOnce = false

    /** 图标一次依次浮起；轻量档或系统关掉动画就直接给到位 */
    private fun playEntry() {
        val views = listOf(b.cardDeskWidget, b.appNuoji, b.llDeskGrid, b.dock, b.navBar)
        if (!animationsEnabled() || DevicePerf.lowEnd(this)) {
            views.forEach { it.alpha = 1f; it.translationY = 0f }
            return
        }
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 26f * density
            v.animate().alpha(1f).translationY(0f).setStartDelay(70L * i).setDuration(320L).start()
        }
    }

    /** 桌面网格：除「糯叽」大图标外的全部入口，四个一行，摆成像真桌面那样 */
    private fun buildDeskGrid() {
        b.llDeskGrid.removeAllViews()
        val items = entries.filter { it.key != "chat" }
        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowLp.topMargin = (6 * density).toInt()
        items.chunked(4).forEach { row ->
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.layoutParams = rowLp
            row.forEach { entry ->
                line.addView(deskTile(entry), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            repeat(4 - row.size) {
                line.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            b.llDeskGrid.addView(line)
        }
    }

    private fun deskTile(entry: Entry): View {
        val tile = LinearLayout(this)
        tile.orientation = LinearLayout.VERTICAL
        tile.gravity = Gravity.CENTER_HORIZONTAL
        tile.background = ContextCompat.getDrawable(this, R.drawable.bg_nav_key)

        val plate = android.widget.FrameLayout(this)
        plate.layoutParams = android.widget.FrameLayout.LayoutParams(
            (50 * density).toInt(), (50 * density).toInt(), Gravity.CENTER)
        plate.background = ContextCompat.getDrawable(this, R.drawable.bg_desk_tile)
        val icon = ImageView(this)
        icon.contentDescription = entry.label
        icon.setImageResource(entry.icon)
        icon.setColorFilter(ContextCompat.getColor(this, R.color.desk_fg))
        plate.addView(icon, android.widget.FrameLayout.LayoutParams(
            (22 * density).toInt(), (22 * density).toInt(), Gravity.CENTER))
        tile.addView(plate)

        val label = TextView(this)
        label.text = entry.label
        label.setTextColor(ContextCompat.getColor(this, R.color.desk_fg))
        label.textSize = 10f
        label.isSingleLine = true
        label.gravity = Gravity.CENTER
        label.setPadding(0, (4 * density).toInt(), 0, 0)
        tile.addView(label)

        tile.setOnClickListener { launch(entry.key, plate) }
        tile.setOnLongClickListener {
            if (entry.key == "wallpaper") { askWallpaper(); true } else false
        }
        return tile
    }

    private fun launch(key: String, v: View?) {
        store.deskRecents = listOf(key) + store.deskRecents.filter { it != key }
        pressThen(v) {
            when (key) {
                "chat" -> go(MainActivity::class.java, key)
                "new" -> go(MainActivity::class.java, key, newSession = true)
                "sessions" -> go(SessionsActivity::class.java, key)
                "settings" -> go(SettingsActivity::class.java, key)
                "files" -> go(FilesActivity::class.java, key)
                "control" -> go(ControlActivity::class.java, key)
                "free" -> go(FreeModelActivity::class.java, key)
                "update" -> UpdateUi.check(this, BuildConfig.VERSION_NAME, silent = false)
                "anim" -> ProgressPreview.show(this)
                "store" -> go(StoreActivity::class.java, key)
                "wallpaper" -> askWallpaper()
            }
        }
    }

    private fun go(target: Class<*>, key: String, newSession: Boolean = false) {
        val i = Intent(this, target)
        if (newSession) i.putExtra("new_session", true)
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        openRecents(false)
        startActivity(i)
        if (animationsEnabled()) overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** 按下先缩一下再跳转，像真机点图标 */
    private fun pressThen(v: View?, action: () -> Unit) {
        if (v == null || !animationsEnabled() || DevicePerf.lowEnd(this)) {
            action(); return
        }
        v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
            action()
        }.start()
    }

    private fun openRecents(open: Boolean) {
        if (recentsOpen == open) return
        recentsOpen = open
        val panel = b.panelRecents
        if (open) {
            buildRecents()
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            panel.animate().alpha(1f).setDuration(if (animationsEnabled()) 200 else 0).start()
        } else {
            panel.animate().alpha(0f).setDuration(if (animationsEnabled()) 160 else 0)
                .withEndAction { panel.visibility = View.GONE }.start()
        }
    }

    private fun buildRecents() {
        b.llRecents.removeAllViews()
        val keys = store.deskRecents.ifEmpty { listOf("chat") }
        // 多任务里只留真正能回去的页面
        val backable = setOf("chat", "newchat", "sessions", "settings", "files", "control", "free", "store")
        keys.filter { it in backable }.forEach { key ->
            val entry = entries.firstOrNull { it.key == key } ?: return@forEach
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(context, R.drawable.bg_nav_key)
                val h = (10 * density).toInt()
                setPadding((14 * density).toInt(), h, (14 * density).toInt(), h)
            }
            val icon = ImageView(this).apply {
                setImageResource(entry.icon)
                if (entry.key != "chat") setColorFilter(ContextCompat.getColor(context, R.color.desk_fg))
            }
            row.addView(icon, LinearLayout.LayoutParams((26 * density).toInt(), (26 * density).toInt()))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val colLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            colLp.marginStart = (12 * density).toInt()
            col.layoutParams = colLp
            val title = TextView(this)
            title.text = entry.label
            title.setTextColor(ContextCompat.getColor(this, R.color.desk_fg))
            title.textSize = 15f
            val sub = TextView(this)
            sub.text = "继续刚才的地方"
            sub.setTextColor(ContextCompat.getColor(this, R.color.desk_fg_dim))
            sub.textSize = 11f
            col.addView(title)
            col.addView(sub)
            row.addView(col)
            row.setOnClickListener { launch(entry.key, it) }
            b.llRecents.addView(row)
        }
    }

    private fun renderClock() {
        val now = Date()
        val hm = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        b.tvBigClock.text = hm
        b.tvDeskDate.text = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now) + battery
    }

    private fun renderStatus() {
        renderClock()
        val sticky = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val state = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = state == BatteryManager.BATTERY_STATUS_CHARGING ||
                state == BatteryManager.BATTERY_STATUS_FULL
        battery = if (level < 0 || scale <= 0) {
            ""
        } else {
            val pct = level * 100 / scale
            " · 电量 $pct%" + if (charging) "（充电中）" else ""
        }
        val model = store.getActiveModel()
        b.tvWidgetNote.text = if (model == null) {
            "还没有能聊的模型 · 点这块卡片去设置"
        } else {
            "当前模型：" + model.name + " · 点这块卡片可换"
        }
    }

    /** 按配置把壁纸铺上；自定义图按屏幕尺寸采样，别把整张原图吃进内存 */
    private fun applyWallpaper() {
        val key = store.wallpaperKey
        if (key == "custom") {
            val f = java.io.File(filesDir, "wallpaper/custom.img")
            if (f.exists()) {
                val dm = resources.displayMetrics
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) > dm.widthPixels * 2) sample *= 2
                val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                if (bmp != null) {
                    b.deskWall.setImageBitmap(bmp)
                    return
                }
            }
        }
        b.deskWall.setImageResource(wallRes(key))
    }

    private fun wallRes(key: String): Int = when (key) {
        "ink" -> R.drawable.bg_wall_ink
        "moss" -> R.drawable.bg_wall_moss
        "sand" -> R.drawable.bg_wall_sand
        "rose" -> R.drawable.bg_wall_rose
        "charcoal" -> R.drawable.bg_wall_charcoal
        else -> R.drawable.bg_wall_crimson
    }

    private fun askWallpaper() {
        val names = walls.map { it.second } + listOf("从相册选一张", "恢复默认（玫红夜色）")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("换一张壁纸")
            .setItems(names.toTypedArray()) { _, which ->
                when {
                    which == walls.size -> pickWall.launch("image/*")
                    which == walls.size + 1 -> {
                        store.wallpaperKey = "crimson"
                        applyWallpaper()
                    }
                    else -> {
                        store.wallpaperKey = walls[which].first
                        applyWallpaper()
                    }
                }
            }
            .setNegativeButton("不用改", null)
            .show()
    }

    private fun bounce(v: View) {
        if (!animationsEnabled()) return
        v.animate().scaleY(0.8f).setDuration(90).withEndAction {
            v.animate().scaleY(1f).setDuration(140).start()
        }.start()
    }
}
