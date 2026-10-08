package com.lingxi.chat

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.lingxi.chat.data.ConfigStore

abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val scale = ConfigStore(newBase).fontScale
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = scale
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        when (ConfigStore(this).themeMode) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "system" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
        super.onCreate(savedInstanceState)
    }

    /** 各页面都要提示用户，统一一条轻提示，别让 Toast 到处重复写 */
    protected fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** 系统关闭动画时（开发者选项 / 无障碍）跳过所有动效 */
    protected fun animationsEnabled(): Boolean =
        android.provider.Settings.Global.getFloat(
            contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) > 0.01f


    /** 一行输入框的弹窗：名字、说明、确认后回调 */
    protected fun askText(title: String, note: String, inputHint: String, onOk: (String) -> Unit) {
        val density = resources.displayMetrics.density
        val box = android.widget.FrameLayout(this)
        val input = android.widget.EditText(this).apply {
            setHint(inputHint)
            textSize = 13f
            setPadding(
                (12 * density).toInt(), (10 * density).toInt(),
                (12 * density).toInt(), (10 * density).toInt()
            )
            setBackgroundResource(R.drawable.bg_input_bar)
        }
        val lp = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
        box.addView(input, lp)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .apply { if (note.isNotBlank()) setMessage(note) }
            .setView(box)
            .setPositiveButton("好") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    protected fun animateForward() {
        if (!animationsEnabled()) return
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_left)
    }
}
