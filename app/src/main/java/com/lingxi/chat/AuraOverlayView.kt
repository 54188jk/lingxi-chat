package com.lingxi.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.lingxi.chat.data.DevicePerf
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 手部特效的绘制核心：张开=指间一条星河在流动，握紧=整只手石化（灰白石面、裂纹、落灰）。
 * 特意写成「给谁画就在谁的 Canvas 上画」的普通类：界面上的浮层视图和录制片段的合成，用的是同一份代码，
 * 这样录出来的东西和屏幕上看到的对得上，不用维护两套。
 */
class AuraField(context: Context) {

    private val density = context.resources.displayMetrics.density
    private val cheap = DevicePerf.lowEnd(context)
    private val maxParticles = if (cheap) 150 else 460
    private val dustCount = if (cheap) 16 else 44

    private val pts = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ptsAdd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 发光要叠加不要互相遮挡：粒子这层用加法混合，密处才会亮成一团而不是一片糊
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stone = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dust = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seam = Path()
    private val measure = android.graphics.PathMeasure()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)

    private val hull = ArrayList<FloatArray>()
    private val shape = Path()
    private val band = Path()
    private val box = RectF()
    private val hullIn = ArrayList<FloatArray>(21)
    private val px = FloatArray(21)
    private val py = FloatArray(21)

    /** 21 个关键点，归一化到画面（x 右、y 下）；没有手时为 null */
    private var marks: FloatArray? = null
    private var spread = 0f          // 0 握紧 → 1 张到最大
    private var shown = 0f           // 特效强度，出现/消失都做缓动，别一闪一闪

    private class P(var x: Float, var y: Float, var vx: Float, var vy: Float,
                    var life: Float, var size: Float, var tone: Int)

    private val pool = ArrayList<P>(maxParticles)
    private val ashes = ArrayList<P>(60)

    /** 每次识别到手就喂进来：marks 是 21×2 的归一化坐标 */
    fun update(m: FloatArray?, openness: Float, nowMs: Long) {
        marks = m
        spread = openness.coerceIn(0f, 1f)
        if (m == null) shown = max(0f, shown - 0.12f)
        else shown = min(1f, shown + 0.16f)
    }

    /** 手指张合程度：指尖到腕的距离，除以指根到腕的距离。张开约 1.7-2.0，握紧约 0.7-0.9 */
    fun openness(m: FloatArray): Float {
        if (m.size < 42) return 0f
        val wx = m[0]; val wy = m[1]
        var sum = 0f
        var n = 0
        for (tip in intArrayOf(4, 8, 12, 16, 20)) {
            val kn = if (tip == 4) 2 else tip - 3
            val dTip = hypot(m[tip * 2] - wx, m[tip * 2 + 1] - wy)
            val dBase = hypot(m[kn * 2] - wx, m[kn * 2 + 1] - wy)
            if (dBase > 1e-4f) { sum += dTip / dBase; n++ }
        }
        if (n == 0) return 0f
        return ((sum / n) - 0.95f) / 0.62f
    }

    fun draw(canvas: Canvas, w: Int, h: Int, nowMs: Long) {
        if (shown <= 0.01f) return
        val m = marks ?: return
        if (m.size < 42) return
        val t = nowMs / 1000f
        val tips = intArrayOf(4, 8, 12, 16, 20)
        for (i in 0 until 21) { px[i] = m[i * 2] * w; py[i] = m[i * 2 + 1] * h }
        val scale = hypot(px[9] - px[5], py[9] - py[5]).coerceAtLeast(1f)
        val open = shown * spread
        val closed = shown * (1f - spread)
        if (open > 0.04f) drawSea(canvas, px, py, tips, scale, open, t, w, h)
        if (closed > 0.04f) drawStone(canvas, px, py, closed, scale, t, h)
        drawDust(canvas, px, py, closed, h, nowMs)
    }

    /** 指间的星河：宽晕 + 中段 + 亮芯三层叠出厚度，再撒一层沿光带流动的星尘，最后指尖各一颗星芒 */
    private fun drawSea(
        canvas: Canvas, px: FloatArray, py: FloatArray, tips: IntArray,
        scale: Float, power: Float, t: Float, w: Int, h: Int
    ) {
        band.reset()
        val ptsX = FloatArray(tips.size + 2)
        val ptsY = FloatArray(tips.size + 2)
        ptsX[0] = px[0]; ptsY[0] = py[0]
        tips.forEachIndexed { i, k -> ptsX[i + 1] = px[k]; ptsY[i + 1] = py[k] }
        ptsX[tips.size + 1] = px[0]; ptsY[tips.size + 1] = py[0]
        // 相邻指尖之间往下坠一点的悬链，看着像一条挂在指间的星河
        band.moveTo(ptsX[0], ptsY[0])
        for (i in 0 until ptsX.size - 1) {
            val ax = ptsX[i]; val ay = ptsY[i]
            val bx = ptsX[i + 1]; val by = ptsY[i + 1]
            val sag = scale * (0.30f + 0.12f * sin(t * 1.7f + i))
            val mx = (ax + bx) / 2f
            val my = (ay + by) / 2f + sag
            band.quadTo(mx, my, bx, by)
        }
        val pal = intArrayOf(argb(0x8FD4FF, 0f), argb(0xFFE6F4, 0.62f * power), argb(0xFFC9E8, 0.5f * power), argb(0xFFE2AE, 0f))
        // 最外一圈是晕，负责「亮到周围空气里」
        halo.strokeWidth = scale * 0.20f
        halo.shader = LinearGradient(px[4], py[4], px[20], py[20], pal, null, Shader.TileMode.CLAMP)
        halo.alpha = (70 * power).toInt().coerceIn(0, 255)
        canvas.drawPath(band, halo)
        // 中段是主体，饱和度略高，读起来才是一条河而不是一团雾
        line.strokeWidth = scale * 0.075f
        line.shader = LinearGradient(
            px[4], py[4], px[20], py[20],
            intArrayOf(argb(0x9BE0FF, 0.15f), argb(0xFFFFFF, 0.85f * power), argb(0xFFB8E6, 0.7f * power), argb(0xFFD9A8, 0.15f)),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        line.alpha = (225 * power).toInt().coerceIn(0, 255)
        canvas.drawPath(band, line)
        halo.shader = null
        line.shader = null
        // 最里一圈白芯，加法混合：叠到星尘上会真的过曝，这才是「亮」
        core.strokeWidth = scale * 0.024f
        core.color = argb(0xFFFFFF, 0.8f * power)
        canvas.drawPath(band, core)

        // 星尘：沿光带等距打点，按序号取伪随机，尺寸明暗各异，整体缓慢流动
        measure.setPath(band, false)
        val len = measure.length
        if (len > 8f) {
            val step = len / dustCount
            val flow = (t * scale * 0.35f) % step
            var i = 0
            while (i < dustCount) {
                val d = i * step + flow
                if (d >= len) break
                measure.getPosTan(d, pos, tan)
                val r1 = hash(i * 7 + 3)
                val r2 = hash(i * 13 + 11)
                val rr = scale * (0.008f + 0.026f * r1)
                val aa = (255 * power * (0.30f + 0.62f * r2)).toInt().coerceIn(0, 255)
                val tone = intArrayOf(0xFFFFFF, 0xBFE8FF, 0xFFD6EC, 0xFFE9BE)[(i and 3)]
                ptsAdd.color = (aa shl 24) or (tone and 0x00FFFFFF)
                canvas.drawCircle(pos[0], pos[1], rr, ptsAdd)
                // 一小半星尘带十字光刺，星河里那些「特别亮的」就是这么来的
                if (r2 > 0.78f) {
                    val arm = rr * 3.4f
                    canvas.drawLine(pos[0] - arm, pos[1], pos[0] + arm, pos[1], core)
                    canvas.drawLine(pos[0], pos[1] - arm, pos[0], pos[1] + arm, core)
                }
                i++
            }
        }

        // 补给粒子：数量按档位收，低机器少放
        val want = (maxParticles * 0.6f * power).toInt()
        while (pool.size < want) pool.add(spawn(px, py, tips, scale))
        val dt = 1f / 60f
        // 按下标走：边遍历边往列表尾部塞新粒子会让 ArrayList 抛并发修改异常，一帧就闪退
        var idx = 0
        while (idx < pool.size) {
            val p = pool[idx]
            p.life -= dt / (2.2f + p.size / 6f)
            if (p.life <= 0f) {
                if (pool.size > want) { pool.removeAt(idx); continue }
                pool[idx] = spawn(px, py, tips, scale)
                continue
            }
            idx++
            // 一点绕手感：光点不是直往下掉，而是被指缝的气流带着旋
            p.vx += -p.vy * 0.012f
            p.vy += p.vx * 0.012f
            p.x += p.vx * dt
            p.y += p.vy * dt + 5f * density * dt * power
            p.vx *= 0.988f
            p.vy = p.vy * 0.988f + 2.4f * density * dt
            val a = (255 * power * min(1f, p.life * 1.5f)).toInt().coerceIn(0, 255)
            if (a <= 6) continue
            ptsAdd.color = (a shl 24) or (p.tone and 0x00FFFFFF)
            canvas.drawCircle(p.x, p.y, p.size * density * (0.5f + 0.5f * p.life), ptsAdd)
            pts.color = ((a / 2) shl 24) or (0xFFFFFF and 0x00FFFFFF)
            canvas.drawCircle(p.x, p.y, p.size * density * 0.30f, pts)
        }

        // 指尖星芒：一圈大柔光 + 一圈暖内核 + 四道会转的光刺
        for (k in tips) {
            val r = scale * 0.22f
            glow.shader = RadialGradient(
                px[k], py[k], r,
                intArrayOf(argb(0xFFFFFF, 0.95f * power), argb(0xFFD8EE, 0.45f * power), argb(0x9BD8FF, 0.18f * power), Color.TRANSPARENT),
                floatArrayOf(0f, 0.28f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(px[k], py[k], r, glow)
            glow.shader = null
            glow.color = argb(0xFFF6FF, 0.85f * power)
            canvas.drawCircle(px[k], py[k], scale * 0.035f, glow)
            star(canvas, px[k], py[k], scale * 0.15f, power, t + k)
        }
    }

    /** 稳定伪随机：同一个序号每次给同一个数，星尘才不会每帧乱跳 */
    private fun hash(seed: Int): Float {
        val v = sin(seed * 12.9898f) * 43758.5453f
        return v - floor(v)
    }

    private fun spawn(px: FloatArray, py: FloatArray, tips: IntArray, scale: Float): P {
        val i = Random.nextInt(tips.size)
        val k1 = tips[i]; val k2 = tips[(i + 1) % tips.size]
        val f = Random.nextFloat()
        val x = px[k1] + (px[k2] - px[k1]) * f
        val y = py[k1] + (py[k2] - py[k1]) * f + scale * 0.16f * sin(f * Math.PI.toFloat())
        // 偏冷的一档加偏暖的一档混着来，纯白纯蓝都会显得薄
        val tones = intArrayOf(0xFFFFFF, 0xDFF2FF, 0xFFE1F2, 0xFFEFC4, 0xA8E4FF, 0xFFC9E0)
        return P(
            x, y,
            (Random.nextFloat() - 0.5f) * scale * 1.15f,
            (Random.nextFloat() - 0.72f) * scale * 0.85f,
            0.9f + Random.nextFloat() * 1.5f,
            1.2f + Random.nextFloat() * 3.1f,
            tones[Random.nextInt(tones.size)]
        )
    }

    private fun star(canvas: Canvas, cx: Float, cy: Float, r: Float, power: Float, t: Float) {
        val spin = t * 0.6f
        // 光刺用加法混合，压在本芯上才会「过曝」，用普通混合就只是画了四道白线
        line.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
        line.strokeWidth = max(1f, r * 0.14f)
        line.alpha = (240 * power).toInt().coerceIn(0, 255)
        line.color = argb(0xFFFFFF, 0.95f * power)
        for (a in 0 until 4) {
            val ang = spin + a * Math.PI.toFloat() / 2f
            val len = r * (if (a % 2 == 0) 1f else 0.55f)
            canvas.drawLine(cx, cy, cx + cos(ang) * len, cy + sin(ang) * len, line)
        }
        // 斜着再来四道短的，星芒才不呆
        line.strokeWidth = max(1f, r * 0.09f)
        line.alpha = (150 * power).toInt().coerceIn(0, 255)
        for (a in 0 until 4) {
            val ang = spin + Math.PI.toFloat() / 4f + a * Math.PI.toFloat() / 2f
            val len = r * 0.44f
            canvas.drawLine(cx, cy, cx + cos(ang) * len, cy + sin(ang) * len, line)
        }
        line.xfermode = null
    }

    /** 握拳：把手的轮廓糊成一块石头，加深灰渐变、裂纹和边缘的碎屑 */
    private fun drawStone(canvas: Canvas, px: FloatArray, py: FloatArray, power: Float, scale: Float, t: Float, h: Int) {
        while (hullIn.size < 21) hullIn.add(FloatArray(2))
        for (i in 0 until 21) { hullIn[i][0] = px[i]; hullIn[i][1] = py[i] }
        hull.clear()
        hull.addAll(convexHull(hullIn))
        if (hull.size < 3) return
        shape.reset()
        shape.moveTo(hull[0][0], hull[0][1])
        for (i in 1 until hull.size) shape.lineTo(hull[i][0], hull[i][1])
        shape.close()
        box.set(hull[0][0], hull[0][1], hull[0][0], hull[0][1])
        hull.forEach { box.left = min(box.left, it[0]); box.top = min(box.top, it[1]); box.right = max(box.right, it[0]); box.bottom = max(box.bottom, it[1]) }
        val pad = scale * 0.10f
        box.inset(-pad, -pad)

        stone.shader = LinearGradient(
            box.left, box.top, box.right, box.bottom,
            intArrayOf(argb(0xB9BCC2, power), argb(0x6E7076, power), argb(0x4A4C51, power)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawPath(shape, stone)
        stone.shader = null
        // 石面上的浅斑：几团半透的椭圆，比纯色像石头得多
        for (i in 0 until 6) {
            val fx = box.left + box.width() * (0.18f + 0.13f * i)
            val fy = box.top + box.height() * (0.25f + 0.11f * (i % 3))
            sp.color = argb(if (i % 2 == 0) 0xCFCFD6 else 0x54565C, 0.30f * power)
            canvas.save()
            canvas.translate(fx, fy)
            canvas.rotate(i * 24f)
            canvas.drawOval(-scale * 0.16f, -scale * 0.09f, scale * 0.16f, scale * 0.09f, sp)
            canvas.restore()
        }
        // 裂纹：从掌心往外放射三条主缝，再各带一条支缝
        crack.strokeWidth = max(1.2f, scale * 0.012f)
        crack.color = argb(0x24262B, 0.85f * power)
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f
        for (i in 0 until 3) {
            val ang = i * 2.1f + 0.4f + 0.05f * sin(t + i)
            val ex = cx + cos(ang) * box.width() * 0.45f
            val ey = cy + sin(ang) * box.height() * 0.45f
            val mx = (cx + ex) / 2f + sin(ang) * scale * 0.05f
            val my = (cy + ey) / 2f - cos(ang) * scale * 0.05f
            val p = seam
            p.reset()
            p.moveTo(cx, cy); p.quadTo(mx, my, ex, ey)
            p.moveTo(mx, my)
            p.lineTo(mx + cos(ang + 1.1f) * scale * 0.12f, my + sin(ang + 1.1f) * scale * 0.12f)
            canvas.drawPath(p, crack)
        }
        // 轮廓上一圈高光，石头才不从背景里糊掉
        crack.strokeWidth = max(1f, scale * 0.01f)
        crack.color = argb(0xE6E8EE, 0.35f * power)
        canvas.drawPath(shape, crack)
    }

    /** 石化时从指缝往下掉的碎屑，坐标一律用画面像素 */
    private fun drawDust(canvas: Canvas, px: FloatArray, py: FloatArray, power: Float, h: Int, nowMs: Long) {
        val dt = 1f / 60f
        if (power > 0.12f && ashes.size < 40 && !cheap) {
            val i = intArrayOf(4, 8, 12, 16).random()
            ashes.add(
                P(
                    px[i] + (Random.nextFloat() - 0.5f) * 12f * density,
                    py[i],
                    (Random.nextFloat() - 0.5f) * 30f * density,
                    20f * density,
                    0.7f + Random.nextFloat() * 0.6f,
                    1f + Random.nextFloat() * 1.8f,
                    0x9FA3AB
                )
            )
        }
        val it = ashes.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += 900f * density * dt
            if (p.life <= 0f || p.y > h) { it.remove(); continue }
            val a = (200 * p.life).toInt().coerceIn(0, 255)
            dust.color = (a shl 24) or (p.tone and 0x00FFFFFF)
            canvas.drawCircle(p.x, p.y, p.size * density, dust)
        }
    }

    private fun argb(rgb: Int, a: Float): Int {
        val al = (255 * a.coerceIn(0f, 1f)).toInt()
        return (al shl 24) or (rgb and 0x00FFFFFF)
    }

    private fun convexHull(input: List<FloatArray>): List<FloatArray> {
        val ps = input.sortedWith(compareBy({ it[0] }, { it[1] }))
        if (ps.size < 3) return ps
        fun cross(o: FloatArray, a: FloatArray, b: FloatArray) =
            (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lower = ArrayList<FloatArray>()
        for (p in ps) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0f) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = ArrayList<FloatArray>()
        for (i in ps.indices.reversed()) {
            val p = ps[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0f) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        upper.removeAt(upper.size - 1); lower.removeAt(lower.size - 1)
        lower.addAll(upper)
        return lower
    }
}

/**
 * 屏幕上的浮层：只管按节拍刷新，画的东西交给 AuraField。
 * 系统关掉动画或轻量档时降频，别为了好看把电放光。
 */
class AuraOverlayView @JvmOverloads constructor(
    context: Context, attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    val field = AuraField(context)
    private var running = false
    private var lastInk = 0L
    private val beat = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            if (now - lastInk >= DevicePerf.frameMs(context)) {
                lastInk = now
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    init {
        setWillNotDraw(false)
    }

    fun start() {
        if (running) return
        running = true
        postOnAnimation(beat)
    }

    fun stop() {
        running = false
        removeCallbacks(beat)
    }

    override fun onDraw(canvas: Canvas) {
        field.draw(canvas, width, height, System.currentTimeMillis())
    }

    /** 浮层和摄像头画面铺满同一块区域，归一化关键点可以直接乘宽高 */
    fun feed(marks: FloatArray?, openness: Float) {
        field.update(marks, openness, System.currentTimeMillis())
    }
}
