package com.lingxi.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sin

/**
 * 再 5 款下载进度条（和另外几文件同一套约定：只认 progress 0-100，配色走主题资源，
 * 系统关动画时不做循环动效）。这 5 款都属于「看着热闹但画得省」的一类，低端机也能跑。
 */

private fun fillOf(context: Context, colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = ContextCompat.getColor(context, colorRes)
}

private fun strokeOf(context: Context, colorRes: Int, dp: Float, density: Float) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp * density
        color = ContextCompat.getColor(context, colorRes)
    }

/** 齿轮咬合：前面一只大齿轮、后面一只小齿轮反着转，进度就是它们滚过的距离 */
class GearProgressView @JvmOverloads constructor(
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

    private val trackPaint = fillOf(context, R.color.chip_active_bg)
    private val gearPaint = fillOf(context, R.color.btn_solid)
    private val hubPaint = fillOf(context, R.color.btn_solid_fg)
    private val smallPaint = fillOf(context, R.color.accent)
    private val box = RectF()
    private val ticker = PhaseTicker(this, 1600L, wiggle) { phase = it }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    // 没被选中的样式是 GONE，但还挂在窗口上，不该占帧
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2f
        canvas.drawRoundRect(RectF(0f, mid - 1.5f * density, w, mid + 1.5f * density), 2f * density, 2f * density, trackPaint)

        val x = h * 0.34f + (w - h * 0.9f) * progress / 100f
        val r = h * 0.3f
        gear(canvas, x, mid, r, phase * 1.4f, gearPaint, hubPaint, 8)
        gear(canvas, x - r * 1.78f, mid - r * 0.42f, r * 0.5f, -phase * 2.0f, smallPaint, hubPaint, 6)
    }

    private fun gear(
        canvas: Canvas, cx: Float, cy: Float, r: Float, angle: Float,
        body: Paint, hub: Paint, teeth: Int
    ) {
        val spin = if (wiggle) angle else 0f
        for (i in 0 until teeth) {
            val a = spin + (2f * Math.PI.toFloat() / teeth) * i
            val px = cx + (r + 2.6f * density) * Math.cos(a.toDouble()).toFloat()
            val py = cy + (r + 2.6f * density) * Math.sin(a.toDouble()).toFloat()
            canvas.drawCircle(px, py, 2.4f * density, body)
        }
        box.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawOval(box, body)
        canvas.drawCircle(cx, cy, r * 0.32f, hub)
    }
}

/** 均衡器：一排音量柱随进度依次立起来，已经过去的柱子还在轻微跳 */
class EqualizerProgressView @JvmOverloads constructor(
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

    private val basePaint = fillOf(context, R.color.chip_active_bg)
    private val barPaint = fillOf(context, R.color.btn_solid)
    private val tipPaint = fillOf(context, R.color.accent)
    private val ticker = PhaseTicker(this, 760L, wiggle) { phase = it }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    // 没被选中的样式是 GONE，但还挂在窗口上，不该占帧
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bars = 22
        val gap = 1.6f * density
        val bw = (w - gap * (bars - 1)) / bars
        val lit = bars * progress / 100
        for (i in 0 until bars) {
            val left = i * (bw + gap)
            val wave = if (wiggle) abs(sin(phase + i * 0.55f)) else 0.5f
            val ratio = when {
                i < lit -> 0.35f + 0.6f * wave
                else -> 0.18f
            }
            val bh = h * ratio
            val paint = if (i < lit) barPaint else basePaint
            paint.alpha = if (i < lit) 235 else 255
            canvas.drawRoundRect(
                RectF(left, h - bh, left + bw, h), 1.6f * density, 1.6f * density, paint
            )
            if (i == lit - 1) {
                canvas.drawRoundRect(RectF(left, h - bh - 3f * density, left + bw, h - bh),
                        1.4f * density, 1.4f * density, tipPaint)
            }
        }
    }
}

/** 心电图：一条心电线从左往右扫，扫过的位置留下淡痕，节拍点正亮着 */
class EcgProgressView @JvmOverloads constructor(
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

    private val gridPaint = strokeOf(context, R.color.chip_active_bg, 1f, density)
    private val trailPaint = strokeOf(context, R.color.btn_solid, 1.8f, density).apply {
        alpha = 70
        strokeCap = Paint.Cap.ROUND
    }
    private val livePaint = strokeOf(context, R.color.btn_solid, 2.2f, density).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = fillOf(context, R.color.accent)
    private val line = Path()
    private val ticker = PhaseTicker(this, 1100L, wiggle) { phase = it }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    // 没被选中的样式是 GONE，但还挂在窗口上，不该占帧
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2f
        var gx = 0f
        while (gx < w) {
            canvas.drawLine(gx, h * 0.16f, gx, h * 0.84f, gridPaint)
            gx += 18f * density
        }
        canvas.drawLine(0f, mid, w, mid, gridPaint)

        val head = w * progress / 100f
        val beatShift = if (wiggle) phase else 0f
        line.reset()
        var x = 0f
        var first = true
        val stepX = 6f * density
        while (x <= head) {
            val t = x / (18f * density)
            val spike = spikeAt(t + beatShift / (2f * Math.PI).toFloat() * 4f)
            val y = mid - spike * h * 0.3f
            if (first) {
                line.moveTo(x, y)
                first = false
            } else line.lineTo(x, y)
            x += stepX
        }
        if (!first) {
            val fade = (head - 46f * density).coerceAtLeast(0f)
            canvas.drawLine(0f, mid, fade, mid, trailPaint)
            canvas.drawPath(line, livePaint)
            val lastY = mid - spikeAt((head / (18f * density)) + beatShift / (2f * Math.PI).toFloat() * 4f) * h * 0.3f
            canvas.drawCircle(head, lastY, 3f * density, dotPaint)
        }
    }

    /** 一个心跳周期里的尖峰形状：平-小起伏-大尖-回平 */
    private fun spikeAt(t: Float): Float {
        val p = t - kotlin.math.floor(t)
        return when {
            p < 0.18f -> sin(p / 0.18f * Math.PI.toFloat()) * 0.22f
            p < 0.34f -> -0.3f
            p < 0.46f -> 1f - (p - 0.34f) / 0.12f * 1.6f
            p < 0.6f -> -0.6f + (p - 0.46f) / 0.14f * 0.6f
            else -> 0f
        }
    }
}

