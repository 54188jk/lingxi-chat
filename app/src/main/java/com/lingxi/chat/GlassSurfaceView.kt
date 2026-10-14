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
 * 液态玻璃面板：贴一层身后内容的模糊图，边缘做透镜折射，外面包一圈镜面高光，
 * 按下去会鼓起来一点、高光跟着手指走、松手带过冲回弹——都是 iOS 那套做法。
 * 拿不到背景（页面没登记源视图）、或机器是轻量档、或用户关了玻璃时，退成半透明底 + 高光描边。
 */
class GlassSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private var radiusDp = 22f
    private var blurDp = 22f
    private var rimStrength = 1f
    private var tintColor = ContextCompat.getColor(context, R.color.desk_glass)

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val outer = Path()
    private val ring = Path()
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()

    private var press = 0f
    private var touchX = -1f
    private var touchY = -1f
    private var anim: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        val a = context.obtainStyledAttributes(attrs, R.styleable.GlassSurface)
        radiusDp = a.getDimension(R.styleable.GlassSurface_glassRadius, radiusDp * density) / density
        blurDp = a.getDimension(R.styleable.GlassSurface_glassBlur, blurDp * density) / density
        tintColor = a.getColor(R.styleable.GlassSurface_glassTint, tintColor)
        rimStrength = a.getFloat(R.styleable.GlassSurface_glassRim, 1f)
        a.recycle()
    }

    /** 轻量档或用户在设置里关了玻璃时，只画半透明底 */
    private fun glassAllowed(): Boolean {
        if (!ConfigStore(context).liquidGlass) return false
        return true
    }

    private fun cheap(): Boolean = DevicePerf.lowEnd(context)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = (radiusDp * density * (1f + 0.12f * press))
        outerRect.set(0f, 0f, w, h)
        outer.reset()
        outer.addRoundRect(outerRect, r, r, Path.Direction.CW)

        val bmp: Bitmap? = if (glassAllowed() && !cheap()) backdrop() else null
        if (bmp != null) {
            val scale = backdropScale
            val here = IntArray(2)
            getLocationInWindow(here)
            val anchorHere = anchorLocation()
            srcRect.set(
                max(0, ((here[0] - anchorHere[0]) * scale).toInt()),
                max(0, ((here[1] - anchorHere[1]) * scale).toInt()),
                0, 0
            )
            srcRect.right = min(bmp.width, srcRect.left + (w * scale).toInt().coerceAtLeast(1))
            srcRect.bottom = min(bmp.height, srcRect.top + (h * scale).toInt().coerceAtLeast(1))
            dstRect.set(0f, 0f, w, h)

            canvas.save()
            canvas.clipPath(outer)
            canvas.drawBitmap(bmp, srcRect, dstRect, backdropPaint)

            // 边缘透镜带：靠边一圈把背景略微放大重画，就是 iOS 那种边上看下去折射的感觉
            if (!cheap()) {
                val band = 9f * density
                val inner = r * 0.72f
                innerRect.set(band, band, w - band, h - band)
                ring.reset()
                ring.addRoundRect(outerRect, r, r, Path.Direction.CW)
                ring.addRoundRect(innerRect, inner, inner, Path.Direction.CW)
                ring.fillType = Path.FillType.INVERSE_EVEN_ODD
                canvas.save()
                canvas.clipPath(ring)
                val push = 1.09f
                canvas.scale(push, push, w / 2f, h / 2f)
                canvas.drawBitmap(bmp, srcRect, dstRect, lensPaint)
                canvas.restore()
            }

            // 手指位置的一团柔光
            if (press > 0.01f && touchX >= 0f) {
                val gr = max(w, h) * 0.6f
                glossPaint.shader = radial(touchX, touchY, gr)
                canvas.drawRect(0f, 0f, w, h, glossPaint)
                glossPaint.shader = null
            }
            canvas.restore()
        }

        // 玻璃底色：iOS 的玻璃不是纯透明，会压一层很淡的面
        tintPaint.color = blend(tintColor, 0.55f + 0.45f * press)
        canvas.drawPath(outer, tintPaint)

        // 上亮下暗的镜面渐变 + 一圈高光描边
        glossPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(argb(255 * 0.22f * rimStrength * (1f + press)), argb(255 * 0.04f * rimStrength), argb(0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(outer, glossPaint)
        glossPaint.shader = null

        rimPaint.strokeWidth = 0.9f * density
        rimPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(argb(255 * 0.75f * rimStrength), argb(255 * 0.18f * rimStrength)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(outer, rimPaint)
        rimPaint.shader = null

        // 下沿一道内阴影，让玻璃像浮在桌面上
        shadePaint.shader = LinearGradient(
            0f, h - 12f * density, 0f, h,
            intArrayOf(argb(0), argb(46)), null, Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.clipPath(outer)
        canvas.drawRect(0f, h - 12f * density, w, h, shadePaint)
        canvas.restore()
        shadePaint.shader = null

        super.onDraw(canvas)
    }

    private var backdropScale = 0.5f

    private fun backdrop(): Bitmap? {
        val act = context as? Activity ?: return null
        val pair = GlassBackdrop.blurred(act, blurDp)
        backdropScale = pair.second
        return pair.first
    }

    private val at = IntArray(2)

    private fun anchorLocation(): IntArray {
        val act = context as? Activity ?: return ZERO
        val a = GlassBackdrop.anchor(act) ?: return ZERO
        if (a === this) return ZERO
        a.getLocationInWindow(at)
        return at
    }

    private fun argb(v: Int) = (v and 0xFF) shl 24

    private fun argb(v: Float) = argb((v * 255f).toInt())

    private fun blend(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun radial(cx: Float, cy: Float, radius: Float): Shader =
        android.graphics.RadialGradient(
            cx, cy, radius,
            intArrayOf(argb(255 * 0.20f), argb(0)), null, Shader.TileMode.CLAMP
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = event.x
                touchY = event.y
                animatePress(1f, 90L, 0f)
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animatePress(0f, 260L, 0.18f)
                touchX = -1f
            }
        }
        return super.onTouchEvent(event)
    }

    /** 松手时带一点过冲，像被按下去的玻璃弹回来 */
    private fun animatePress(to: Float, duration: Long, overshoot: Float) {
        anim?.cancel()
        val from = press
        if (overshoot <= 0f) {
            anim = ValueAnimator.ofFloat(from, to).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { press = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            val peak = min(1f, to + overshoot)
            anim = ValueAnimator.ofFloat(from, peak, to).apply {
                this.duration = duration
                addUpdateListener { press = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    /** 允许代码里改玻璃强度（比如轻量档自动减模糊） */
    fun setBlurDp(value: Float) {
        blurDp = value
        invalidate()
    }

    private companion object {
        val ZERO = IntArray(2)
    }
}
