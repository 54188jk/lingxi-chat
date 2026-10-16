package com.lingxi.chat

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaActionSound
import android.view.Surface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.lingxi.chat.data.EffectModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max

/**
 * 手指特效页：摄像头预览 + 手部关键点 + 星河流光／握拳石化。
 * 三条硬约束：识别只在本机做（画面不联网、不上传）、识别文件随包内置、用到时放一份到本机永久目录、
 * 任何一环拿不到（没权限／没摄像头／没下着文件）都用一句人话停住，不闪退也不假装能用。
 */
class HandFxActivity : BaseActivity() {

    private lateinit var b: com.lingxi.chat.databinding.ActivityHandFxBinding
    private val scope = MainScope()
    private val bg = HandlerThread("handfx-cam").apply { start() }
    private val bgHandler = Handler(bg.looper)
    private val recThread = HandlerThread("handfx-rec").apply { start() }
    private val recHandler = Handler(recThread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var sensorDeg = 90
    private var previewW = 0
    private var previewH = 0
    private var landmarker: HandLandmarker? = null
    private var openness = 0f
    private var analyzing = false
    private var recording = false
    private var retriedModel = false
    @Volatile private var recorder: ClipRecorder? = null
    @Volatile private var recField: AuraField? = null
    @Volatile private var lastMarks: FloatArray? = null
    @Volatile private var lastOpen = 0f
    private var recOut: File? = null
    private var recStart = 0L
    private var recFrames = 0

    private val REQ_CAM = 7121

    /** 一帧一帧抓画面做识别；60ms 一次足够跟上手的动作，再快机器白费劲 */
    private val tick = object : Runnable {
        override fun run() {
            if (!analyzing || isFinishing) return
            val t0 = SystemClock.uptimeMillis()
            analyzeOnce()
            val cost = SystemClock.uptimeMillis() - t0
            b.root.postDelayed(this, max(20L, 60L - cost))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = com.lingxi.chat.databinding.ActivityHandFxBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
        b.btnRec.setOnClickListener { toggleRecord() }
        b.btnModel.setOnClickListener { onModelButton() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAM)
            showState(
                "要摄像头才能玩",
                "这个特效要看你的手，需要允许糯叽使用摄像头。不许也可以进来，只是画面和特效都不会动。",
                "去允许"
            )
        } else {
            prepareModel()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAM) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            hideState()
            prepareModel()
        } else {
            toast("没给摄像头权限，这个特效用不了")
        }
    }

    // ---------------- 模型：有就用，没有才下 ----------------

    private fun prepareModel() {
        val f = EffectModel.find(this)
        if (f != null) {
            load(f)
            return
        }
        showState(
            "先把手部识别文件放好",
            "这个文件已经在安装包里，放一次就一直留在手机本机，不用联网、也不用再下载。",
            "现在就放"
        )
    }

    private fun onModelButton() {
        b.btnModel.isEnabled = false
        b.btnModel.alpha = 0.5f
        b.pbModel.visibility = View.VISIBLE
        b.tvModelPct.visibility = View.VISIBLE
        b.tvModelPct.text = "正在放到本机…"
        scope.launch {
            val got = withContext(Dispatchers.IO) {
                EffectModel.ensure(this@HandFxActivity) { pct, _ -> runOnUiThread { setModelPct(pct) } }
            }
            b.btnModel.isEnabled = true
            b.btnModel.alpha = 1f
            if (got == null) {
                b.pbModel.visibility = View.GONE
                b.tvModelPct.text = "没能放好，可能是这块存储不让我写"
                toast("没能放好，再点一次试试")
            } else {
                b.pbModel.visibility = View.GONE
                hideState()
                load(got)
            }
        }
    }

    private fun setModelPct(pct: Int) {
        b.pbModel.progress = pct
        b.tvModelPct.text = "$pct%"
    }

