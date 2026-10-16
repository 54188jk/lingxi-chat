package com.lingxi.chat

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import com.lingxi.chat.data.ConfigStore
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

/**
 * 玻璃面板「身后那层」的采集与模糊缓存。
 * 只在页面空闲时（外部 post 的回调里）抓，绝不在 onDraw 里抓——
 * 抓的时候会把所有玻璃面板自己藏掉，否则每抓一次就把上一次的模糊结果再糊一层，越叠越脏。
 * 连着两次抓不动就认定这台机器不配套，自己退回半透明底，不再重复冒险。
 */
object GlassBackdrop {

    private const val SCALE = 0.3f
    private const val MAX_FAILS = 2

    private class State {
        var root: WeakReference<View>? = null
        var cap: Bitmap? = null
        var blur: Bitmap? = null
        var px: IntArray? = null
        var tmp: IntArray? = null
        var lastAt = 0L
        var fails = 0
        var capturing = false

        /** 这台机器上抓屏不可用，面板退回轻玻璃 */
        var dead = false
        /** 相机帧模式：身后那层由页面一帧帧喂进来（视图树里根本没有那些像素），绝不去重画视图树 */
        var frameMode = false
        /** 背景图相对宿主视图的缩放：相机帧也糊成和抓视图树一样的大小，面板换算逻辑一套通吃 */
        var frameScale = SCALE
        fun drop() {
            cap?.recycle(); cap = null
            blur?.recycle(); blur = null
            px = null; tmp = null
        }
    }

    private val states = HashMap<Int, State>()
    private val panels = ArrayList<WeakReference<View>>()

    fun attach(activity: Activity, root: View) {
        val st = states.getOrPut(activity.hashCode()) { State() }
        if (st.root?.get() !== root) {
            // 同一个 hashCode 上换了新页面，旧的图不能再用了
            st.drop()
            st.fails = 0
            st.dead = false
        }
        st.root = WeakReference(root)
        st.lastAt = 0L
    }

    /** 身后是相机画面这类「视图树里抓不到」的页面：把提供画面的那个视图交进来 */
    fun attachFrame(activity: Activity, source: View) {
        val st = states.getOrPut(activity.hashCode()) { State() }
        if (!st.frameMode || st.root?.get() !== source) {
            st.drop()
            st.fails = 0
            st.dead = false
        }
        st.frameMode = true
        st.root = WeakReference(source)
        st.lastAt = 0L
    }

