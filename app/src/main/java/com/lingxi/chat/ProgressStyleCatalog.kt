package com.lingxi.chat

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.content.ContextCompat

/**
 * 十款下载进度条的「名字 + 现场造一个出来」清单。
 * 选择页用它一次摆出 10 个会动的小样，不用先下载也能看清每款长什么样。
 * 顺序要和 [UpdateUi.applyProgressStyle] 里布局的序号一致。
 */
object ProgressStyleCatalog {

    class Entry(val name: String, val create: (Context) -> Style)

    /** 一个小样：视图本体 + 进度设置器 */
    class Style(val view: View, val set: (Int) -> Unit)

    private fun wrap(v: View, heightDp: Float, gravity: Int = Gravity.CENTER_VERTICAL): FrameLayout {
        val density = v.resources.displayMetrics.density
        val box = FrameLayout(v.context)
        box.addView(
            v,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (heightDp * density).toInt(),
                gravity
            )
        )
        return box
    }

    val entries = listOf(
        Entry("普通横条") { ctx ->
            val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                runCatching {
                    ContextCompat.getDrawable(ctx, R.drawable.progress_update)?.let { progressDrawable = it }
                }
            }
            Style(wrap(bar, 10f), { p -> bar.progress = p })
        },
        Entry("贪吃蛇") { ctx ->
            val v = SnakeProgressView(ctx)
            Style(wrap(v, 26f), { p -> v.progress = p })
        },
        Entry("像素方块") { ctx ->
            val v = BlocksProgressView(ctx)
            Style(wrap(v, 20f), { p -> v.progress = p })
        },
        Entry("液体波动") { ctx ->
            val v = WaveProgressView(ctx)
            Style(wrap(v, 18f), { p -> v.progress = p })
        },
        Entry("圆环表盘") { ctx ->
            val v = RingProgressView(ctx)
            Style(wrap(v, 26f), { p -> v.progress = p })
        },
        Entry("吃豆人") { ctx ->
            val v = PacmanProgressView(ctx)
            Style(wrap(v, 24f), { p -> v.progress = p })
        },
        Entry("小火车") { ctx ->
            val v = TrainProgressView(ctx)
            Style(wrap(v, 26f), { p -> v.progress = p })
        },
        Entry("电池充电") { ctx ->
            val v = BatteryProgressView(ctx)
            Style(wrap(v, 20f), { p -> v.progress = p })
        },
        Entry("火柴人跑步") { ctx ->
            val v = RunnerProgressView(ctx)
            Style(wrap(v, 30f), { p -> v.progress = p })
        },
        Entry("流星拉尾") { ctx ->
            val v = CometProgressView(ctx)
            Style(wrap(v, 20f), { p -> v.progress = p })
        }
    )

    /** 选择页每行的高度（含文字），给外部排版用 */
    internal fun name(idx: Int): String = entries[idx].name
}
