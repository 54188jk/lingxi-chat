package com.lingxi.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.lingxi.chat.data.DevicePerf
import kotlin.math.cos
import kotlin.math.sin

/**
 * 壁纸上的呼吸光：三团缓慢漂移的柔光，让整屏不像一张死图。
 * 只用全局那条刷新节拍（ProgressFrameClock），轻量档或系统关掉动画时静止不画第二帧。
 */
class DeskGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val animate = animationsOn(context) && !DevicePerf.lowEnd(context)
    private var phase = 0f

    private val ticker = PhaseTicker(this, 18_000L, animate) { p ->
        phase = p
        invalidate()
    }

    /** 三团光的位置基准、半径与颜色 */
    private data class Glow(val cx: Float, val cy: Float, val radius: Float, val color: Int)

    private val glows = listOf(
        Glow(0.18f, 0.16f, 1.15f, ContextCompat.getColor(context, R.color.chip_active_bg)),
        Glow(0.86f, 0.34f, 0.9f, ContextCompat.getColor(context, R.color.badge_bg)),
        Glow(0.5f, 1.02f, 1.3f, ContextCompat.getColor(context, R.color.chip_active_bg))
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var prepared: List<Pair<Float, Shader?>> = emptyList()
    private var preparedFor = 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) ticker.attach()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) ticker.attach() else ticker.detach()
    }

    override fun onDetachedFromWindow() {
        ticker.detach()
        super.onDetachedFromWindow()
    }

    /** 尺寸不变就不重建渐变，漂移靠平移矩阵 */
    private fun prepare() {
        val tag = width * 100_000 + height
        if (tag == preparedFor) return
        prepared = glows.map { g ->
            val r = (minOf(width, height) * g.radius * 0.75f).coerceAtLeast(120f * density)
            val shader = RadialGradient(
                r, r, r,
                intArrayOf(g.color, 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            r to shader
        }
        preparedFor = tag
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        prepare()
        val t = phase * 2.0 * Math.PI
        prepared.forEachIndexed { i, (r, shader) ->
            val g = glows[i]
            val sway = if (animate) 0.045f * sin(t + i * 2.1).toFloat() else 0f
            val lift = if (animate) 0.035f * cos(t + i * 1.4).toFloat() else 0f
            val cx = width * (g.cx + sway) - r
            val cy = height * (g.cy + lift) - r
            paint.shader = shader
            canvas.save()
            canvas.translate(cx, cy)
            canvas.drawRect(0f, 0f, r * 2f, r * 2f, paint)
            canvas.restore()
        }
        paint.shader = null
    }
}