    /** 喂一帧进去（只能在界面线程、onDraw 之外调用）：模糊的尺寸和抓视图树那套保持一致，相机帧再小也照这个尺寸糊 */
    fun frameFrom(activity: Activity, src: Bitmap) {
        val st = states[activity.hashCode()] ?: return
        val host = st.root?.get() ?: return
        if (st.dead || !st.frameMode || src.isRecycled || src.width <= 0 || host.width <= 0) return
        st.capturing = true
        try {
            val w = max(48, (host.width * SCALE).toInt())
            val h = max(48, min((host.height * SCALE).toInt(), (src.height * w / src.width)))
            ensureBuffers(st, w, h)
            val cap = st.cap ?: return
            val canvas = Canvas(cap)
            canvas.drawColor(Color.TRANSPARENT)
            canvas.drawBitmap(src, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), null)
            boxBlur(st, w, h)
            st.fails = 0
            st.lastAt = System.currentTimeMillis()
        } catch (t: Throwable) {
            st.fails++
            if (st.fails >= MAX_FAILS) giveUp(st, host)
        } finally {
            st.capturing = false
        }
    }
    /** 这一页的背景图是按什么缩放画的，面板贴回去时要用同一个比例 */
    fun scaleOf(activity: Activity): Float = states[activity.hashCode()]?.frameScale ?: SCALE

    fun detach(activity: Activity) {
        states.remove(activity.hashCode())?.drop()
        panels.removeAll {
            val p = it.get()
            p == null || p.context === activity
        }
    }

    /** 玻璃面板自己注册进来，抓快照时跳过它们 */
    fun addPanel(v: View) {
        if (panels.none { it.get() === v }) panels.add(WeakReference(v))
    }

    fun removePanel(v: View) {
        panels.removeAll { it.get() == null || it.get() === v }
    }

    /** 页面根视图，面板用它换算自己在快照里的位置 */
    fun rootOf(activity: Activity): View? = states[activity.hashCode()]?.root?.get()

    /** 抓一张并模糊；必须在 onDraw 之外调用 */
    fun refresh(activity: Activity, force: Boolean = false, minIntervalMs: Long = 110L) {
        val st = states[activity.hashCode()] ?: return
        if (st.dead || st.capturing || st.frameMode) return
        val now = System.currentTimeMillis()
        if (!force && now - st.lastAt < minIntervalMs) return
        val root = st.root?.get() ?: return
        if (root.width <= 0 || root.height <= 0) return
        if (!root.isAttachedToWindow || root.windowVisibility != View.VISIBLE) return
        val w = max(48, (root.width * SCALE).toInt())
        val h = max(48, (root.height * SCALE).toInt())
        st.capturing = true
        try {
            st.lastAt = now
            ensureBuffers(st, w, h)
            val bmp = st.cap ?: return
            val hidden = panels.mapNotNull { it.get() }.filter { it.visibility == View.VISIBLE }
            hidden.forEach { it.visibility = View.INVISIBLE }
            try {
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.TRANSPARENT)
                canvas.scale(SCALE, SCALE)
                root.draw(canvas)
            } finally {
                hidden.forEach { it.visibility = View.VISIBLE }
            }
            boxBlur(st, w, h)
            st.fails = 0
        } catch (t: Throwable) {
            st.fails++
            if (st.fails >= MAX_FAILS) giveUp(st, root)
        } finally {
            st.capturing = false
        }
    }

    /** 抓不动就别再抓了：本次退回半透明底，并把开关存回去，下次打开不再尝试 */
    private fun giveUp(st: State, root: View) {
        st.dead = true
        st.drop()
        runCatching { ConfigStore(root.context.applicationContext).liquidGlass = false }
    }

    private fun ensureBuffers(st: State, w: Int, h: Int) {
        val cap = st.cap
        if (cap == null || cap.isRecycled || cap.width != w || cap.height != h) {
            cap?.recycle()
            st.cap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val blur = st.blur
        if (blur == null || blur.isRecycled || blur.width != w || blur.height != h) {
            blur?.recycle()
            st.blur = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val n = w * h
        if (st.px == null || st.px?.size != n) st.px = IntArray(n)
        if (st.tmp == null || st.tmp?.size != n) st.tmp = IntArray(n)
    }

    /** 已经模糊好的整屏图；没抓过或本机不支持就是 null，面板自己退回半透明底 */
    fun snapshot(activity: Activity): Bitmap? {
        val st = states[activity.hashCode()] ?: return null
        val bmp = st.blur ?: return null
        return if (bmp.isRecycled || st.dead) null else bmp
    }

    fun scale(): Float = SCALE

    /** 两轮可分离盒模糊：在小图上做，观感等同高斯，代价低得多。原地复用缓冲区，不再每帧新建位图 */
    private fun boxBlur(st: State, w: Int, h: Int) {
        val src = st.cap ?: return
        val out = st.blur ?: return
        val px = st.px ?: return
        val tmp = st.tmp ?: return
        src.getPixels(px, 0, w, 0, 0, w, h)
        val r = max(2, min(w / 26, max(w, h) / 3))
        repeat(2) {
            blurRows(px, tmp, w, h, r)
            blurCols(tmp, px, w, h, r)
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun blurRows(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val base = y * w
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in 0..r) {
                val c = src[base + min(i + r, w - 1)]
                a += Color.alpha(c); rr += Color.red(c); g += Color.green(c); b += Color.blue(c)
            }
            for (x in 0 until w) {
                dst[base + x] = Color.argb(a / div, rr / div, g / div, b / div)
                val add = src[base + min(x + r + 1, w - 1)]
                val sub = src[base + max(x - r, 0)]
                a += Color.alpha(add) - Color.alpha(sub)
                rr += Color.red(add) - Color.red(sub)
                g += Color.green(add) - Color.green(sub)
                b += Color.blue(add) - Color.blue(sub)
            }
        }
    }

    private fun blurCols(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in 0..r) {
                val c = src[min(i + r, h - 1) * w + x]
                a += Color.alpha(c); rr += Color.red(c); g += Color.green(c); b += Color.blue(c)
            }
            for (y in 0 until h) {
                dst[y * w + x] = Color.argb(a / div, rr / div, g / div, b / div)
                val add = src[min(y + r + 1, h - 1) * w + x]
                val sub = src[max(y - r, 0) * w + x]
                a += Color.alpha(add) - Color.alpha(sub)
                rr += Color.red(add) - Color.red(sub)
                g += Color.green(add) - Color.green(sub)
                b += Color.blue(add) - Color.blue(sub)
            }
        }
    }
}
