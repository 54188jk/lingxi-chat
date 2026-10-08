package com.lingxi.chat.data

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference

/**
 * 机型档位判断：老旧机器少画点、画慢点，别为了好看把 CPU 占满。
 * 只看有没有「明显会卡」的信号（低内存档、核少、可用堆小），命中就走轻量档。
 */
object DevicePerf {

    @Volatile
    private var cached: Boolean? = null

    fun lowEnd(ctx: Context): Boolean {
        cached?.let { return it }
        val am = ctx.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val heapMb = Runtime.getRuntime().maxMemory() / 1048576L
        val cores = Runtime.getRuntime().availableProcessors()
        val low = am?.isLowRamDevice == true || heapMb <= 128L || cores <= 4
        cached = low
        return low
    }

    /** 一次重绘的最小间隔：正常约 22fps，轻量档约 11fps，肉眼看不出但省电一半 */
    fun frameMs(ctx: Context): Long = if (lowEnd(ctx)) 92L else 46L

    /** 下载时随机挑样式用的白名单：轻量档不选绘制最重的那几款 */
    fun heavyFreeNames(): Set<String> = setOf("普通横条", "像素方块", "电池充电", "流星拉尾", "圆环表盘")
}

/**
 * 全局只跑一条时间轴，所有花样进度条挂在它上面。
 * 之前每一款各自开一个无限动画，预览页十几款同时在跑就会明显掉帧；
 * 现在统一按档位间隔刷新，并且只刷新真正露在屏幕上的那几个。
 */
internal object ProgressFrameClock {

    private class Sub(
        val view: WeakReference<View>,
        val periodMs: Long,
        val onPhase: (Float) -> Unit
    )

    private val subs = ArrayList<Sub>()
    private val handler = Handler(Looper.getMainLooper())
    private val rect = android.graphics.Rect()
    private var running = false
    private var intervalMs = 46L

    private val beat = object : Runnable {
        override fun run() {
            if (subs.isEmpty()) {
                running = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val snapshot = synchronized(subs) { ArrayList(subs) }
            var alive = 0
            snapshot.forEach { s ->
                val v = s.view.get()
                if (v == null) {
                    synchronized(subs) { subs.remove(s) }
                    return@forEach
                }
                alive++
                if (v.visibility != View.VISIBLE || !v.isShown) return@forEach
                // 滚动列表里没露出来的行不重绘，省下的都是真帧数
                if (!v.getGlobalVisibleRect(rect) || rect.height() <= 0) return@forEach
                val phase = ((now % s.periodMs).toFloat() / s.periodMs) * (2.0 * Math.PI).toFloat()
                s.onPhase(phase)
                v.invalidate()
            }
            if (alive == 0) {
                running = false
                return
            }
            handler.postDelayed(this, intervalMs)
        }
    }

    fun register(view: View, periodMs: Long, onPhase: (Float) -> Unit) {
        synchronized(subs) { subs.add(Sub(WeakReference(view), periodMs, onPhase)) }
        intervalMs = minOf(intervalMs, DevicePerf.frameMs(view.context))
        start()
    }

    fun unregister(view: View) {
        synchronized(subs) { subs.removeAll { it.view.get() === view } }
    }

    fun clear(container: ViewGroup) {
        synchronized(subs) { subs.removeAll { s -> s.view.get()?.let { isInside(container, it) } == true } }
    }

    private fun isInside(parent: ViewGroup, child: View): Boolean {
        var p: android.view.ViewParent? = child.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    private fun start() {
        if (running) return
        running = true
        handler.post(beat)
    }
}
