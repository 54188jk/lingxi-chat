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
    private var glassFeed = false
    private var recording = false
    private var retriedModel = false
    private var reloadOnly = false
    @Volatile private var inflying = false
    private val inferThread = HandlerThread("handfx-infer").apply { start() }
    private val inferHandler = Handler(inferThread.looper)
    @Volatile private var recorder: ClipRecorder? = null
    @Volatile private var recField: AuraField? = null
    @Volatile private var lastMarks: FloatArray? = null
    @Volatile private var lastOpen = 0f
    private var recOut: File? = null
    private var recStart = 0L
    private var recFrames = 0
    @Volatile private var lastTs = 0L
    @Volatile private var inferFails = 0

    private val REQ_CAM = 7121

    /** 一帧一帧抓画面做识别；60ms 一次足够跟上手的动作，再快机器白费劲 */
    private val tick = object : Runnable {
        override fun run() {
            if (!analyzing || isFinishing) return
            analyzeOnce()
            b.root.postDelayed(this, 60L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = com.lingxi.chat.databinding.ActivityHandFxBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnBack.setOnClickListener { finish() }
        b.btnRec.setOnClickListener { toggleRecord() }
        b.btnShoot.setOnClickListener { shoot() }
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
        if (reloadOnly) {
            // 运行库没起来时重放文件是白费劲，直接拿现有那份再试一次加载
            val cur = EffectModel.find(this)
            if (cur != null) {
                hideState()
                load(cur)
                return
            }
            reloadOnly = false
        }
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
                // 旧实例排在识别线程队尾关：直接在这条线程关会和正在跑的那一帧撞车
                val prev = landmarker
                landmarker = lm
                if (prev != null) inferHandler.post { runCatching { prev.close() } }
                // 顶栏和底栏要透出相机画面：把相机那块视图交给玻璃，之后每一帧喂它一张
                glassFeed = !com.lingxi.chat.data.DevicePerf.lowEnd(this@HandFxActivity) &&
                    com.lingxi.chat.data.ConfigStore(this@HandFxActivity).liquidGlass
                if (glassFeed) GlassBackdrop.attachFrame(this@HandFxActivity, b.texCam)
                hideState()
                startCamera()
                b.aura.start()
                analyzing = true
                b.root.postDelayed(tick, 240L)
                return@launch
            }
            val err = outcome.exceptionOrNull()
            val why = explain(err)
            // 运行库压根没加载起来时，重放识别文件是没用的功课
            val noRetryCopy = err is UnsatisfiedLinkError || err is NoClassDefFoundError || err is ExceptionInInitializerError
            if (noRetryCopy) {
                reloadOnly = true
                val title = if (err is UnsatisfiedLinkError) "这台手机的系统带不动这个特效" else "特效没能启动"
                showState(title, why + "\n\n长按下面这行说明可以复制，发给作者就能定位。", "再试一次")
            } else if (!retriedModel) {
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

    /**
     * 把本机那份文件交给推理库。
     * 先按整块内存喂（不碰文件路径，权限最省事），某些 ROM 上这条路会失手，就换文件路径再试一次；
     * 两次都不行才把第一次的错报出去，因为那才是用户真正撞上的那道坎。
     */
    private fun build(f: File): HandLandmarker {
        if (!EffectModel.isReady(f)) throw java.io.IOException("内容校验没过")
        val buf = readIntoBuffer(f)
        return runCatching { HandLandmarker.createFromOptions(this, landmarkerOptions(BaseOptions.builder().setModelAssetBuffer(buf))) }
            .getOrElse { first ->
                runCatching {
                    HandLandmarker.createFromOptions(this, landmarkerOptions(BaseOptions.builder().setModelAssetPath(f.absolutePath)))
                }.getOrElse { throw first }
            }
    }

    private fun landmarkerOptions(base: BaseOptions.Builder) =
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base.build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .build()

    /** 读进直接内存；读不满就直接报错，别拿半份去初始化 */
    private fun readIntoBuffer(f: File): ByteBuffer {
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
        return buf
    }

    /** 把异常换成一句人话，并附上这台机器的情况，用户长按就能复制整段报回来 */
    private fun explain(e: Throwable?): String {
        if (e == null) return "不清楚卡在哪一步"
        val root = generateSequence(e) { it.cause }.last()
        val detail = root.message?.take(200).orEmpty().ifBlank { root.javaClass.simpleName }
        return when (e) {
            is UnsatisfiedLinkError ->
                "特效要用的那颗运行库没能装进这个系统。\n${deviceLine()}\n$detail"
            is NoClassDefFoundError, is ExceptionInInitializerError ->
                "特效初始化到一半停住了，这不是这台手机的问题。\n${deviceLine()}\n$detail"
            else -> "文件没能读全：" + root.javaClass.simpleName + " " + root.message?.take(120).orEmpty() +
                    "\n" + deviceLine()
        }
    }

    private fun deviceLine(): String =
        Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE +
            "（API " + Build.VERSION.SDK_INT + "）"

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

    /**
     * 抓帧只能在界面线程做，识别整块挪到专用线程：一次推理几十毫秒，
     * 放在界面线程上会把手势反馈拖成一卡一卡，严重了系统还会弹「应用无响应」。
     * 上一帧没算完就跳过这一帧，永远只有一张位图在后台，算完由后台回收。
     */
    private fun analyzeOnce() {
        val lm = landmarker ?: return
        if (!analyzing || inflying || isFinishing) return
        if (!b.texCam.isAvailable || b.texCam.width == 0) return
        val vw = b.texCam.width
        val vh = b.texCam.height
        val w = 480
        val h = max(2, (vh * w.toFloat() / vw).toInt())
        val bmp = runCatching { b.texCam.getBitmap(w, h) }.getOrNull() ?: return
        // 玻璃要的就是这一张：趁它还没交给后台，先糊进背景（frameFrom 只读，不持有）
        if (glassFeed) {
            GlassBackdrop.frameFrom(this, bmp)
            b.glassTop.invalidateGlass()
            b.glassBottom.invalidateGlass()
        }
        inflying = true
        inferHandler.post {
            var marks: FloatArray? = null
            val ts = max(lastTs + 1, SystemClock.uptimeMillis())
            lastTs = ts
            try {
                val res = lm.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
                val list = res.landmarks().firstOrNull()
                if (list != null && list.size >= 21) {
                    val arr = FloatArray(42)
                    for (i in 0 until 21) {
                        arr[i * 2] = list[i].x()
                        arr[i * 2 + 1] = list[i].y()
                    }
                    marks = arr
                    inferFails = 0
                } else {
                    // 画面里没有手是正常状态，不能算失败；只数真正抛异常的次数
                    inferFails = 0
                }
            } catch (_: Throwable) {
                inferFails++
            }
            runCatching { bmp.recycle() }
            inflying = false
            val got = marks
            if (inferFails > 60) {
                inferFails = 0
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    analyzing = false
                    b.aura.stop()
                    showState("识别跑不动了", "这台机器带不动这个特效，休息一会儿再进来试试。", "再试一次")
                }
                return@post
            }
            b.root.post {
                if (!analyzing || isFinishing) return@post
                openness = if (got == null) 0f else b.aura.field.openness(got)
                lastMarks = got
                lastOpen = openness
                b.aura.feed(got, openness)
                val hint = when {
                    got == null -> "把手伸到镜头前，张开五指定住一下"
                    openness > 0.55f -> "张开：指间星河"
                    openness < 0.18f -> "握紧：石化"
                    else -> "正在跟着你的手"
                }
                if (b.tvHint.text != hint) b.tvHint.text = hint
            }
        }
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
        b.btnShoot.isEnabled = false
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
        runCatching { r.push(bmp) }
        recFrames++
        bmp.recycle()
    }
    private fun stopRecord() {
        if (!recording) return
        recording = false
        b.root.removeCallbacks(grabTick)
        b.btnRec.text = "录一段"
        b.btnShoot.isEnabled = true
        // 编码器只能在还在往里送帧的那条线程上收尾，否则会和最后一帧撞车
        recHandler.post {
            recField = null
            val r = recorder
            val src = recOut
            recorder = null
            recOut = null
            val saved = if (r != null && src != null) {
                val done = runCatching { r.stop() }.getOrDefault(false)
                if (done && src.isFile && src.length() > 2048) publish(src) else {
                    runCatching { src.delete() }; null
                }
            } else null
            runCatching { MediaActionSound().play(MediaActionSound.STOP_VIDEO_RECORDING) }
            runOnUiThread { toast(if (saved == null) "这段没录上" else "已存好：$saved") }
        }
    }
    /**
     * 拍一张：把屏幕上这一帧连特效一起存进相册。
     * 特效强度是逐帧缓动出来的，临时那份得先空跑几帧才到满强度，否则拍出来比看见的淡一大截。
     */
    private fun shoot() {
        if (recording) { toast("录的时候先停下再拍"); return }
        if (landmarker == null || !b.texCam.isAvailable) { toast("等画面出来再拍"); return }
        val vw = b.texCam.width
        val vh = b.texCam.height
        if (vw == 0 || vh == 0) return
        val w = 1080
        val h = ((vh * w.toFloat() / vw).toInt() / 2) * 2
        val bmp = runCatching { b.texCam.getBitmap(w, h) }.getOrNull()
        if (bmp == null) { toast("这一下没抓到画面"); return }
        val f = AuraField(this)
        val m = lastMarks?.copyOf()
        val o = lastOpen
        repeat(8) { f.update(m?.copyOf(), o, System.currentTimeMillis()) }
        runCatching { f.draw(Canvas(bmp), w, h, System.currentTimeMillis()) }
        runCatching { MediaActionSound().play(MediaActionSound.SHUTTER_CLICK) }
        b.btnShoot.isEnabled = false
        scope.launch {
            val where = withContext(Dispatchers.IO) {
                val name = "糯叽-手指特效-" +
                    android.text.format.DateFormat.format("yyyyMMdd-HHmmss", System.currentTimeMillis()) + ".jpg"
                val saved = runCatching { writeImage(bmp, name) }.getOrNull()
                bmp.recycle()
                saved
            }
            b.btnShoot.isEnabled = true
            toast(if (where == null) "这张没能存下" else "已存好：$where")
        }
    }

    /** 存图：能进相册就进相册，进不去就落在应用自己的图片目录并把位置说清楚 */
    private fun writeImage(bmp: android.graphics.Bitmap, name: String): String? {
        val tmp = File(cacheDir, name)
        val written = runCatching {
            tmp.outputStream().use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out) && tmp.length() > 1024
            }
        }.getOrDefault(false)
        if (!written) {
            runCatching { tmp.delete() }
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val ok = runCatching {
                val v = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/糯叽")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                    ?: return@runCatching false
                contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(tmp).use { it.copyTo(out) } }
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                true
            }.getOrDefault(false)
            runCatching { tmp.delete() }
            return if (ok) "相册里的 $name" else null
        }
        val pub = runCatching {
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES
                ), "糯叽"
            )
            dir.mkdirs()
            val dst = File(dir, name)
            FileInputStream(tmp).use { a -> dst.outputStream().use { sink -> a.copyTo(sink) } }
            android.media.MediaScannerConnection.scanFile(this, arrayOf(dst.absolutePath), arrayOf("image/jpeg"), null)
            dst.absolutePath
        }.getOrNull()
        if (pub != null) { runCatching { tmp.delete() }; return pub }
        val own = runCatching {
            val base = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                ?: return@runCatching null
            val dir = File(base, "糯叽")
            dir.mkdirs()
            val dst = File(dir, name)
            FileInputStream(tmp).use { a -> dst.outputStream().use { b -> a.copyTo(b) } }
            dst.absolutePath
        }.getOrNull()
        runCatching { tmp.delete() }
        return own?.let { "本机 $it" }
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
        runCatching { src.delete() }
        return if (ok) "相册里的 $name" else null
    }

    // ---------------- 状态牌 ----------------

    private fun showState(title: String, desc: String, button: String?) {
        b.llState.visibility = View.VISIBLE
        b.tvStateTitle.text = title
        b.tvStateDesc.text = desc
        // 出不了这一屏的时候，唯一能把现场说清楚的办法就是让用户一行字都不用打
        b.tvStateDesc.setOnLongClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("糯叽特效", title + "\n" + desc))
            toast("说明已复制，直接粘贴发给我就行")
            true
        }
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
        recording = false
        GlassBackdrop.detach(this)
        b.root.removeCallbacks(tick)
        b.root.removeCallbacks(grabTick)
        // 编码器收尾、识别实例释放都要排在各自那条线程的队尾跑完，再让线程退出：
        // 从界面线程直接关会和正在送的最后一帧撞车，还会留下一个没写完的坏文件
        recHandler.post {
            runCatching { recorder?.stop() }
            recOut?.let { runCatching { it.delete() } }
            recField = null; recorder = null; recOut = null
        }
        recThread.quitSafely()
        inferHandler.post {
            runCatching { landmarker?.close() }
            landmarker = null
        }
        inferThread.quitSafely()
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null; device = null
        bg.quitSafely()
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
