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
        started = true
        frames = 0
    }

    /** 收一帧 RGBA 合成图，转换后交给编码器 */
    fun push(src: Bitmap, ptsUs: Long) {
        val c = codec ?: return
        if (!started) return
        val bmp = if (src.width == w && src.height == h) src
        else Bitmap.createScaledBitmap(src, w, h, true)
        toYuv(bmp, plane)
        val idx = c.dequeueInputBuffer(4_000)
        if (idx >= 0) {
            val ib = c.getInputBuffer(idx) ?: return
            ib.clear()
            ib.put(plane)
            c.queueInputBuffer(idx, 0, plane.size, ptsUs, 0)
            frames++
        }
        drain(false)
    }

    fun stop() {
        val c = codec
        if (c != null && started) {
            val idx = c.dequeueInputBuffer(2_000)
            if (idx >= 0) c.queueInputBuffer(idx, 0, 0, frames * 1_000_000L / max(1, fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            runCatching { c.stop() }
        }
        val m = muxer
        if (m != null) {
            runCatching { if (track >= 0) m.stop() }
            runCatching { m.release() }
        }
        runCatching { c?.release() }
        codec = null; muxer = null; track = -1; started = false; frames = 0
    }

    fun busy() = started

    private fun drain(final: Boolean) {
        val c = codec ?: return
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 2_000)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!final) return
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
     * RGBA → YUV420P（BT.601 全量程）。只处理偶数行偶数列，剩下的边直接裁掉，
     * 编码器本来也要求宽高成双，画面右下一像素的偏差肉眼看不见。
     */
    private fun toYuv(bmp: Bitmap, bytes: ByteArray) {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (j in 0 until h step 2) {
            val row = j * w
            val row2 = (j + 1) * w
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
                val uvRow = w * h + (j / 2) * (w / 2)
                bytes[uvRow + i / 2] = u.coerceIn(0, 255).toByte()
                bytes[uvRow + (w / 2) + i / 2] = v.coerceIn(0, 255).toByte()
            }
        }
    }

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun y(c: Int) = (0.257f * r(c) + 0.504f * g(c) + 0.098f * b(c) + 16f).toInt()
        .coerceIn(0, 255).toByte()
}
