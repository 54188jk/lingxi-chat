package com.lingxi.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.sin

/**
 * 贪吃蛇样式的下载进度条：
 * 蛇身方块从左侧随进度逐格推进，蛇头带眼睛并有轻微上下蠕动；
 * 轨道上撒着固定位置的豆子，蛇头经过即「吃掉」。
 * 配色全部走主题资源（btn_solid / chip_active_bg / accent），深浅色自动适配；
 * 系统动画被关闭（animator scale = 0）时不做蠕动，仅静态跟随进度。
 */
class SnakeProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val step = 16f * density        // 每格（豆子间距/蛇身方块间距）
    private val block = 11f * density       // 蛇身方块边长
    private val radius = 3.5f * density     // 方块圆角

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chip_active_bg)
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.btn_solid)
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.btn_solid)
    }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.btn_solid_fg)
    }
    private val foodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }

    /** 相位：驱动蛇头蠕动与豆子脉动；关闭动画时恒为 0 */
    private var phase = 0f
    private val wiggleEnabled = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) > 0f
    private val ticker = ValueAnimator.ofFloat(0f, (2f * PI).toFloat()).apply {
        duration = 900L
        repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (wiggleEnabled) ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f
        val trackH = 12f * density
        canvas.drawRoundRect(0f, midY - trackH / 2, w, midY + trackH / 2, trackH / 2, trackH / 2, trackPaint)

        val usable = w - step / 2
        val headX = usable * progress / 100f
        val wig = if (wiggleEnabled) sin(phase * 2f) * 1.6f * density else 0f

        // 豆子：每格一颗，蛇头越过即被吃掉
        var x = step
        while (x < usable) {
            if (x > headX) {
                val pulse = if (wiggleEnabled) 1f + 0.18f * sin(phase * 2f + x / step) else 1f
                canvas.drawCircle(x, midY, 2.6f * density * pulse, foodPaint)
            }
            x += step
        }

        // 蛇身：0 到 headX 的圆角方块，尾部渐淡
        var bx = 0f
        var idx = 0
        val cols = ((headX - block / 2) / step).toInt()
        while (idx <= cols && bx <= headX) {
            val tailFade = if (cols <= 1) 255 else (60 + 195 * (bx / (headX.coerceAtLeast(1f)))).toInt().coerceIn(60, 255)
            bodyPaint.alpha = tailFade
            val top = midY - block / 2 + (bx / usable) * wig
            canvas.drawRoundRect(RectF(bx, top, bx + block, top + block), radius, radius, bodyPaint)
            bx += step
            idx++
        }

        // 蛇头：稍大一格 + 双眼，朝行进方向
        val head = 13f * density
        val hy = midY + wig
        canvas.drawRoundRect(
            RectF(headX - head / 2, hy - head / 2, headX + head / 2, hy + head / 2),
            5f * density, 5f * density, headPaint
        )
        canvas.drawCircle(headX + 1.5f * density, hy - 2.4f * density, 1.7f * density, eyePaint)
        canvas.drawCircle(headX + 1.5f * density, hy + 2.4f * density, 1.7f * density, eyePaint)

        // 满格时蛇头前方一颗强调色豆（校验完成的反馈）
        if (progress >= 100) {
            canvas.drawCircle(headX + head / 2, hy, 3f * density, flashPaint)
        }
    }
}