    private fun load(f: File) {
        scope.launch {
            val outcome: Result<HandLandmarker> = withContext(Dispatchers.IO) { runCatching { build(f) } }
            val lm = outcome.getOrNull()
            if (lm != null) {
                landmarker?.close()
                landmarker = lm
                hideState()
                startCamera()
                b.aura.start()
                analyzing = true
                b.root.postDelayed(tick, 240L)
                return@launch
            }
            val why = explain(outcome.exceptionOrNull())
            if (!retriedModel) {
                // 第一次读不起来，先当作那份文件坏了：删掉自动重放一次，别再麻烦用户点
                retriedModel = true
                runCatching { f.delete() }
                showState("这份文件读不起来，正在重新放一份", why, null)
                onModelButton()
            } else {
                showState("这个文件读不起来", why, "重新放一份")
            }
        }
    }

    /** 把本机那份文件读进直接内存并交给推理库；读不满就直接报错，别拿半份去初始化 */
    private fun build(f: File): HandLandmarker {
        if (!EffectModel.isReady(f)) throw java.io.IOException("内容校验没过")
        val size = f.length().toInt()
        val buf = ByteBuffer.allocateDirect(size)
        java.io.FileInputStream(f).channel.use { ch ->
            var idle = 0
            while (buf.hasRemaining()) {
                if (ch.read(buf) > 0) {
                    idle = 0
                } else if (++idle > 64) {
                    throw java.io.IOException("只读到 " + buf.position() + "/" + size + " 字节")
                }
            }
        }
        buf.rewind()
        val opts = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(buf).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .build()
        return HandLandmarker.createFromOptions(this, opts)
    }
    /** 把异常换成一句人话，同时留一点线索给用户报回来 */
    private fun explain(e: Throwable?): String = when (e) {
        null -> "不清楚卡在哪一步"
        is UnsatisfiedLinkError, is NoClassDefFoundError, is ExceptionInInitializerError ->
            "这台机器的运行库加载不起来（多为系统位数或 ROM 精简所致）：" + e.message?.take(90).orEmpty()
        else -> "文件没能读全：" + e.javaClass.simpleName + " " + e.message?.take(90).orEmpty()
    }
    // ---------------- 摄像头 ----------------

    private fun startCamera() {
        if (!b.texCam.isAvailable) {
            b.texCam.surfaceTextureListener = object : TextureViewListener() {
                override fun onAvailable(t: SurfaceTexture) = openCamera(t)
            }
            return
        }
        openCamera(b.texCam.surfaceTexture!!)
    }

