package com.lingxi.chat

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 主页「+」面板里的下载动画预览：不联网、不下任何东西。
 * 选择页把每一款都现场画出来同时跑，看中哪款点哪款放大播一轮，播完自动回到选择页。
 */
object ProgressPreview {

    fun show(activity: Activity) = openSelector(activity)

    /** 选择页：左列名字，右列就是那一款真实跑起来的样子 */
    private fun openSelector(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_progress_choose, null)
        val rows = view.findViewById<LinearLayout>(R.id.llRows)
        view.findViewById<TextView>(R.id.tvChooseHint).text =
            "下面 ${ProgressStyleCatalog.entries.size} 行，每行右边都是那一款真实跑起来的样子。" +
                    "点哪一行就放大播一轮，播完自动回到这里；真实下载时是从这些里随机挑一款。"

        val styles = ArrayList<ProgressStyleCatalog.Style>()
        var chooser: Dialog? = null
        ProgressStyleCatalog.entries.forEachIndexed { idx, entry ->
            val style = entry.create(activity)
            val row = activity.layoutInflater
                .inflate(R.layout.item_progress_style, rows, false) as LinearLayout
            row.findViewById<TextView>(R.id.tvStyleName).text = entry.name
            val slot = row.findViewById<android.widget.FrameLayout>(R.id.flStyleSlot)
            slot.addView(
                style.view,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            row.setOnClickListener {
                chooser?.dismiss()
                playStyle(activity, idx)
            }
            rows.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * activity.resources.displayMetrics.density).toInt() }
            )
            styles.add(style)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()
        chooser = dialog

        // 各行的起点与步长都错开，看着像一批任务在并行下载，而不是同一条动画的复读
        val handler = Handler(Looper.getMainLooper())
        // 起点错开，进来第一眼就是十款各自在不同位置上跑
        val pct = IntArray(styles.size) { (it * 37) % 100 }
        val step = IntArray(styles.size) { (2..6).random() }
        val hold = BooleanArray(styles.size)
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                styles.forEachIndexed { i, s ->
                    if (hold[i]) return@forEachIndexed
                    pct[i] = (pct[i] + step[i]).coerceAtMost(100)
                    s.set(pct[i])
                    if (pct[i] >= 100) {
                        hold[i] = true
                        handler.postDelayed({
                            hold[i] = false
                            pct[i] = 0
                            step[i] = (2..6).random()
                            s.set(0)
                        }, 700L)
                    }
                }
                handler.postDelayed(this, 130L)
            }
        }
        dialog.setOnShowListener { handler.post(tick) }
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
        dialog.show()
    }

    /** 播放页：把选中的那一款从 0 跑到 100，满格停一下就关掉并回选择页 */
    private fun playStyle(activity: Activity, index: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_progress_preview, null)
        val percent = view.findViewById<TextView>(R.id.tvPvPercent)
        val speed = view.findViewById<TextView>(R.id.tvPvSpeed)
        val status = view.findViewById<TextView>(R.id.tvPvStatus)

        val dialog = AlertDialog.Builder(activity)
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
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { restart() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // 别让 dismiss 回调再开一次选择页
                autoReturn = false
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
