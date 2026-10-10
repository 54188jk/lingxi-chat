package com.lingxi.chat

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.View
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.databinding.ActivityPhoneBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 打开应用先落到一台「手机」的桌面：机身、状态栏、图标、三键都在，
 * 桌面里只摆糯叽自己的页面，点图标进去，按返回键回到桌面。
 */
class PhoneActivity : BaseActivity() {

    private lateinit var b: ActivityPhoneBinding
    private val store by lazy { ConfigStore(this) }

    private val ticker = object : Runnable {
        override fun run() {
            renderClock()
            b.tvDeskClock.postDelayed(this, 20_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!store.homeDesktop) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        b = ActivityPhoneBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.appChat.setOnClickListener { go(MainActivity::class.java) }
        b.appNewChat.setOnClickListener { go(MainActivity::class.java, newSession = true) }
        b.appSessions.setOnClickListener { go(SessionsActivity::class.java) }
        b.appSettings.setOnClickListener { go(SettingsActivity::class.java) }
        b.cardDeskWidget.setOnClickListener { go(SettingsActivity::class.java) }
        b.appFiles.setOnClickListener { go(FilesActivity::class.java) }
        b.appControl.setOnClickListener { go(ControlActivity::class.java) }
        b.appFreeModel.setOnClickListener { go(FreeModelActivity::class.java) }
        b.appUpdate.setOnClickListener { UpdateUi.check(this, BuildConfig.VERSION_NAME, silent = false) }

        // 三键按真机的习惯来：返回把应用整个退到后台，桌面键弹一下，最近键看会话列表
        b.btnNavBack.setOnClickListener { moveTaskToBack(true) }
        b.btnNavHome.setOnClickListener {
            bounce(it)
            renderStatus()
        }
        b.btnNavRecent.setOnClickListener { go(SessionsActivity::class.java) }
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        renderStatus()
        b.tvDeskClock.post(ticker)
    }

    override fun onPause() {
        if (::b.isInitialized) b.tvDeskClock.removeCallbacks(ticker)
        super.onPause()
    }

    private fun go(target: Class<*>, newSession: Boolean = false) {
        val i = Intent(this, target)
        if (newSession) i.putExtra("new_session", true)
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(i)
        if (animationsEnabled()) {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun renderClock() {
        val now = Date()
        b.tvDeskClock.text = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        b.tvDeskDate.text = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now)
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
        b.tvDeskBattery.text = if (level < 0 || scale <= 0) {
            "电量未知"
        } else {
            val pct = level * 100 / scale
            if (charging) "$pct% 充电中" else "$pct%"
        }

        val model = store.getActiveModel()
        if (model == null) {
            b.tvWidgetTitle.text = "还没有能聊的模型"
            b.tvWidgetNote.text = "点这块卡片去设置里配一个，或先用「免费模型」拿一个 Key"
        } else {
            b.tvWidgetTitle.text = "糯叽已经就绪"
            b.tvWidgetNote.text = "当前模型：" + model.name + "（点这块卡片可换）"
        }
    }

    private fun bounce(v: View) {
        if (!animationsEnabled()) return
        v.animate().scaleY(0.82f).setDuration(90).withEndAction {
            v.animate().scaleY(1f).setDuration(140).start()
        }.start()
    }
}