    private fun openCamera(texture: SurfaceTexture) {
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = runCatching { pickCamera(mgr) }.getOrNull()
        if (id == null) {
            showState("这台机器上没有能用摄像头", "换一台设备再用这个特效。", null)
            return
        }
        val chars = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull() ?: return
        sensorDeg = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        val best = sizes.filter { it.width * it.height <= 1920 * 1080 }.maxByOrNull { it.width * it.height }
            ?: sizes.firstOrNull()
        if (best == null) {
            showState("摄像头打不开", "关掉这个页面再进来试一次。", null)
            return
        }
        previewW = best.width; previewH = best.height
        texture.setDefaultBufferSize(best.width, best.height)
        applyTransform(texture)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    runCatching { d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) }
                        .onSuccess { buildSession(it) }
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close(); device = null
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); device = null
                    runOnUiThread { showState("摄像头打不开", "可能被别的应用占着，关掉那个再用一次。（错误码 $error）", null) }
                }
            }, bgHandler)
        }.onFailure {
            showState("摄像头打不开", "系统不让这个应用用摄像头。", null)
        }
    }

    private fun pickCamera(mgr: CameraManager): String {
        // 后置最稳：不用镜像，手指和画面对得上
        val ids = mgr.cameraIdList
        return ids.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.first()
    }

    private fun buildSession(req: android.hardware.camera2.CaptureRequest.Builder) {
        val d = device ?: return
        // 只挂预览这一路输出，够用又省电量
        val preview = Surface(b.texCam.surfaceTexture)
        runCatching {
            d.createCaptureSession(listOf(preview), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    req.set(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    req.addTarget(preview)
                    runCatching { s.setRepeatingRequest(req.build(), null, bgHandler) }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runOnUiThread { showState("摄像头打不开", "预览通道没建起来，退出再进一次。", null) }
                }
            }, bgHandler)
        }
    }

    /** 画面按「铺满」摆放：先还原摄像头原始比例，再转感应器角度，最后放大到盖住整块屏 */
    private fun applyTransform(texture: SurfaceTexture) {
        val vw = b.texCam.width.toFloat().takeIf { it > 0f } ?: return
        val vh = b.texCam.height.toFloat()
        val cx = vw / 2f; val cy = vh / 2f
        val m = Matrix()
        m.postScale(previewW / vw, previewH / vh, cx, cy)
        m.postRotate(sensorDeg.toFloat(), cx, cy)
        val swap = abs(sin90(sensorDeg)) > 0.5f
        val effW = if (swap) previewH.toFloat() else previewW.toFloat()
        val effH = if (swap) previewW.toFloat() else previewH.toFloat()
        val k = max(vw / effW, vh / effH)
        m.postScale(k, k, cx, cy)
        b.texCam.setTransform(m)
    }

    private fun sin90(deg: Int): Float {
        val r = deg % 180
        return if (r == 90 || r == -90) 1f else 0f
    }

    // ---------------- 识别与特效 ----------------

    private fun analyzeOnce() {
        val lm = landmarker ?: return
        if (!b.texCam.isAvailable || b.texCam.width == 0) return
        val vw = b.texCam.width
        val vh = b.texCam.height
        val w = 480
        val h = max(2, (vh * w.toFloat() / vw).toInt())
        val bmp = runCatching { b.texCam.getBitmap(w, h) }.getOrNull() ?: return
        val marks: FloatArray?
        try {
            val img = BitmapImageBuilder(bmp).build()
            val res = lm.detectForVideo(img, SystemClock.uptimeMillis())
            val list = res.landmarks().firstOrNull()
            if (list == null || list.size < 21) {
                marks = null
            } else {
                val arr = FloatArray(42)
                for (i in 0 until 21) {
                    arr[i * 2] = list[i].x()
                    arr[i * 2 + 1] = list[i].y()
                }
                marks = arr
            }
        } catch (t: Throwable) {
            bmp.recycle()
            return
        }
        bmp.recycle()
        openness = if (marks == null) 0f else b.aura.field.openness(marks)
        lastMarks = marks
        lastOpen = openness
        b.aura.feed(marks, openness)
        val hint = when {
            marks == null -> "把手伸到镜头前，张开五指定住一下"
            openness > 0.55f -> "张开：指间星河"
            openness < 0.18f -> "握紧：石化"
            else -> "正在跟着你的手"
        }
        if (b.tvHint.text != hint) b.tvHint.text = hint
    }

    // ---------------- 录一段 ----------------

    private fun toggleRecord() {
        if (!recording) startRecord() else stopRecord()
    }

    private fun startRecord() {
        if (landmarker == null || !b.texCam.isAvailable) {
            toast("先让画面跑起来再录")
            return
        }
        val vw = b.texCam.width
        val vh = b.texCam.height
        if (vw == 0 || vh == 0) return
        val w = 720
        val h = ((vh * w.toFloat() / vw).toInt() / 2) * 2
        val out = File(cacheDir, "record")
        out.mkdirs()
        val dst = File(out, "hand-${System.currentTimeMillis()}.mp4")
        val r = ClipRecorder()
        runCatching { r.start(dst, w, h, 20) }.getOrElse {
            toast("这台机器录不了，特效还能照常看")
            return
        }
        recOut = dst
        recorder = r
        recField = AuraField(this)
        recording = true
        recStart = System.currentTimeMillis()
        recFrames = 0
        b.btnRec.text = "停下"
        runCatching { MediaActionSound().play(MediaActionSound.START_VIDEO_RECORDING) }
        b.root.post(grabTick)
    }

    /** 抓帧只能在界面线程做，抓完把整张图丢给后台去画特效和编码 */
    private val grabTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val vw = b.texCam.width
            val vh = b.texCam.height
            if (vw > 0 && vh > 0) {
                val h = ((vh * 720f / vw).toInt() / 2) * 2
                val bmp = runCatching { b.texCam.getBitmap(720, h) }.getOrNull()
                if (bmp != null) recHandler.post { encodeFrame(bmp) }
            }
            if (System.currentTimeMillis() - recStart > 20_000L) {
                stopRecord()
                return
            }
            b.root.postDelayed(this, 50L)
        }
    }

    private fun encodeFrame(bmp: Bitmap) {
        val r = recorder
        val f = recField
        if (r == null || f == null) {
            bmp.recycle()
            return
        }
        val c = Canvas(bmp)
        f.update(lastMarks?.copyOf(), lastOpen, System.currentTimeMillis())
        f.draw(c, bmp.width, bmp.height, System.currentTimeMillis())
        r.push(bmp, recFrames * 50_000L)
        recFrames++
        bmp.recycle()
    }
    private fun stopRecord() {
        if (!recording) return
        recording = false
        b.root.removeCallbacks(grabTick)
        b.btnRec.text = "录一段"
        recField = null
        // 编码器只能在还在往里送帧的那条线程上收尾，否则会和最后一帧撞车
        recHandler.post {
            val r = recorder
            val src = recOut
            recorder = null
            recOut = null
            val saved = if (r != null && src != null) {
                runCatching { r.stop() }
                publish(src)
            } else null
            runCatching { MediaActionSound().play(MediaActionSound.STOP_VIDEO_RECORDING) }
            runOnUiThread { toast(if (saved == null) "这段没录上" else "已存好：$saved") }
        }
    }
    /** 把临时文件挪进用户能在相册里翻到的位置 */
    private fun publish(src: File): String? {
        val name = "糯叽-手指特效-" + android.text.format.DateFormat.format("yyyyMMdd-HHmmss", System.currentTimeMillis()) + ".mp4"
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val v = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/糯叽")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v) ?: return@runCatching false
                contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(src).use { it.copyTo(out) } }
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                true
            }.getOrDefault(false)
        } else {
            runCatching {
                val dir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MOVIES
                    ), "糯叽"
                )
                dir.mkdirs()
                val dst = File(dir, name)
                FileInputStream(src).use { a -> dst.outputStream().use { b -> a.copyTo(b) } }
                android.media.MediaScannerConnection.scanFile(this, arrayOf(dst.absolutePath), arrayOf("video/mp4"), null)
                true
            }.getOrDefault(false)
        }
        val shown = if (ok) name else null
        runCatching { src.delete() }
        return if (ok) "相册里的 $name" else null
    }

    // ---------------- 状态牌 ----------------

    private fun showState(title: String, desc: String, button: String?) {
        b.llState.visibility = View.VISIBLE
        b.tvStateTitle.text = title
        b.tvStateDesc.text = desc
        b.btnModel.text = button ?: "知道了"
        b.btnModel.visibility = if (button == null) View.GONE else View.VISIBLE
    }

    private fun hideState() {
        b.llState.visibility = View.GONE
        b.pbModel.visibility = View.GONE
        b.tvModelPct.visibility = View.GONE
    }

    override fun onDestroy() {
        analyzing = false
        b.root.removeCallbacks(tick)
        recHandler.removeCallbacksAndMessages(null)
        runCatching { recField = null }
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null; device = null
        runCatching { recorder?.stop() }
        recorder = null
        runCatching { landmarker?.close() }
        landmarker = null
        bg.quitSafely()
        recThread.quitSafely()
        scope.cancel()
        super.onDestroy()
    }
}

/** TextureView 的监听只要一个回调，写成基类省掉空方法 */
private open class TextureViewListener : android.view.TextureView.SurfaceTextureListener {
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    open fun onAvailable(t: SurfaceTexture) {}
}
