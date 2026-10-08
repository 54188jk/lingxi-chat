package com.lingxi.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.sin

/**
 * 下载进度的几种花样式视图，对外都只有一个 `progress`（0-100）。
 * 配色全部走主题资源（btn_solid / chip_active_bg / accent / btn_solid_fg / card_stroke），
 * 深浅色自动适配；系统动画被关掉（animator scale = 0）时不做循环动效，只静态跟随进度。
 */

internal fun animationsOn(context: Context): Boolean = Settings.Global.getFloat(
    context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
) > 0f

/** 0→2π 的无限循环，驱动帧动画；每次刷新把相位交给 onPhase 并请求重绘 */
internal class PhaseTicker(
    view: View,
    durationMs: Long,
    private val enabled: Boolean,
    private val onPhase: (Float) -> Unit
) {
    private val anim = ValueAnimator.ofFloat(0f, (2.0 * Math.PI).toFloat()).apply {
        duration = durationMs
        repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener {
            onPhase(it.animatedValue as Float)
            view.invalidate()
        }
    }

    fun attach() {
        if (enabled) anim.start()
    }

    fun detach() {
        anim.cancel()
    }
}

/** 表盘进度：左边一枚圆环像仪表一样顺时针走，环口一颗脉动的亮点；右边一条细轨道同步延伸 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val wiggle = animationsOn(context)
    private var phase = 0f

    private val trackPaint = strokePaint(ContextCompat.getColor(context, R.color.chip_active_bg))
    private val arcPaint = strokePaint(ContextCompat.getColor(context, R.color.btn_solid)).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = strokePaint(ContextCompat.getColor(context, R.color.accent)).apply {
        alpha = 90
        strokeWidth = 1.6f * density
    }
    private val dotPaint = fillPaint(ContextCompat.getColor(context, R.color.accent))
    private val railPaint = strokePaint(ContextCompat.getColor(context, R.color.chip_active_bg)).apply {
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val railInkPaint = strokePaint(ContextCompat.getColor(context, R.color.btn_solid)).apply {
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val ticker = PhaseTicker(this, 2400L, wiggle) { phase = it }

    private fun strokePaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = 6f * density
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    // 没被选中时这个 View 是 GONE，但它依然挂在窗口上；不管理可见性就会一直空转
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val cx = h / 2f
        val cy = h / 2f
        val r = cx - 5f * density
        if (r <= 0f) return
        val sweep = 360f * progress / 100f

        // 细轨道：从表盘右侧一直延伸到行尾
        val railStart = h + 6f * density
        val railEnd = w - 4f * density
        if (railEnd > railStart) {
            canvas.drawLine(railStart, cy, railEnd, cy, railPaint)
            val filledEnd = railStart + (railEnd - railStart) * progress / 100f
            canvas.drawLine(railStart, cy, filledEnd, cy, railInkPaint)
        }

        canvas.drawCircle(cx, cy, r, trackPaint)
        listOf(0f, 90f, 180f, 270f).forEach { deg ->
            val rad = Math.toRadians(deg.toDouble())
            val sx = Math.sin(rad).toFloat()
            val sy = -Math.cos(rad).toFloat()
            canvas.drawLine(
                cx + sx * (r - 9f * density), cy + sy * (r - 9f * density),
                cx + sx * (r - 4f * density), cy + sy * (r - 4f * density),
                tickPaint
            )
        }
        if (sweep > 0.3f) {
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, sweep, false, arcPaint)
        }
        val head = Math.toRadians((-90f + sweep).toDouble())
        val pulse = if (wiggle) 1f + 0.22f * sin(phase * 2f) else 1f
        canvas.drawCircle(
            cx + r * Math.cos(head).toFloat(),
            cy + r * Math.sin(head).toFloat(),
            3.2f * density * pulse,
            dotPaint
        )
    }
}

/** 像素方块进度：一格一格点亮，正在进入的那格是强调色并跳动，未到的格子只留浅浅的痕 */
class BlocksProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val wiggle = animationsOn(context)
    private val columns = 16
    private var phase = 0f

    private val emptyPaint = fill(ContextCompat.getColor(context, R.color.chip_active_bg))
    private val filledPaint = fill(ContextCompat.getColor(context, R.color.btn_solid))
    private val headPaint = fill(ContextCompat.getColor(context, R.color.accent))
    private val ticker = PhaseTicker(this, 620L, wiggle) { phase = it }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    // 没被选中时这个 View 是 GONE，但它依然挂在窗口上；不管理可见性就会一直空转
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 3f * density
        val cw = (w - gap * (columns - 1)) / columns
        val radius = 2.5f * density
        val lit = columns * progress / 100
        val base = h * 0.74f
        val mid = h / 2f

        for (i in 0 until columns) {
            val left = i * (cw + gap)
            val box: Float
            val paint: Paint
            when {
                i < lit - 1 -> {
                    filledPaint.alpha = 140 + (115 * i / (columns - 1)).toInt()
                    paint = filledPaint
                    box = base
                }
                i == lit - 1 -> {
                    headPaint.alpha = 255
                    paint = headPaint
                    box = base * (if (wiggle) 1f + 0.2f * sin(phase * 2f) else 1f)
                }
                else -> {
                    emptyPaint.alpha = 255
                    paint = emptyPaint
                    box = base * 0.4f
                }
            }
            canvas.drawRoundRect(RectF(left, mid - box / 2f, left + cw, mid + box / 2f), radius, radius, paint)
        }
    }
}

