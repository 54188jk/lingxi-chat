package com.lingxi.chat

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.data.DevicePerf
import kotlin.math.max
import kotlin.math.min

/**
 * 液态玻璃面板：把身后内容模糊后贴进自己这块圆角里，靠边一圈做折射般的放大带，
 * 上缘一道镜面高光、下沿一抹内阴影；按下去会鼓起来、高光跟手指走、松手带过冲回弹。
 * 轻量档机器或设置里关掉「液态玻璃」时，只画半透明底 + 高光描边，不抓屏幕。
 */
open class GlassSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private var radiusDp = 18f
    private var blurDp = 18f
    private var rimStrength = 1f
    private var tintFocus = false

    private val tint = ContextCompat.getColor(context, R.color.glass_tint)
    private val topLight = ContextCompat.getColor(context, R.color.glass_top_light)
    private val edgeColor = ContextCompat.getColor(context, R.color.glass_edge)
    private val shadeColor = ContextCompat.getColor(context, R.color.glass_shade)

    private val bodyPath = Path()
    private val lensPath = Path()
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val src = Rect()
    private val dst = RectF()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var press = 0f
    private var touchX = -1f
    private var touchY = -1f
    private var spring: ValueAnimator? = null
    private val cheap = DevicePerf.lowEnd(context)

    private val here = IntArray(2)
    private val base = IntArray(2)

    init {
        setWillNotDraw(false)
        clipToOutline = false
        val a = context.obtainStyledAttributes(attrs, R.styleable.GlassSurface)
        radiusDp = a.getDimension(R.styleable.GlassSurface_glassRadius, radiusDp * density) / density
        blurDp = a.getDimension(R.styleable.GlassSurface_glassBlur, blurDp * density) / density
        rimStrength = a.getFloat(R.styleable.GlassSurface_glassRim, 1f)
        a.recycle()
    }

    private fun glassOn(): Boolean = ConfigStore(context).liquidGlass

    /** 输入框获得焦点时由代码点亮一圈主色 */
    fun setGlassFocused(focused: Boolean) {
        if (tintFocus == focused) return
        tintFocus = focused
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!cheap && glassOn()) GlassBackdrop.addPanel(this)
    }

    override fun onDetachedFromWindow() {
        GlassBackdrop.removePanel(this)
        spring?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            super.onDraw(canvas)
            return
        }
        val radius = radiusDp * density * (1f + 0.14f * press)
        outerRect.set(0f, 0f, w, h)
        bodyPath.reset()
        bodyPath.addRoundRect(outerRect, radius, radius, Path.Direction.CW)

        val act = context as? Activity
        val bmp: Bitmap? = if (cheap || !glassOn()) null else act?.let { GlassBackdrop.snapshot(it) }
        if (bmp != null) {
            var saves = 0
            try {
                canvas.save(); saves++
                canvas.clipPath(bodyPath)
                getLocationInWindow(here)
                act?.let { rootBase(it) }
                val s = GlassBackdrop.scale()
                src.left = max(0, ((here[0] - base[0]) * s).toInt())
                src.top = max(0, ((here[1] - base[1]) * s).toInt())
                src.right = min(bmp.width, src.left + (w * s).toInt().coerceAtLeast(1))
                src.bottom = min(bmp.height, src.top + (h * s).toInt().coerceAtLeast(1))
                if (src.right > src.left && src.bottom > src.top) {
                    dst.set(0f, 0f, w, h)
                    canvas.drawBitmap(bmp, src, dst, bgPaint)

                    // 边缘折射带：贴着边一圈把背景略微放大重画，看着就是玻璃的厚度
                    val band = 8f * density
                    innerRect.set(band, band, w - band, h - band)
                    lensPath.reset()
                    lensPath.addRoundRect(outerRect, radius, radius, Path.Direction.CW)
                    lensPath.addRoundRect(innerRect, radius * 0.7f, radius * 0.7f, Path.Direction.CW)
                    lensPath.fillType = Path.FillType.INVERSE_EVEN_ODD
                    canvas.save(); saves++
                    canvas.clipPath(lensPath)
                    canvas.scale(1.08f, 1.08f, w / 2f, h / 2f)
                    canvas.drawBitmap(bmp, src, dst, bgPaint)
                    canvas.restore(); saves--

                    // 手指下的一团柔光
                    if (press > 0.02f && touchX >= 0f) {
                        glowPaint.shader = RadialGradientShaders.of(
                            touchX, touchY, max(w, h) * 0.62f,
                            blend(topLight, 0.9f), Color.TRANSPARENT
                        )
                        canvas.drawRect(0f, 0f, w, h, glowPaint)
                        glowPaint.shader = null
                    }
                }
            } catch (t: Throwable) {
                // 背景图哪一帧不对就只画半透明底，宁可少层玻璃也不能崩
            } finally {
                repeat(saves) { runCatching { canvas.restore() } }
            }
        }

        tintPaint.color = blend(tint, 0.82f + 0.18f * press)
        canvas.drawPath(bodyPath, tintPaint)

        glossPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(blend(topLight, rimStrength), blend(topLight, 0.12f * rimStrength), Color.TRANSPARENT),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(bodyPath, glossPaint)
        glossPaint.shader = null

        edgePaint.strokeWidth = 0.8f * density
        edgePaint.color = if (tintFocus) accent() else edgeColor
        canvas.drawPath(bodyPath, edgePaint)
        if (tintFocus) {
            edgePaint.strokeWidth = 1.6f * density
            edgePaint.color = blend(accent(), 0.45f)
            canvas.drawPath(bodyPath, edgePaint)
        }

        shadePaint.shader = LinearGradient(
            0f, h - 14f * density, 0f, h,
            intArrayOf(Color.TRANSPARENT, blend(shadeColor, 0.9f)), null, Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.clipPath(bodyPath)
        canvas.drawRect(0f, h - 14f * density, w, h, shadePaint)
        canvas.restore()
        shadePaint.shader = null

        super.onDraw(canvas)
    }

    private fun rootBase(act: Activity) {
        val root = GlassBackdrop.rootOf(act) ?: return
        root.getLocationInWindow(base)
    }

    private fun accent() = ContextCompat.getColor(context, R.color.accent)

    private fun blend(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = event.x
                touchY = event.y
                animateTo(1f, 90L, false)
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchX = -1f
                animateTo(0f, 300L, true)
            }
        }
        return super.onTouchEvent(event)
    }

    /** 松手时冲过零点再回来，玻璃才有「弹」的感觉 */
    private fun animateTo(target: Float, duration: Long, overshoot: Boolean) {
        spring?.cancel()
        val from = press
        spring = if (overshoot && !cheap) {
            ValueAnimator.ofFloat(from, 0f, min(1f, from + 0.18f), 0f).apply {
                this.duration = duration
                addUpdateListener { press = it.animatedValue as Float; invalidate() }
            }
        } else {
            ValueAnimator.ofFloat(from, target).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { press = it.animatedValue as Float; invalidate() }
            }
        }
        spring?.start()
    }

    /** 外部滚动了身后内容时叫一声，让玻璃里的倒影跟上 */
    fun invalidateGlass() = invalidate()

    private object RadialGradientShaders {
        fun of(cx: Float, cy: Float, r: Float, inner: Int, outer: Int): Shader =
            android.graphics.RadialGradient(cx, cy, r, inner, outer, Shader.TileMode.CLAMP)
    }
}
