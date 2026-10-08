package com.lingxi.chat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.TextView
import java.util.Locale

/**
 * 主页「+」面板里的下载动画预览：不联网、不下任何东西。
 * 先让用户从全部样式里点一款，播完一轮 0→100 后自动回到选择页继续挑。
 */
object ProgressPreview {

    fun show(activity: Activity) = openSelector(activity)

    /** 选择页：列出所有款式，点一款就去播，点「随机」抽一款 */
    private fun openSelector(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val names = UpdateUi.progressStyleNames.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("选一款下载动画")
            .setMessage("共 ${names.size} 款。点一款就播一轮，播完自动回到这里；真实下载时是从这些里随机挑一款。")
            .setItems(names) { _, which -> playStyle(activity, which) }
            .setNeutralButton("随机抽一款", { _, _ -> playStyle(activity, names.indices.random()) })
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 播放页：把选中的那一款从 0 跑到 100，满格停一下就关掉并回选择页 */
    private fun playStyle(activity: Activity, index: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_progress_preview, null)
        val percent = view.findViewById<TextView>(R.id.tvPvPercent)
        val speed = view.findViewById<TextView>(R.id.tvPvSpeed)
        val status = view.findViewById<TextView>(R.id.tvPvStatus)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton("重播一次", null)
            .setPositiveButton("返回选择", null)
            .create()

        val handler = Handler(Looper.getMainLooper())
        var style = UpdateUi.ProgressStyle("", {})
        var pct = 0
        var step = 3
        // 只有「跑完一轮」才自动弹回选择页；中途按返回键就是直接退出预览
        var autoReturn = false

        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                pct = (pct + step).coerceAtMost(100)
                style.set(pct)
                percent.text = "$pct%"
                speed.text = "${(320..3800).random()} KB/s"
                val mb = "%.1f".format(Locale.US, 2.6 * pct / 100.0)
                status.text = if (pct >= 100) {
                    "${style.name} · 下载完成，正在校验…"
                } else {
                    "${style.name} · Gitee 直连 · $mb/2.6 MB"
                }
                if (pct >= 100) {
                    autoReturn = true
                    handler.postDelayed({
                        if (dialog.isShowing) dialog.dismiss()
                    }, 1100L)
                } else {
                    step = (2..6).random()
                    handler.postDelayed(this, 120L)
                }
            }
        }

        fun restart() {
            handler.removeCallbacks(tick)
            autoReturn = false
            pct = 0
            style = UpdateUi.applyProgressStyle(view, index)
            percent.text = "0%"
            speed.text = "0 KB/s"
            status.text = "开始播放：${style.name}"
            handler.postDelayed(tick, 260L)
        }

        dialog.setOnShowListener {
            restart()
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                restart()
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                openSelector(activity)
            }
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(tick)
            if (autoReturn) openSelector(activity)
        }
        dialog.show()
    }
}
