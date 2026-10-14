package com.lingxi.chat

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 玻璃面板「背后那层」的采集与模糊缓存。
 * 把指定的几个源视图（桌面的壁纸层）抓成半尺寸小图，按需模糊几档并缓存复用：
 * 面板每帧只是贴一张已经模糊好的小图，老机器也不会掉帧。
 */
object GlassBackdrop {

    private class State {
        var sources: List<Weak> = emptyList()
        var raw: Bitmap? = null
        val blurred = HashMap<Int, Bitmap>()
        var scale = 0.5f
    }

    private class Weak(val ref: java.lang.ref.WeakReference<View>) {
        val view: View? get() = ref.get()
    }

    private val states = HashMap<Int, State>()

    /** 页面里哪些视图属于「玻璃背后的东西」。换了壁纸或尺寸变化后调 invalidate。 */
    fun attach(activity: Activity, sources: List<View>) {
        val st = states.getOrPut(activity.hashCode()) { State() }
        st.sources = sources.map { Weak(java.lang.ref.WeakReference(it)) }
        invalidate(activity)
    }

    fun detach(activity: Activity) {
        states.remove(activity.hashCode())?.let { st ->
            st.raw?.recycle()
            st.blurred.values.forEach { it.recycle() }
            st.blurred.clear()
        }
    }

    fun invalidate(activity: Activity) {
        val st = states[activity.hashCode()] ?: return
        st.blurred.values.forEach { it.recycle() }
        st.blurred.clear()
        st.raw?.recycle()
        st.raw = null
    }

    /** 面板取背景：返回已模糊的小图与它的缩放比（用来把面板坐标换算成图上坐标） */
    fun blurred(activity: Activity, radiusDp: Float): Pair<Bitmap?, Float> {
        val st = states[activity.hashCode()] ?: return null to 1f
        val key = max(2, radiusDp.toInt())
        st.blurred[key]?.let { if (!it.isRecycled) return it to st.scale }
        val raw = st.raw?.takeIf { !it.isRecycled } ?: capture(st) ?: return null to st.scale
        val made = boxBlur(raw, max(2, (key * raw.width / 420f).toInt()))
        st.blurred[key] = made
        return made to st.scale
    }

    fun sharp(activity: Activity): Pair<Bitmap?, Float> {
        val st = states[activity.hashCode()] ?: return null to 1f
        val raw = st.raw?.takeIf { !it.isRecycled } ?: capture(st) ?: return null to st.scale
        return raw to st.scale
    }

    /** 参考视图取第一个源，用它的位置当坐标原点，面板再按相对位置裁自己的那块 */
    fun anchor(activity: Activity): View? = states[activity.hashCode()]?.sources?.firstNotNullOfOrNull { it.view }

    private fun capture(st: State): Bitmap? {
        val live = st.sources.mapNotNull { it.view }.filter { it.width > 0 && it.height > 0 }
        if (live.isEmpty()) return null
        val first = live[0]
        val sw = max(48, (first.width * st.scale).toInt())
        val sh = max(48, (first.height * st.scale).toInt())
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        st.raw = bmp
        val canvas = Canvas(bmp)
        val base = IntArray(2)
        first.getLocationInWindow(base)
        for (v in live) {
            val p = IntArray(2)
            v.getLocationInWindow(p)
            canvas.save()
            canvas.translate((p[0] - base[0]) * st.scale, (p[1] - base[1]) * st.scale)
            canvas.scale(st.scale, st.scale)
            v.draw(canvas)
            canvas.restore()
        }
        return bmp
    }

    /** 两轮可分离盒模糊：在小图上做，成本很低，视觉上和高斯基本一致 */
    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, false)
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        val r = max(2, min(radius, max(w, h) / 3))
        repeat(2) {
            passHorizontal(px, w, h, r)
            passVertical(px, w, h, r)
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun passHorizontal(px: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        var y = 0
        while (y < h) {
            val base = y * w
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in 0..r) {
                val c = px[base + min(i + r, w - 1)]
                a += Color.alpha(c); rr += Color.red(c); g += Color.green(c); b += Color.blue(c)
            }
            var x = 0
            while (x < w) {
                px[base + x] = Color.argb(a / div, rr / div, g / div, b / div)
                if (x + r + 1 < w) {
                    val add = px[base + min(x + r + 1, w - 1)]
                    val sub = px[base + max(x - r, 0)]
                    a += Color.alpha(add) - Color.alpha(sub)
                    rr += Color.red(add) - Color.red(sub)
                    g += Color.green(add) - Color.green(sub)
                    b += Color.blue(add) - Color.blue(sub)
                }
                x++
            }
            y++
        }
    }

    private fun passVertical(px: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        var x = 0
        while (x < w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in 0..r) {
                val c = px[min(i + r, h - 1) * w + x]
                a += Color.alpha(c); rr += Color.red(c); g += Color.green(c); b += Color.blue(c)
            }
            var y = 0
            while (y < h) {
                px[y * w + x] = Color.argb(a / div, rr / div, g / div, b / div)
                val add = px[min(y + r + 1, h - 1) * w + x]
                val sub = px[max(y - r, 0) * w + x]
                a += Color.alpha(add) - Color.alpha(sub)
                rr += Color.red(add) - Color.red(sub)
                g += Color.green(add) - Color.green(sub)
                b += Color.blue(add) - Color.blue(sub)
                y++
            }
            x++
        }
    }
}
