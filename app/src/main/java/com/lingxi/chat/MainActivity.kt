package com.lingxi.chat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.lingxi.chat.data.ChatMessage
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.data.Session
import com.lingxi.chat.data.SessionStore
import com.lingxi.chat.databinding.ActivityMainBinding
import com.lingxi.chat.net.OpenAiClient
import com.lingxi.chat.net.SearchClient
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : BaseActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var configStore: ConfigStore
    private lateinit var sessionStore: SessionStore
    private lateinit var adapter: ChatAdapter
    private val client = OpenAiClient()

    private var session = Session()
    private var streaming = false
    private var pendingImageBase64: String? = null
    private var pendingImageMime: String? = null
    private var pendingFileText: String? = null
    private var pendingFileName: String? = null
    private var lastNotifyTime = 0L

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    private var cameraFile: File? = null

    private var recorder: android.media.MediaRecorder? = null
    private var recordFile: File? = null
    private var recording = false
    private var recordDialog: androidx.appcompat.app.AlertDialog? = null
    private var pendingSttConfig: com.lingxi.chat.data.ModelConfig? = null

    // ============ 协程异步层（替代裸Thread/Handler）============

    /** 生命周期作用域：Activity 销毁时自动取消所有协程，不会泄漏 */
    private val scope: LifecycleCoroutineScope = lifecycleScope

    /** 「思考中」三点动画 */
    private var thinkingJob: kotlinx.coroutines.Job? = null

    /** 打字机：把已收到的文本按帧逐步显示，积压越多追得越快 */
    private var revealedLen = 0
    private var typeJob: kotlinx.coroutines.Job? = null
    private var draftJob: kotlinx.coroutines.Job? = null
    private var caretJob: kotlinx.coroutines.Job? = null
    private var clockJob: kotlinx.coroutines.Job? = null
    private var searchJob: kotlinx.coroutines.Job? = null
    private var searchingMessage: ChatMessage? = null

    // 流式光标闪烁：▍ 每 500ms 显示/隐藏
    private var caretVisible = true

    /** 系统关闭动画时（开发者选项/无障碍）跳过动态效果 */
    private fun animationsEnabled(): Boolean {
        val scale = android.provider.Settings.Global.getFloat(
            contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
        return scale > 0.01f
    }

    // 会话内搜索命中的关键词
    private var highlightKey: String? = null

    private fun startThinkingTicker(index: Int) {
        thinkingJob?.cancel()
        thinkingJob = scope.launch {
            while (isActive) {
                if (!streaming || session.messages.getOrNull(index)?.content?.isNotBlank() == true) return@launch
                adapter.thinkingDots = adapter.thinkingDots % 3 + 1
                adapter.notifyItemChanged(index)
                delay(450)
            }
        }
    }

    /** 光标闪烁：只关心「正在流式输出的那一条」 */
    private fun startCaretBlink() {
        caretJob?.cancel()
        caretJob = scope.launch {
            while (isActive) {
                delay(500)
                caretVisible = !caretVisible
                val idx = adapter.streamingIndex
                if (idx < 0) continue
                val msg = session.messages.getOrNull(idx) ?: continue
                if (msg.content.isNotBlank()) {
                    adapter.updateStreamingText(
                        msg.content.substring(0, minOf(revealedLen, msg.content.length)),
                        caretVisible
                    )
                }
            }
        }
    }

    /** 顶栏时钟：进入前台时启动，离开时自动停 */
    private fun startClock() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                b.tvClock.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                delay(1000)
            }
        }
    }

    private val openSessions =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringExtra("session_id")?.let { loadSession(it) }
            highlightKey = r.data?.getStringExtra("highlight")?.takeIf { it.isNotBlank() }
            adapter.highlight = highlightKey
            adapter.notifyDataSetChanged()
        }
    }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleImage(it) }
        }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleFile(it) }
        }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) cameraFile?.let { handleImage(android.net.Uri.fromFile(it)) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        configStore = ConfigStore(this)
        sessionStore = SessionStore(this)

        initTts()

        newAdapter()
        val lm = LinearLayoutManager(this)
        lm.stackFromEnd = true
        b.rvMessages.layoutManager = lm
        b.rvMessages.adapter = adapter
        b.rvMessages.itemAnimator?.apply {
            addDuration = 180
            changeDuration = 0
        }
        adapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onChanged() = updateWelcome()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = updateWelcome()
        })
        updateWelcome()
        if (b.llWelcome.visibility == View.VISIBLE) {
            b.llWelcome.alpha = 0f
            b.llWelcome.translationY = 24f
            b.llWelcome.animate().alpha(1f).translationY(0f)
                .setDuration(420).setStartDelay(120)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }

        val chipListener = View.OnClickListener { v ->
            val t = (v as? android.widget.TextView)?.text?.toString() ?: return@OnClickListener
            b.etInput.setText(t)
            b.etInput.setSelection(t.length)
            b.etInput.requestFocus()
        }
        b.chip1.setOnClickListener(chipListener)
        b.chip2.setOnClickListener(chipListener)
        b.chip3.setOnClickListener(chipListener)

        b.etInput.setOnFocusChangeListener { _, hasFocus ->
            b.llInputBar.setBackgroundResource(
                if (hasFocus) R.drawable.bg_input_bar_focused else R.drawable.bg_input_bar
            )
        }
        b.btnSend.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(100).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }

        b.btnNew.setOnClickListener { newSession() }
        b.btnHistory.setOnClickListener {
            openSessions.launch(Intent(this, SessionsActivity::class.java))
            animateForward()
        }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            animateForward()
        }
        b.btnShare.setOnClickListener { exportSession() }
        startLogoBreathing()
        b.root.findViewById<android.widget.ImageView>(R.id.ivLogo).setOnClickListener { showRolePicker() }

        b.btnAttach.setOnClickListener { showAttachOptions() }
        b.btnRemoveImage.setOnClickListener { clearPendingImage() }
        b.btnRemoveFile.setOnClickListener {
            pendingFileText = null
            pendingFileName = null
            b.llFilePreview.visibility = View.GONE
        }
        b.btnMic.setOnClickListener { onMicClick() }
        b.btnSearch.isChecked = configStore.searchEnabled
        b.btnSearch.setOnCheckedChangeListener { _, checked ->
            configStore.searchEnabled = checked
            updateSearchTint()
            if (checked) toast("联网搜索已开启（DuckDuckGo 免费搜索）")
        }
        updateSearchTint()

        b.btnSend.setOnClickListener { view ->
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            if (streaming) stopGenerating() else send()
        }

        // 回车行为：设置里可切「回车发送 / 回车换行」，Shift+回车始终换行
        b.etInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            ) {
                if (configStore.enterToSend && !event.isShiftPressed) {
                    send()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        b.btnEmoji.setOnClickListener { showEmojiPanel() }

        // 草稿：停手 600ms 落盘，切页面/退后台不丢
        b.etInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                draftJob?.cancel()
                val text = s?.toString().orEmpty()
                draftJob = scope.launch {
                    delay(600)
                    configStore.draft = text
                }
            }
        })
        if (configStore.draft.isNotBlank()) {
            b.etInput.setText(configStore.draft)
            b.etInput.setSelection(b.etInput.text.length)
        }

        UpdateUi.check(this, BuildConfig.VERSION_NAME, silent = true)
    }

    private fun showEmojiPanel() {
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 8
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        EMOJIS.forEach { e ->
            val tv = android.widget.TextView(this).apply {
                text = e
                textSize = 22f
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { insertAtCursor(e) }
            }
            grid.addView(tv)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("表情")
            .setView(grid)
            .setPositiveButton("完成", null)
            .show()
    }

    private fun insertAtCursor(s: String) {
        val editable = b.etInput.text ?: return
        val start = b.etInput.selectionStart.coerceAtLeast(0)
        val end = b.etInput.selectionEnd.coerceAtLeast(0)
        editable.replace(minOf(start, end), maxOf(start, end), s)
        b.etInput.setSelection(minOf(start, end) + s.length)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pasteImageFromClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return toast("剪贴板是空的")
            val item = clip.getItemAt(0)
            item.uri?.let {
                handleImage(it)
                toast("已粘贴剪贴板图片")
                return
            }
            val coerced = item.coerceToText(this)
            if (coerced is android.graphics.drawable.BitmapDrawable) {
                val bmp = coerced.bitmap
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                pendingImageBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                pendingImageMime = "image/jpeg"
                b.ivPreview.setImageBitmap(bmp)
                b.llImagePreview.visibility = View.VISIBLE
                toast("已粘贴剪贴板图片")
                return
            }
            toast("剪贴板里没有图片")
        } catch (e: Exception) {
            toast("读取剪贴板失败：${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshModelLabel()
        updateSearchTint()
        UpdateUi.resumeInstallIfNeeded(this)
        startClock()
    }

    override fun onPause() {
        super.onPause()
        clockJob?.cancel()
    }

    override fun onDestroy() {
        tts?.shutdown()
        speechRecognizer?.destroy()
        // 协程随生命周期自动取消，这里只需释放录音资源
        configStore.draft = b.etInput.text.toString()
        if (recording) {
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            recorder?.release()
            recorder = null
            recording = false
        }
        super.onDestroy()
    }

    private fun animateForward() {
        if (!animationsEnabled()) return
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_left)
    }

    /** 顶栏头像缓慢呼吸，提示「点我切换角色」 */
    private fun startLogoBreathing() {
        if (!animationsEnabled()) return
        b.ivLogo.animate().cancel()
        b.ivLogo.animate()
            .scaleX(1.06f).scaleY(1.06f)
            .setDuration(1400)
            .setStartDelay(600)
            .withEndAction {
                b.ivLogo.animate().scaleX(1f).scaleY(1f).setDuration(1400).start()
            }
            .start()
    }

    private fun updateSearchTint() {
        val color = ContextCompat.getColor(this, if (b.btnSearch.isChecked) R.color.accent else R.color.icon_tint)
        b.btnSearch.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.CHINESE
            }
        }
    }

    private fun refreshModelLabel() {
        val cfg = configStore.getActiveModel()
        val role = configStore.getActiveRole()
        b.tvModel.text = if (cfg != null) {
            "${cfg.name} · ${cfg.model} · ${role.name}"
        } else {
            "未配置模型 · 去设置添加"
        }
    }

    private fun refreshTitle() {
        b.tvTitle.text = session.title.ifBlank { "新会话" }
    }

    private fun showRolePicker() {
        val roles = configStore.loadRoles()
        val active = configStore.getActiveRole()
        val names = roles.map { if (it.id == active.id) "✓ ${it.name}" else it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择 AI 角色")
            .setItems(names) { _, which ->
                configStore.setActiveRole(roles[which].id)
                refreshModelLabel()
                toast("已切换到「${roles[which].name}」")
            }
            .show()
    }

    private fun newSession() {
        if (streaming) stopGenerating()
        stopSpeaking()
        session = Session()
        newAdapter()
        clearPendingImage()
        refreshTitle()
    }

    private fun loadSession(id: String) {
        val s = sessionStore.load(id) ?: return
        if (streaming) stopGenerating()
        stopSpeaking()
        session = s
        newAdapter()
        refreshTitle()
        b.rvMessages.scrollToPosition(maxOf(0, adapter.itemCount - 1))
    }

    private fun newAdapter() {
        adapter = ChatAdapter(
            session.messages,
            onLongPress = { msg -> onMsgLongPress(msg) },
            onCopyCode = { code ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("code", code))
                toast("已复制代码")
            },
            onOpenLink = { url -> openUrl(url) }
        ).apply { highlight = highlightKey }
        b.rvMessages.adapter = adapter
        adapter.notifyDataSetChanged()
    }

    private fun openUrl(url: String) {
        try {
            val safe = if (url.startsWith("http")) url else "https://$url"
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(safe)))
        } catch (e: Exception) {
            toast("无法打开链接")
        }
    }

    private fun onMsgLongPress(msg: ChatMessage) {
        val idx = session.messages.indexOf(msg)
        val isLastAi = idx == session.messages.size - 1 && msg.role == "assistant"
        val items = mutableListOf("复制内容")
        if (msg.role == "assistant") {
            items.add(if (tts?.isSpeaking == true) "停止朗读" else "朗读")
            if (isLastAi && !streaming) items.add("重新生成")
        }
        if (msg.role == "user" && !streaming) items.add("编辑重发")
        val canDelete = idx >= 0 && (!streaming || idx != adapter.streamingIndex)
        if (canDelete) items.add("删除此消息")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "复制内容" -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("message", msg.content))
                        toast("已复制")
                    }
                    "朗读" -> speak(msg.content)
                    "停止朗读" -> stopSpeaking()
                    "重新生成" -> regenerate()
                    "编辑重发" -> editAndResend(idx)
                    "删除此消息" -> {
                        session.messages.removeAt(idx)
                        adapter.notifyItemRemoved(idx)
                        sessionStore.save(session)
                    }
                }
            }
            .show()
    }

    private fun regenerate() {
        if (streaming) return
        val last = session.messages.lastOrNull()
        if (last == null || last.role != "assistant") {
            toast("只能重新生成最后一条回答")
            return
        }
        session.messages.removeAt(session.messages.size - 1)
        adapter.notifyItemRemoved(session.messages.size)
        val keepSources = last.sources.map { SearchClient.Hit(it.title, it.url, "") }
        callApi(null, keepSources)
    }

    private fun editAndResend(idx: Int) {
        val msg = session.messages[idx]
        b.etInput.setText(msg.content)
        b.etInput.setSelection(msg.content.length)
        val count = session.messages.size - idx
        repeat(count) { session.messages.removeAt(idx) }
        adapter.notifyItemRangeRemoved(idx, count)
        sessionStore.save(session)
        b.etInput.requestFocus()
        updateWelcome()
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            toast("语音引擎初始化中，稍后再试")
            return
        }
        val plain = text.replace(Regex("```[\\s\\S]*?```"), "（代码段）")
            .replace(Regex("[*`#▍]"), "")
        tts?.speak(plain, TextToSpeech.QUEUE_FLUSH, null, "lingxi_tts")
        toast("开始朗读")
    }

    private fun stopSpeaking() {
        tts?.stop()
    }

    private fun onMicClick() {
        if (recording) {
            stopRecordingAndTranscribe()
            return
        }
        if (listening) {
            speechRecognizer?.stopListening()
            setListening(false)
            return
        }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            startSystemVoice()
        } else {
            startWhisperVoice()
        }
    }

    private fun startSystemVoice() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                // 系统识别回调本身就在主线程，这里直接更新 UI
                override fun onEndOfSpeech() { setListening(false) }
                override fun onError(error: Int) {
                    setListening(false)
                    toast("语音识别失败（错误码 $error）")
                }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull()
                    if (!text.isNullOrBlank()) appendToInput(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            speechRecognizer?.startListening(intent)
            setListening(true)
            toast("开始聆听，请说话…")
        } catch (e: Exception) {
            toast("语音识别启动失败：${e.message}")
        }
    }

    private fun startWhisperVoice() {
        val cfg = configStore.getActiveModel()
        val anyKey = configStore.loadModels().any { it.apiKey.isNotBlank() }
        if (cfg == null || (!anyKey && cfg.apiKey.isBlank())) {
            toast("设备无系统语音识别，且未配置任何 API Key。\n请到 设置 → 免费语音识别 添加免费语音服务")
            return
        }
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingSttConfig = cfg
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO
            )
            return
        }
        beginRecording(cfg)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            val cfg = pendingSttConfig
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED && cfg != null) {
                beginRecording(cfg)
            } else {
                toast("未授予录音权限，无法使用云端语音识别")
            }
            pendingSttConfig = null
        }
    }

    private fun beginRecording(cfg: com.lingxi.chat.data.ModelConfig) {
        try {
            val f = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            recordFile = f
            recorder = android.media.MediaRecorder().apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(64000)
                setOutputFile(f.absolutePath)
                prepare()
                start()
            }
            recording = true
            setListening(true)
            recordDialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("云端语音识别")
                .setMessage("正在聆听…说完点「完成」")
                .setPositiveButton("完成") { _, _ -> stopRecordingAndTranscribe() }
                .setNegativeButton("取消") { _, _ -> cancelRecording() }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            recording = false
            setListening(false)
            toast("录音启动失败：${e.message}")
        }
    }

    private fun cancelRecording() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder?.release()
        recorder = null
        recordFile?.delete()
        recordFile = null
        recording = false
        setListening(false)
    }

    private fun stopRecordingAndTranscribe() {
        recordDialog?.dismiss()
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder?.release()
        recorder = null
        recording = false
        setListening(false)
        val f = recordFile ?: return
        toast("正在识别…")
        scope.launch {
            var text: String? = null
            // 依次尝试：当前模型 → 其余已配置且有 Key 的模型（优先带语音模型的）
            val candidates = ArrayList<com.lingxi.chat.data.ModelConfig>()
            configStore.getActiveModel()?.let { candidates.add(it) }
            configStore.loadModels()
                .filter { it.apiKey.isNotBlank() && candidates.none { c -> c.id == it.id } }
                .sortedByDescending { if (it.sttModel.isNotBlank()) 1 else 0 }
                .forEach { candidates.add(it) }
            withContext(Dispatchers.IO) {
                try {
                    for (cfg in candidates) {
                        ensureActive()
                        try {
                            text = transcribeAudio(cfg, f)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            text = null
                        }
                        if (!text.isNullOrBlank()) break
                    }
                } finally {
                    f.delete()
                }
            }
            if (text.isNullOrBlank()) {
                toast("识别失败：已尝试 ${candidates.size} 个模型，均不支持语音接口。可在设置→免费语音识别添加免费服务")
            } else {
                appendToInput(text)
            }
        }
    }

    private fun resolveSttModel(cfg: com.lingxi.chat.data.ModelConfig): String {
        if (cfg.sttModel.isNotBlank()) return cfg.sttModel
        val url = cfg.baseUrl.lowercase()
        return when {
            url.contains("siliconflow") -> "FunAudioLLM/SenseVoiceSmall"
            url.contains("groq") -> "whisper-large-v3-turbo"
            else -> "whisper-1"
        }
    }

    private fun transcribeAudio(cfg: com.lingxi.chat.data.ModelConfig, audio: File): String? {
        val url = cfg.baseUrl.trimEnd('/') + "/audio/transcriptions"
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("model", resolveSttModel(cfg))
            .addFormDataPart(
                "file", audio.name,
                audio.readBytes().toRequestBody("audio/mp4".toMediaType())
            )
            .build()
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = org.json.JSONObject(resp.body?.string() ?: return null)
            return json.optString("text", "").ifBlank { null }
        }
    }

    private fun appendToInput(text: String) {
        val cur = b.etInput.text.toString()
        val merged = if (cur.isBlank()) text else "$cur $text"
        b.etInput.setText(merged)
        b.etInput.setSelection(merged.length)
        b.etInput.requestFocus()
    }

    private fun setListening(on: Boolean) {
        listening = on
        val color = ContextCompat.getColor(
            this,
            if (on) R.color.accent else R.color.icon_tint
        )
        b.btnMic.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    companion object {
        private const val REQ_RECORD_AUDIO = 7101

        val EMOJIS = listOf(
            "😀", "😄", "😂", "😊", "🙂", "😉", "😍", "🥰",
            "😎", "🤔", "😐", "😴", "😪", "😭", "😤", "😡",
            "👍", "👎", "👏", "🙏", "💪", "🤝", "✌️", "👌",
            "🔥", "✨", "🎉", "💡", "📌", "📎", "✅", "❌",
            "❤️", "💙", "💚", "💛", "⭐", "🌟", "☕", "🍀"
        )
    }

    private fun exportSession() {
        if (session.messages.isEmpty()) {
            toast("当前会话还没有内容")
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("导出 / 分享")
            .setItems(
                arrayOf(
                    "分享为 Markdown 文本",
                    "导出 .md 文件",
                    "生成长图（图片）",
                    "复制全文到剪贴板"
                )
            ) { _, which ->
                when (which) {
                    0 -> shareText(buildMarkdownExport())
                    1 -> exportMarkdownFile()
                    2 -> exportLongImage()
                    3 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val sb = StringBuilder()
                        session.messages.forEach { m ->
                            sb.append(if (m.role == "user") "我：" else "灵犀AI：").append(m.content).append("\n\n")
                        }
                        cm.setPrimaryClip(ClipData.newPlainText("session", sb.toString()))
                        toast("已复制全文")
                    }
                }
            }
            .show()
    }

    private fun buildMarkdownExport(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("# ${session.title.ifBlank { "灵犀AI 会话" }}\n\n")
        sb.append("> 导出时间 ${fmt.format(Date())} · 共 ${session.messages.size} 条\n\n")
        session.messages.forEach { m ->
            val who = if (m.role == "user") "用户" else "灵犀AI"
            sb.append("**$who**：${m.content}\n\n---\n\n")
        }
        return sb.toString()
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, session.title.ifBlank { "灵犀AI 会话" })
        }
        startActivity(Intent.createChooser(intent, "导出会话"))
    }

    private fun exportMarkdownFile() {
        try {
            val dir = File(getExternalFilesDir(null) ?: filesDir, "exports").apply { mkdirs() }
            val safe = session.title.ifBlank { "lingxi" }
                .map { if (it in "\\/:*?\"<>|") '_' else it }
                .joinToString("")
                .take(24)
            val f = File(dir, "$safe-${System.currentTimeMillis()}.md")
            f.writeText(buildMarkdownExport(), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "com.lingxi.chat.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享 Markdown 文件"))
        } catch (e: Exception) {
            toast("导出失败：${e.message}")
        }
    }

    /** 整段对话渲染成一张长图：临时搭个 LinearLayout 量高后画到 Bitmap */
    private fun exportLongImage() {
        try {
            val width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
            val surface = ContextCompat.getColor(this, R.color.surface)
            val primary = ContextCompat.getColor(this, R.color.text_primary)
            val secondary = ContextCompat.getColor(this, R.color.text_secondary)
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(surface)
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
            container.addView(android.widget.TextView(this).apply {
                text = session.title.ifBlank { "灵犀AI 会话" }
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(primary)
            })
            container.addView(android.widget.TextView(this).apply {
                text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()) +
                        " · 共 ${session.messages.size} 条"
                textSize = 11f
                setTextColor(secondary)
            })
            session.messages.forEach { m ->
                container.addView(android.widget.TextView(this).apply {
                    text = (if (m.role == "user") "我：\n" else "灵犀AI：\n") + m.content
                    textSize = 13f
                    setTextColor(primary)
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(10), 0, 0)
                })
            }
            container.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val h = container.measuredHeight.coerceIn(dp(200), 20000)
            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(surface)
            container.draw(canvas)
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val f = File(dir, "lingxi-${System.currentTimeMillis()}.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
            bmp.recycle()
            val uri = FileProvider.getUriForFile(this, "com.lingxi.chat.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享长图"))
        } catch (e: Exception) {
            toast("长图生成失败：${e.message}")
        }
    }

    private fun showAttachOptions() {
        val cfg = configStore.getActiveModel()
        val items = mutableListOf("发送文本文件（txt/md/代码等）", "从剪贴板粘贴图片")
        if (cfg?.vision == true) {
            items.add(0, "发送图片")
            items.add(1, "拍照提问")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("添加附件")
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "发送图片" -> pickImage.launch("image/*")
                    "拍照提问" -> launchCamera()
                    "从剪贴板粘贴图片" -> pasteImageFromClipboard()
                    else -> pickFile.launch("*/*")
                }
            }
            .show()
    }

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            cameraFile = File(dir, "shot_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "com.lingxi.chat.fileprovider", cameraFile!!)
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("相机启动失败：${e.message}")
        }
    }

    private fun handleImage(uri: android.net.Uri) {
        try {
            val bytes = if (uri.scheme == "file") {
                FileInputStream(File(uri.path!!)).use { it.readBytes() }
            } else {
                contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 1280 || opts.outHeight / sample > 1280) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            pendingImageBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            pendingImageMime = "image/jpeg"
            b.ivPreview.setImageBitmap(bmp)
            b.llImagePreview.visibility = View.VISIBLE
        } catch (e: Exception) {
            toast("图片读取失败：${e.message}")
        }
    }

    private fun handleFile(uri: android.net.Uri) {
        try {
            val name = queryFileName(uri) ?: "文件"
            val stream = contentResolver.openInputStream(uri) ?: return
            val bytes = stream.readBytes()
            stream.close()
            if (bytes.size > 200 * 1024) {
                toast("文件太大，最多 200KB 的文本文件")
                return
            }
            val text = String(bytes, Charsets.UTF_8).take(20000)
            pendingFileText = text
            pendingFileName = name
            b.tvFileName.text = name
            b.llFilePreview.visibility = View.VISIBLE
            toast("已附加文件 $name，发送时会一起提交")
        } catch (e: Exception) {
            toast("只支持文本类文件（txt/md/代码/json 等）")
        }
    }

    private fun queryFileName(uri: android.net.Uri): String? {
        val c = contentResolver.query(uri, null, null, null, null) ?: return null
        c.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return null
    }

    private fun clearPendingImage() {
        pendingImageBase64 = null
        pendingImageMime = null
        b.llImagePreview.visibility = View.GONE
    }

    private fun send() {
        if (streaming) return
        var text = b.etInput.text.toString().trim()
        val img = pendingImageBase64
        val fileText = pendingFileText
        val fileName = pendingFileName

        if (text.isEmpty() && img == null && fileText == null) return
        val cfg = configStore.getActiveModel()
        if (cfg == null) {
            toast("请先在设置里添加模型配置")
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (cfg.apiKey.isBlank()) {
            toast("当前模型配置还没填 API Key")
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (img != null && !cfg.vision) {
            toast("当前模型不支持图片，去设置里换视觉模型或勾选「支持图片理解」")
            return
        }
        if (fileText != null && fileName != null) {
            text = "以下是文件「$fileName」的内容：\n```\n$fileText\n```\n\n$text".trim()
        }

        val userMsg = ChatMessage("user", text, img, pendingImageMime)
        session.messages.add(userMsg)
        if (session.title.isBlank()) {
            session.title = (if (text.isNotBlank()) text else "图片对话").take(20)
            refreshTitle()
        }
        adapter.notifyItemInserted(session.messages.size - 1)
        b.rvMessages.scrollToPosition(adapter.itemCount - 1)
        b.etInput.setText("")
        configStore.draft = ""
        clearPendingImage()
        pendingFileText = null
        pendingFileName = null
        b.llFilePreview.visibility = View.GONE
        hideKeyboard()
        sessionStore.save(session)

        val useSearch = b.btnSearch.isChecked
        if (useSearch) {
            setStreaming(true)
            val searchingMsg = ChatMessage("assistant", "正在联网搜索…")
            session.messages.add(searchingMsg)
            adapter.notifyItemInserted(session.messages.size - 1)
            b.rvMessages.scrollToPosition(adapter.itemCount - 1)
            searchingMessage = searchingMsg
            searchJob = scope.launch {
                val hits = withContext(Dispatchers.IO) {
                    try {
                        SearchClient.searchHits(text.take(200))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                searchJob = null
                searchingMessage = null
                session.messages.remove(searchingMsg)
                adapter.notifyDataSetChanged()
                if (hits.isEmpty()) {
                    setStreaming(false)
                    appendError("联网搜索失败，已转为直接回答")
                    callApi(null, emptyList())
                } else {
                    callApi(
                        hits.joinToString("\n\n") { h -> "${h.title}\n${h.url}\n${h.snippet}" },
                        hits
                    )
                }
            }
        } else {
            callApi(null, emptyList())
        }
    }

    private fun callApi(searchContext: String?, sources: List<SearchClient.Hit>) {
        val cfg = configStore.getActiveModel() ?: return
        setStreaming(true)

        val apiMsgs = mutableListOf<ChatMessage>()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        var sysPrompt = configStore.getActiveRole().prompt + "\n今天是 $today。"
        if (searchContext != null) {
            sysPrompt += "\n\n以下是针对用户问题联网搜索到的最新资料，请结合资料回答，并在结尾列出参考来源链接：\n$searchContext"
        }
        apiMsgs.add(ChatMessage("system", sysPrompt))
        apiMsgs.addAll(session.messages)

        val aiMsg = ChatMessage("assistant", "")
        aiMsg.sources.addAll(sources.map { ChatMessage.Source(it.title, it.url) })
        session.messages.add(aiMsg)
        val aiIndex = session.messages.size - 1
        adapter.streamingIndex = aiIndex
        adapter.notifyItemInserted(aiIndex)
        b.rvMessages.scrollToPosition(aiIndex)
        startThinkingTicker(aiIndex)
        revealedLen = 0
        startTypewriter(aiMsg)

        // 收集流式响应：取消本协程即中断网络请求
        streamJob = scope.launch {
            client.streamChat(cfg, apiMsgs)
                .catch { emit(OpenAiClient.Event.Failed("出错：${it.message ?: "请求失败"}")) }
                .collect { ev ->
                when (ev) {
                    is com.lingxi.chat.net.OpenAiClient.Event.Delta -> {
                        if (ev.text.isNotEmpty()) aiMsg.content += ev.text
                    }

                    com.lingxi.chat.net.OpenAiClient.Event.Done -> {
                        stopTypewriter()
                        adapter.flushStreaming(aiIndex)
                        sessionStore.save(session)
                        setStreaming(false)
                    }

                    is com.lingxi.chat.net.OpenAiClient.Event.Failed -> {
                        if (aiMsg.content.isBlank()) {
                            aiMsg.content = "出错了：${ev.message}"
                        } else {
                            aiMsg.content += "\n\n[中断：${ev.message}]"
                        }
                        stopTypewriter()
                        adapter.flushStreaming(aiIndex)
                        sessionStore.save(session)
                        setStreaming(false)
                    }
                }
            }
        }
    }

    /** 当前流式请求的收集协程，用于「停止生成」 */
    private var streamJob: kotlinx.coroutines.Job? = null

    /** 打字机：每 24ms 多显示几个字，积压越多追得越快，长回答不至于等太久 */
    private fun startTypewriter(aiMsg: ChatMessage) {
        stopTypewriter()
        caretVisible = true
        startCaretBlink()
        typeJob = scope.launch {
            while (isActive) {
                if (!streaming) return@launch
                val full = aiMsg.content
                if (revealedLen < full.length) {
                    val backlog = full.length - revealedLen
                    revealedLen = minOf(full.length, revealedLen + maxOf(2, backlog / 8))
                    adapter.updateStreamingText(full.substring(0, revealedLen), caretVisible)
                    scrollToEndIfNearBottom()
                }
                delay(24)
            }
        }
    }

    private fun stopTypewriter() {
        typeJob?.cancel()
        typeJob = null
        caretJob?.cancel()
        caretJob = null
    }

    /** 点「停止」：取消收集协程即掐断网络流，已收到的内容定稿，空气泡直接撤掉 */
    private fun stopGenerating() {
        if (!streaming) return
        // 取消收集 → callbackFlow 的 awaitClose 触发 → call.cancel()，无需等超时
        searchJob?.cancel()
        searchJob = null
        searchingMessage?.let { msg ->
            val searchIndex = session.messages.indexOfFirst { it === msg }
            if (searchIndex >= 0) {
                session.messages.removeAt(searchIndex)
                adapter.notifyItemRemoved(searchIndex)
                sessionStore.save(session)
            }
        }
        searchingMessage = null
        streamJob?.cancel()
        if (streamJob != null) client.cancel()
        streamJob = null
        stopTypewriter()
        val idx = adapter.streamingIndex
        if (idx in session.messages.indices) {
            val m = session.messages[idx]
            if (m.role == "assistant" && m.content.isBlank()) {
                session.messages.removeAt(idx)
                adapter.notifyItemRemoved(idx)
            } else {
                adapter.flushStreaming(idx)
            }
            sessionStore.save(session)
        }
        setStreaming(false)
        toast("已停止生成")
    }

    private fun appendError(text: String) {
        val m = ChatMessage("assistant", text)
        session.messages.add(m)
        adapter.notifyItemInserted(session.messages.size - 1)
        b.rvMessages.scrollToPosition(adapter.itemCount - 1)
    }

    private fun setStreaming(on: Boolean) {
        streaming = on
        b.btnSend.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_send)
        if (!on) {
            thinkingJob?.cancel()
            thinkingJob = null
            adapter.streamingIndex = -1
        }
    }

    private fun updateWelcome() {
        b.llWelcome.visibility = if (session.messages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun scrollToEndIfNearBottom() {
        val lm = b.rvMessages.layoutManager as LinearLayoutManager
        val last = lm.findLastVisibleItemPosition()
        if (last >= adapter.itemCount - 3) {
            b.rvMessages.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.etInput.windowToken, 0)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
