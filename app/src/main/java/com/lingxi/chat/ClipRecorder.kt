package com.lingxi.chat

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.max

/**
 * 把「摄像头画面 + 特效」合成出来的每一帧编成 MP4。
 * 不走系统的录像通道：那条路只录摄像头本身，录不到我们叠上去的星和石头。
 */
class ClipRecorder {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var plane = ByteArray(0)
    private var rgba = IntArray(0)
    private var started = false
    private var frames = 0
    private var w = 0
    private var h = 0
    private var fps = 0

    val info = MediaCodec.BufferInfo()

    fun start(dst: File, width: Int, height: Int, fps: Int, bitrateKbps: Int = 6000) {
        stop()
        w = width and 1.inv()
        h = height and 1.inv()
        this.fps = fps
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            )
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(fmt, null as android.view.Surface?, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        plane = ByteArray(w * h * 3 / 2)
        rgba = IntArray(w * h)
        started = true
        frames = 0
    }

    /**
     * 收一帧 RGBA 合成图，转换后交给编码器。
     * 时间戳按「真正送进去的第几帧」自己算：抓帧偶尔会丢几帧（编码器满了），
     * 让外面按墙上时钟给的戳会让片子忽快忽慢，丢的帧还会把时间轴撑出空洞。
     */
    fun push(src: Bitmap) {
        val c = codec ?: return
        if (!started) return
        val bmp = if (src.width == w && src.height == h) src
        else Bitmap.createScaledBitmap(src, w, h, true)
        toYuv(bmp, plane)
        if (bmp !== src) runCatching { bmp.recycle() }
        val idx = c.dequeueInputBuffer(4_000)
        if (idx >= 0) {
            val ib = c.getInputBuffer(idx) ?: return
            ib.clear()
            ib.put(plane)
            c.queueInputBuffer(idx, 0, plane.size, frames * 1_000_000L / max(1, fps), 0)
            frames++
        }
        drain(false)
    }

    /** 收尾；返回 false 表示这份文件压根没写成（没拿到轨道或没进过一帧），调用方别把它当成品交付 */
    fun stop(): Boolean {
        val c = codec
        if (c != null && started) {
            val idx = c.dequeueInputBuffer(2_000)
            if (idx >= 0) c.queueInputBuffer(idx, 0, 0, frames * 1_000_000L / max(1, fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            runCatching { c.stop() }
        }
        val wrote = track >= 0 && frames > 0
        val m = muxer
        if (m != null) {
            runCatching { if (track >= 0) m.stop() }.onFailure { return false }
            runCatching { m.release() }
        }
        runCatching { c?.release() }
        codec = null; muxer = null; track = -1; started = false; frames = 0
        return wrote && m != null
    }

    fun busy() = started

    private fun drain(final: Boolean) {
        val c = codec ?: return
        var emptySpins = 0
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 2_000)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!final) return
                // 收尾时编码器迟迟不给最后一包（低端机上有这种），空转 0.5 秒就撤，别把录影线程钉死
                if (++emptySpins > 250) return
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (track >= 0) return
                muxer?.let { track = it.addTrack(c.outputFormat) }
                runCatching { muxer?.start() }
            } else if (idx >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0) {
                    val out = c.getOutputBuffer(idx) ?: continue
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    muxer?.let { if (track >= 0) it.writeSampleData(track, out, info) }
                }
                c.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    /**
     * RGBA → YUV420P（BT.601 有限量程，和编码器默认协商的色彩范围一致）。
     * 三个平面是分开的：Y 占满前 w*h 字节，U 从 w*h 开始排 (w/2)*(h/2) 字节，V 再往后同样大小一块。
     * 只处理偶数行偶数列，画面右下一像素的偏差肉眼看不见。
     */
    private fun toYuv(bmp: Bitmap, bytes: ByteArray) {
        if (rgba.size < w * h) return
        bmp.getPixels(rgba, 0, w, 0, 0, w, h)
        val px = rgba
        val cw = w / 2
        val vBase = w * h + cw * (h / 2)
        for (j in 0 until h step 2) {
            val row = j * w
            val row2 = (j + 1) * w
            val cRow = w * h + (j / 2) * cw
            for (i in 0 until w step 2) {
                val c0 = px[row + i]
                val c1 = px[row + i + 1]
                val c2 = px[row2 + i]
                val c3 = px[row2 + i + 1]
                bytes[row + i] = y(c0)
                bytes[row + i + 1] = y(c1)
                bytes[row2 + i] = y(c2)
                bytes[row2 + i + 1] = y(c3)
                val u: Int = (-0.148f * r(c0) - 0.289f * r(c1) - 0.419f * r(c2) + 0.856f * r(c3) + 128f).toInt()
                val v: Int = (0.856f * r(c0) - 0.726f * r(c1) - 0.130f * r(c2) - 0.099f * r(c3) + 128f).toInt()
                bytes[cRow + i / 2] = u.coerceIn(0, 255).toByte()
                bytes[vBase + (j / 2) * cw + i / 2] = v.coerceIn(0, 255).toByte()
            }
        }
    }

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun y(c: Int) = (0.257f * r(c) + 0.504f * g(c) + 0.098f * b(c) + 16f).toInt()
        .coerceIn(0, 255).toByte()
}
