package com.lingxi.chat

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

/**
 * 玻璃面板「身后那层」的采集与模糊缓存。
 * 抓的是整个页面（壁纸 + 已经画好的列表内容），抓之前把所有玻璃面板自己隐藏掉，
 * 否则每刷新一次就把上一次的模糊结果再糊一层，会越叠越脏。
 */
object GlassBackdrop {

    private const val SCALE = 0.3f

    private class State {
        var root: WeakReference<View>? = null
        var blur: Bitmap? = null
        var lastAt = 0L
    }

    private val states = HashMap<Int, State>()
    private val panels = ArrayList<WeakReference<View>>()

    fun attach(activity: Activity, root: View) {
        val st = states.getOrPut(activity.hashCode()) { State() }
        st.root = WeakReference(root)
        st.blur?.recycle()
        st.blur = null
        st.lastAt = 0L
    }

    fun detach(activity: Activity) {
        states.remove(activity.hashCode())?.let { it.blur?.recycle() }
        panels.clear()
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

    /** 抓一张并模糊；minIntervalMs 控制刷新频率，滚动时不要每帧都抓 */
    fun refresh(activity: Activity, force: Boolean = false, minIntervalMs: Long = 110L) {
        val st = states[activity.hashCode()] ?: return
        val now = System.currentTimeMillis()
        if (!force && now - st.lastAt < minIntervalMs) return
        val root = st.root?.get() ?: return
        if (root.width <= 0 || root.height <= 0) return
        val w = max(48, (root.width * SCALE).toInt())
        val h = max(48, (root.height * SCALE).toInt())
        val bmp = st.blur?.takeIf { it.width == w && it.height == h && !it.isRecycled }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { st.blur = it }
        val hidden = panels.mapNotNull { it.get() }.filter { it.visibility == View.VISIBLE }
        hidden.forEach { it.visibility = View.INVISIBLE }
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        canvas.scale(SCALE, SCALE)
        runCatching { root.draw(canvas) }
        hidden.forEach { it.visibility = View.VISIBLE }
        st.blur = boxBlur(bmp, max(4, w / 26))
        st.lastAt = now
    }

    /** 面板取已经模糊好的整屏图；没有就顺手补抓一张 */
    fun snapshot(activity: Activity): Bitmap? {
        val st = states[activity.hashCode()] ?: return null
        if (st.blur == null || st.blur?.isRecycled == true) refresh(activity, force = true)
        return st.blur
    }

    fun scale(): Float = SCALE

    /** 两轮可分离盒模糊：在小图上做，观感等同高斯，代价低得多 */
    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, false)
            ?: return src
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
        for (y in 0 until h) {
            val base = y * w
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in 0..r) {
                val c = px[base + min(i + r, w - 1)]
                a += Color.alpha(c); rr += Color.red(c); g += Color.green(c); b += Color.blue(c)
            }
            for (x in 0 until w) {
                px[base + x] = Color.argb(a / div, rr / div, g / div, b / div)
                val add = px[base + min(x + r + 1, w - 1)]
                val sub = px[base + max(x - r, 0)]
                a += Color.alpha(add) - Color.alpha(sub)
                rr += Color.red(add) - Color.red(sub)
                g += Color.green(add) - Color.green(sub)
                b += Color.blue(add) - Color.blue(sub)
            }
        }
    }

    private fun passVertical(px: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in 0..r) {
                val c = px[min(i + r, h - 1) * w + x]
                a += Color.alpha(c); rr += Color.red(c); g += Color.green(c); b += Color.blue(c)
            }
            for (y in 0 until h) {
                px[y * w + x] = Color.argb(a / div, rr / div, g / div, b / div)
                val add = px[min(y + r + 1, h - 1) * w + x]
                val sub = px[max(y - r, 0) * w + x]
                a += Color.alpha(add) - Color.alpha(sub)
                rr += Color.red(add) - Color.red(sub)
                g += Color.green(add) - Color.green(sub)
                b += Color.blue(add) - Color.blue(sub)
            }
        }
    }
}
