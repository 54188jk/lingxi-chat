package com.lingxi.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lingxi.chat.data.DevicePerf
import kotlin.math.max
import kotlin.math.min

/**
 * iOS 味道的分段切换器，底子和聊天页顶栏/输入栏是同一块液态玻璃。
 * 选中的那一格用一块会滑的圆牌标出来，不是只把字变个色——隔着玻璃也得一眼看出现在在哪一段。
 * 段与段之间按宽度平分，文字在代码里加，不在 XML 里堆，免得改一个字就要动布局。
 */
class SegmentBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GlassSurfaceView(context, attrs) {

    private val density = resources.displayMetrics.density
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pillPath = Path()
    private val pillRect = RectF()
    private val labels = ArrayList<TextView>()
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val pillFill = ContextCompat.getColor(context, R.color.chip_active_bg)
    private val pillStroke = ContextCompat.getColor(context, R.color.accent_soft)
    private val onColor = accent
    private val offColor = ContextCompat.getColor(context, R.color.text_secondary)

    private var index = 0
    private var pos = 0f
    private var slide: ValueAnimator? = null

    /** 选完之后的回调，参数是段号 */
    var onPick: ((Int) -> Unit)? = null

    init {
        clipChildren = false
    }

    fun setItems(items: List<String>) {
        removeAllViews()
        labels.clear()
        items.forEachIndexed { i, text ->
            val tv = TextView(context).apply {
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                textSize = 13f
                setPadding(0, (10f * density).toInt(), 0, (10f * density).toInt())
                setText(items[i])
                letterSpacing = -0.01f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            labels.add(tv)
            addView(tv)
        }
        applyColors()
    }

    /** 外部也能把界面切到某一段（比如特效页退出后自动回第一段的 AI） */
    fun select(i: Int, animate: Boolean = true) {
        val n = labels.size
        if (n == 0) return
        index = i.coerceIn(0, n - 1)
        if (animate && !DevicePerf.lowEnd(context)) {
            slide?.cancel()
            slide = ValueAnimator.ofFloat(pos, index.toFloat()).apply {
                duration = 220L
                addUpdateListener {
                    pos = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            pos = index.toFloat()
            invalidate()
        }
        applyColors()
    }

    fun current() = index

    private fun applyColors() {
        labels.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == index) onColor else offColor)
            tv.setTypeface(if (i == index) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        drawPill(canvas)
        super.dispatchDraw(canvas)
    }

    private fun drawPill(canvas: Canvas) {
        val n = labels.size
        if (n == 0) return
        val cell = width / n.toFloat()
        val inset = 3f * density
        val r = 15f * density
        pillRect.set(cell * pos + inset, inset, cell * (pos + 1f) - inset, height - inset)
        if (pillRect.width() <= 0f || pillRect.height() <= 0f) return
        pillPath.reset()
        pillPath.addRoundRect(pillRect, r, r, Path.Direction.CW)
        // 苹果那种「染色胶囊」：底色是半透的主色，字是主色，比一整块实心更贴玻璃
        pillPaint.color = pillFill
        canvas.drawPath(pillPath, pillPaint)
        pillEdge.strokeWidth = 1f * density
        pillEdge.color = pillStroke
        canvas.drawPath(pillPath, pillEdge)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (labels.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                alpha = 0.88f
                return true
            }
            MotionEvent.ACTION_UP -> {
                alpha = 1f
                val cell = max(1f, width / labels.size.toFloat())
                val i = min(labels.size - 1, max(0, (event.x / cell).toInt()))
                select(i)
                onPick?.invoke(i)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                alpha = 1f
            }
        }
        return super.onTouchEvent(event)
    }
}
