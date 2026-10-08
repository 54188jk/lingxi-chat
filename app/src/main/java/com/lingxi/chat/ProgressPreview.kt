package com.lingxi.chat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.TextView
import java.util.Locale

/**
 * 主页「+」面板里的下载动画预览：不联网、不下任何东西，
 * 只把更新下载会随机用到的那几款进度条跑一遍，满格后自动换一款接着演示。
 */
object ProgressPreview {

    fun show(activity: Activity) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_progress_preview, null)
        val percent = view.findViewById<TextView>(R.id.tvPvPercent)
        val speed = view.findViewById<TextView>(R.id.tvPvSpeed)
        val status = view.findViewById<TextView>(R.id.tvPvStatus)
        view.findViewById<TextView>(R.id.tvPvAll).text =
            "一共 ${UpdateUi.progressStyleNames.size} 款：" +
                    UpdateUi.progressStyleNames.joinToString(" · ") +
                    "\n真下载时每次点「立即更新 / 开始下载」随机挑一款，不喜欢就关掉重开一次。"

        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setView(view)
            .setNeutralButton("换一款", null)
            .setPositiveButton("关闭", null)
            .create()

        val handler = Handler(Looper.getMainLooper())
        var style = UpdateUi.ProgressStyle("", {})
        var pct = 0
        var done = false
        var step = 3

        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                if (done) {
                    done = false
                    pct = 0
                    step = (2..6).random()
                    style = UpdateUi.pickProgressView(view)
                    percent.text = "0%"
                    status.text = "换好了：${style.name}"
                    handler.postDelayed(this, 90L)
                    return
                }
                pct = (pct + step).coerceAtMost(100)
                style.set(pct)
                percent.text = "$pct%"
                speed.text = "${(320..3800).random()} KB/s"
                val mb = "%.1f".format(Locale.US, 2.6 * pct / 100.0)
                status.text = if (pct >= 100) {
                    done = true
                    "${style.name} · 下载完成，正在校验…"
                } else {
                    "${style.name} · Gitee 直连 · $mb/2.6 MB"
                }
                handler.postDelayed(this, if (pct >= 100) 1500L else 120L)
            }
        }

        dialog.setOnShowListener {
            style = UpdateUi.pickProgressView(view)
            status.text = "当前样式：${style.name}"
            handler.post(tick)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                done = false
                pct = 0
                step = (2..6).random()
                style = UpdateUi.pickProgressView(view)
                percent.text = "0%"
                speed.text = "0 KB/s"
                status.text = "换好了：${style.name}"
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
        dialog.show()
    }
}
