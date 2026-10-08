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
 * 另外 5 款下载进度条（与 ProgressStyles.kt 同一套约定：
 * 只认一个 `progress`（0-100），配色走主题资源，系统关动画时退化为静态跟随）。
 */

private fun fillPaint(context: Context, colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = ContextCompat.getColor(context, colorRes)
}

private fun strokePaint(context: Context, colorRes: Int, dp: Float, density: Float) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp * density
        color = ContextCompat.getColor(context, colorRes)
    }

/** 吃豆人：轨道上撒着豆子，圆嘴一张一合把经过的豆子吞掉 */
class PacmanProgressView @JvmOverloads constructor(
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

    private val railPaint = strokePaint(context, R.color.chip_active_bg, 1.4f, density)
    private val beanPaint = fillPaint(context, R.color.accent)
    private val bodyPaint = fillPaint(context, R.color.btn_solid)
    private val eyePaint = fillPaint(context, R.color.btn_solid_fg)
    private val ticker = PhaseTicker(this, 520L, wiggle) { phase = it }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    // 没被选中的样式是 GONE，但依然挂在窗口上，不该空转
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2f
        val r = h * 0.32f
        val head = r + (w - 2f * r) * progress / 100f

        canvas.drawLine(r * 1.6f, mid, w, mid, railPaint)
        var x = r * 2f
        while (x < w) {
            if (x > head + r * 0.7f) canvas.drawCircle(x, mid, 2.4f * density, beanPaint)
            x += 15f * density
        }

        val open = if (wiggle) 20f + 20f * abs(sin(phase)) else 12f
        canvas.drawArc(RectF(head - r, mid - r, head + r, mid + r), open, 360f - 2f * open, true, bodyPaint)
        canvas.drawCircle(head + r * 0.2f, mid - r * 0.55f, 1.7f * density, eyePaint)
    }
}

/** 小火车：车头拉着三节车厢沿轨道跑，轮辐跟着转 */
class TrainProgressView @JvmOverloads constructor(
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

    private val railPaint = strokePaint(context, R.color.chip_active_bg, 1.6f, density)
    private val spokePaint = strokePaint(context, R.color.chip_active_bg, 1.2f, density)
    private val bodyPaint = fillPaint(context, R.color.btn_solid)
    private val wheelPaint = fillPaint(context, R.color.accent)
    private val windowPaint = fillPaint(context, R.color.btn_solid_fg)
    private val ticker = PhaseTicker(this, 700L, wiggle) { phase = it }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    // 没被选中的样式是 GONE，但依然挂在窗口上，不该空转
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rail = h * 0.78f
        canvas.drawLine(0f, rail, w, rail, railPaint)

        val locoW = h * 0.6f
        val locoH = h * 0.44f
        val x = locoW / 2f + (w - locoW) * progress / 100f

        for (i in 1..3) {
            val cx = x - i * (locoW + 5f * density)
            if (cx - locoW * 0.5f < 0f) break
            bodyPaint.alpha = 215 - i * 55
            canvas.drawRoundRect(
                RectF(cx - locoW * 0.44f, rail - locoH * 0.95f, cx + locoW * 0.44f, rail - locoH * 0.12f),
                3f * density, 3f * density, bodyPaint
            )
            canvas.drawCircle(cx, rail - locoH * 0.05f, 2.6f * density, wheelPaint)
        }

        bodyPaint.alpha = 255
        canvas.drawRoundRect(
            RectF(x - locoW / 2f, rail - locoH, x + locoW / 2f, rail - locoH * 0.12f),
            4f * density, 4f * density, bodyPaint
        )
        canvas.drawRect(x - locoW * 0.1f, rail - locoH * 1.4f, x + locoW * 0.14f, rail - locoH, bodyPaint)
        canvas.drawRoundRect(
            RectF(x + locoW * 0.04f, rail - locoH * 0.8f, x + locoW * 0.38f, rail - locoH * 0.44f),
            1.5f * density, 1.5f * density, windowPaint
        )
        listOf(x - locoW * 0.24f, x + locoW * 0.26f).forEach { cx ->
            canvas.drawCircle(cx, rail - locoH * 0.04f, 3f * density, wheelPaint)
            val spin = if (wiggle) locoW * 0.12f * sin(phase * 2f) else 0f
            canvas.drawLine(cx, rail - locoH * 0.04f - 2.4f * density, cx, rail - locoH * 0.04f + 2.4f * density, spokePaint)
            canvas.drawLine(cx - 2.4f * density, rail - locoH * 0.04f, cx + 2.4f * density, rail - locoH * 0.04f, spokePaint)
            canvas.drawLine(cx - spin, rail - locoH * 0.04f - spin, cx + spin, rail - locoH * 0.04f + spin, spokePaint)
        }
    }
}