/** 液体进度：容器里的水从左往右涨，液面是一道来回摆动的竖波，水里两颗气泡跟着漂 */
class WaveProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val wiggle = animationsOn(context)
    private var phase = 0f

    private val shellFill = fill(ContextCompat.getColor(context, R.color.chip_active_bg))
    private val water = fill(ContextCompat.getColor(context, R.color.btn_solid)).apply { alpha = 235 }
    private val foam = stroke(ContextCompat.getColor(context, R.color.accent), 2f)
    private val shell = stroke(ContextCompat.getColor(context, R.color.card_stroke), 1.2f)
    private val bubble = fill(ContextCompat.getColor(context, R.color.btn_solid_fg)).apply { alpha = 80 }

    private val clip = Path()
    private val body = Path()
    private val surface = Path()
    private val ticker = PhaseTicker(this, 1500L, wiggle) { phase = it }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun stroke(color: Int, dp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp * density
        this.color = color
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    // 没被选中时这个 View 是 GONE，但它依然挂在窗口上；不管理可见性就会一直空转
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f * 0.55f
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, shellFill)

        val edge = w * progress / 100f
        val amp = if (wiggle) h * 0.16f else 0f
        val sway = if (wiggle) sin(phase * 2f) * amp else 0f

        body.reset()
        surface.reset()
        if (edge > 0f) {
            val steps = 8
            val dx = h / steps
            body.moveTo(0f, 0f)
            var y = 0f
            var x = edge + sway
            body.lineTo(x, y)
            surface.moveTo(x, y)
            var flip = 1f
            for (i in 1..steps) {
                y = (dx * i).coerceAtMost(h)
                x = edge + sway * flip
                body.quadTo(edge + sway * flip * 0.4f, y - dx / 2f, x, y)
                surface.quadTo(edge + sway * flip * 0.4f, y - dx / 2f, x, y)
                flip = -flip
            }
            body.lineTo(0f, h)
            body.close()
        }

        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        if (edge > 0f) {
            canvas.drawPath(body, water)
            canvas.drawPath(surface, foam)
            val float = if (wiggle) (phase / (2f * Math.PI).toFloat()) else 0f
            canvas.drawCircle(edge * 0.42f, h * (0.3f + 0.12f * float), 2.2f * density, bubble)
            canvas.drawCircle(edge * 0.68f, h * (0.72f - 0.1f * float), 1.5f * density, bubble)
        }
        canvas.restore()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, shell)
    }
}