/** 小船过河：船身随浪起伏，船后拖着两道水纹，靠岸即满格 */
class BoatProgressView @JvmOverloads constructor(
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

    private val waterPaint = fillOf(context, R.color.chip_active_bg)
    private val hullPaint = fillOf(context, R.color.btn_solid)
    private val sailPaint = fillOf(context, R.color.btn_solid)
    private val ripplePaint = strokeOf(context, R.color.accent, 1.4f, density)
    private val hull = Path()
    private val sail = Path()
    private val ticker = PhaseTicker(this, 1400L, wiggle) { phase = it }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    // 没被选中的样式是 GONE，但还挂在窗口上，不该占帧
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val waterTop = h * 0.62f
        canvas.drawRect(0f, waterTop, w, h, waterPaint)

        val x = h * 0.26f + (w - h * 0.6f) * progress / 100f
        val bob = if (wiggle) sin(phase * 2f) * h * 0.05f else 0f
        val deck = waterTop - h * 0.08f + bob

        hull.reset()
        hull.moveTo(x - h * 0.24f, deck)
        hull.lineTo(x + h * 0.24f, deck)
        hull.lineTo(x + h * 0.15f, deck + h * 0.16f)
        hull.lineTo(x - h * 0.15f, deck + h * 0.16f)
        hull.close()
        canvas.drawPath(hull, hullPaint)

        sail.reset()
        sail.moveTo(x + h * 0.02f, deck - h * 0.42f)
        sail.lineTo(x + h * 0.02f, deck - h * 0.02f)
        sail.lineTo(x - h * 0.2f, deck - h * 0.02f)
        sail.close()
        canvas.drawPath(sail, sailPaint)
        canvas.drawLine(x + h * 0.02f, deck - h * 0.42f, x + h * 0.02f, deck, hullPaint)

        var i = 1
        while (i <= 3) {
            val rx = x - h * (0.3f + i * 0.22f)
            if (rx < 0f) break
            ripplePaint.alpha = 200 - i * 55
            canvas.drawLine(rx, waterTop + h * 0.06f, rx - 6f * density, waterTop + h * 0.06f, ripplePaint)
            i++
        }
        canvas.drawLine(w - 2f * density, h * 0.2f, w - 2f * density, h, ripplePaint)
    }
}

/** 毛毛虫：一节一节身子往前拱，头节先过去，身后各节按延迟跟上 */
class CaterpillarProgressView @JvmOverloads constructor(
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

    private val trackPaint = fillOf(context, R.color.chip_active_bg)
    private val bodyPaint = fillOf(context, R.color.btn_solid)
    private val headPaint = fillOf(context, R.color.accent)
    private val eyePaint = fillOf(context, R.color.btn_solid_fg)
    private val ticker = PhaseTicker(this, 820L, wiggle) { phase = it }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    // 没被选中的样式是 GONE，但还挂在窗口上，不该占帧
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2f
        val r = h * 0.24f
        canvas.drawRoundRect(RectF(0f, mid - 1.2f * density, w, mid + 1.2f * density), 1.2f * density, 1.2f * density, trackPaint)

        val head = r + (w - 2f * r) * progress / 100f
        val segments = 6
        for (i in segments downTo 1) {
            val lag = i * 0.42f
            val phaseShift = if (wiggle) sin(phase - lag) * r * 0.5f else 0f
            val x = head - i * r * 1.05f
            if (x - r < 0f) continue
            bodyPaint.alpha = 245 - i * 22
            canvas.drawCircle(x, mid + phaseShift * 0.5f, r * (1f - i * 0.04f), bodyPaint)
        }
        val headBob = if (wiggle) sin(phase) * r * 0.4f else 0f
        canvas.drawCircle(head, mid + headBob * 0.4f, r, headPaint)
        canvas.drawCircle(head + r * 0.3f, mid + headBob * 0.4f - r * 0.3f, 1.6f * density, eyePaint)
    }
}