/** 电池充电：一格一格充进去，最后一格闪一下，充满时中间亮一道闪电 */
class BatteryProgressView @JvmOverloads constructor(
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

    private val shellPaint = strokePaint(context, R.color.card_stroke, 1.4f, density)
    private val capPaint = fillPaint(context, R.color.card_stroke)
    private val emptyPaint = fillPaint(context, R.color.chip_active_bg)
    private val cellPaint = fillPaint(context, R.color.btn_solid)
    private val boltPaint = fillPaint(context, R.color.accent)
    private val bolt = Path()
    private val ticker = PhaseTicker(this, 1000L, wiggle) { phase = it }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    // 没被选中的样式是 GONE，但依然挂在窗口上，不该空转
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val capW = 4f * density
        val body = RectF(0f, h * 0.14f, w - capW - 2f * density, h * 0.86f)
        canvas.drawRoundRect(
            RectF(body.right + 2f * density, h * 0.34f, w, h * 0.66f),
            1.5f * density, 1.5f * density, capPaint
        )
        canvas.drawRoundRect(body, 4f * density, 4f * density, emptyPaint)

        val cells = 8
        val pad = 2.2f * density
        val cw = (body.width() - pad * (cells + 1)) / cells
        val lit = cells * progress / 100
        for (i in 0 until cells) {
            val left = body.left + pad + i * (cw + pad)
            if (i < lit) {
                cellPaint.alpha = if (i == lit - 1 && wiggle) 150 + (105 * (sin(phase) * 0.5f + 0.5f)).toInt() else 245
                canvas.drawRect(left, body.top + pad, left + cw, body.bottom - pad, cellPaint)
            }
        }
        canvas.drawRoundRect(body, 4f * density, 4f * density, shellPaint)

        if (progress >= 100) {
            val cx = body.centerX()
            val cy = body.centerY()
            bolt.reset()
            bolt.moveTo(cx + 3f * density, cy - 8f * density)
            bolt.lineTo(cx - 4f * density, cy + 1f * density)
            bolt.lineTo(cx + 0.5f * density, cy + 1f * density)
            bolt.lineTo(cx - 2.5f * density, cy + 8f * density)
            bolt.lineTo(cx + 5f * density, cy - 1.5f * density)
            bolt.lineTo(cx + 0.8f * density, cy - 1.5f * density)
            bolt.close()
            boltPaint.alpha = if (wiggle) 150 + (105 * (sin(phase * 2f) * 0.5f + 0.5f)).toInt() else 240
            canvas.drawPath(bolt, boltPaint)
        }
    }
}

/** 火柴人跑步：脚下虚线往后退，四肢摆动，跑到右端即满格 */
class RunnerProgressView @JvmOverloads constructor(
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

    private val groundPaint = strokePaint(context, R.color.chip_active_bg, 2f, density)
    private val dashPaint = strokePaint(context, R.color.hairline, 1.4f, density)
    private val inkPaint = strokePaint(context, R.color.btn_solid, 2.2f, density).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = fillPaint(context, R.color.btn_solid)
    private val flagPaint = fillPaint(context, R.color.accent)
    private val ticker = PhaseTicker(this, 420L, wiggle) { phase = it }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    // 没被选中的样式是 GONE，但依然挂在窗口上，不该空转
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val ground = h * 0.88f
        canvas.drawLine(0f, ground, w, ground, groundPaint)

        val shift = if (wiggle) (phase / (2f * Math.PI).toFloat()) * 14f * density else 0f
        var dx = -shift
        while (dx < w) {
            canvas.drawLine(dx, ground + 4f * density, dx + 7f * density, ground + 4f * density, dashPaint)
            dx += 14f * density
        }

        val x = 8f * density + (w - 20f * density) * progress / 100f
        val swing = if (wiggle) sin(phase * 2f) else 0.4f
        val hip = ground - h * 0.3f
        val shoulder = ground - h * 0.6f
        val bounce = if (wiggle) -h * 0.04f * abs(sin(phase * 2f)) else 0f

        canvas.drawCircle(x + h * 0.05f, shoulder - h * 0.14f + bounce, h * 0.1f, headPaint)
        canvas.drawLine(x, shoulder + bounce, x - h * 0.02f, hip, inkPaint)
        canvas.drawLine(x - h * 0.02f, hip, x - h * 0.02f + h * 0.22f * swing, ground, inkPaint)
        canvas.drawLine(x - h * 0.02f, hip, x - h * 0.02f - h * 0.22f * swing, ground, inkPaint)
        canvas.drawLine(x, shoulder + h * 0.06f + bounce, x + h * 0.16f * swing, shoulder + h * 0.2f + bounce, inkPaint)
        canvas.drawLine(x, shoulder + h * 0.06f + bounce, x - h * 0.16f * swing, shoulder + h * 0.16f + bounce, inkPaint)

        canvas.drawRect(w - 3f * density, h * 0.14f, w, ground, flagPaint)
    }
}

/** 流星拉尾：一颗头带渐淡尾迹划过，尾迹长度随进度增长 */
class CometProgressView @JvmOverloads constructor(
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

    private val railPaint = strokePaint(context, R.color.chip_active_bg, 1.2f, density)
    private val headPaint = fillPaint(context, R.color.accent)
    private val trailPaint = strokePaint(context, R.color.btn_solid, 5f, density).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val sparklePaint = fillPaint(context, R.color.btn_solid_fg)
    private val ticker = PhaseTicker(this, 900L, wiggle) { phase = it }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    // 没被选中的样式是 GONE，但依然挂在窗口上，不该空转
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2f
        canvas.drawLine(0f, mid, w, mid, railPaint)

        val head = 6f * density + (w - 12f * density) * progress / 100f
        val tail = head * 0.6f
        // 尾迹：分成 6 段，越靠近头部越实
        for (i in 0 until 6) {
            val seg0 = head - tail * (i + 1) / 6f
            val seg1 = head - tail * i / 6f
            trailPaint.alpha = (40 + 200 * (6 - i) / 6)
            canvas.drawLine(seg0, mid, seg1, mid, trailPaint)
        }
        val pulse = if (wiggle) 1f + 0.18f * sin(phase * 3f) else 1f
        canvas.drawCircle(head, mid, 5f * density * pulse, headPaint)
        if (wiggle && progress in 5..95) {
            sparklePaint.alpha = (200 * (sin(phase * 2f) * 0.5f + 0.5f)).toInt()
            canvas.drawCircle(head - tail * 0.55f, mid - 5f * density, 1.4f * density, sparklePaint)
        }
    }
}
