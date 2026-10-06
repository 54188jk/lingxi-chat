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

    private val openSessions =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK) {
                r.data?.getStringExtra("session_id")?.let { loadSession(it) }
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

        adapter = ChatAdapter(session.messages) { msg -> onMsgLongPress(msg) }
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
        }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.btnShare.setOnClickListener { exportSession() }
        b.root.findViewById<android.widget.ImageView>(R.id.ivLogo).setOnClickListener { showRolePicker() }

        b.btnAttach.setOnClickListener { showAttachOptions() }
        b.btnRemoveImage.setOnClickListener { clearPendingImage() }
        b.btnMic.setOnClickListener { toggleVoiceInput() }
        b.btnSearch.isChecked = configStore.searchEnabled
        b.btnSearch.setOnCheckedChangeListener { _, checked ->
            configStore.searchEnabled = checked
            if (checked && configStore.searchKey.isBlank()) {
                toast("还没配置搜索服务，去设置里填一下搜索 API Key")
            }
        }

        b.btnSend.setOnClickListener {
            if (streaming) client.cancel() else send()
        }

        UpdateUi.check(this, BuildConfig.VERSION_NAME, silent = true)
    }

    override fun onResume() {
        super.onResume()
        refreshModelLabel()
        UpdateUi.resumeInstallIfNeeded(this)
    }

    override fun onDestroy() {
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
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
            "点右上角齿轮，先添加模型配置 · ${role.name}"
        }
    }

    private fun refreshTitle() {
        b.tvTitle.text = session.title.ifBlank { "新会话" }
    }

    private fun showRolePicker() {
        val roles = configStore.loadRoles()
        val active = configStore.getActiveRole()
        val names = roles.map { if (it.id == active.id) "✓ ${it.name}" else it.name }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("选择 AI 角色")
            .setItems(names) { _, which ->
                configStore.setActiveRole(roles[which].id)
                refreshModelLabel()
                toast("已切换到「${roles[which].name}」")
            }
            .show()
    }

    private fun newSession() {
        if (streaming) client.cancel()
        stopSpeaking()
        session = Session()
        newAdapter()
        clearPendingImage()
        refreshTitle()
    }

    private fun loadSession(id: String) {
        val s = sessionStore.load(id) ?: return
        if (streaming) client.cancel()
        stopSpeaking()
        session = s
        newAdapter()
        refreshTitle()
        b.rvMessages.scrollToPosition(maxOf(0, adapter.itemCount - 1))
    }

    private fun newAdapter() {
        adapter = ChatAdapter(session.messages) { msg -> onMsgLongPress(msg) }
        b.rvMessages.adapter = adapter
        adapter.notifyDataSetChanged()
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

        android.app.AlertDialog.Builder(this)
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
        callApi(null)
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

    private fun toggleVoiceInput() {
        if (listening) {
            speechRecognizer?.stopListening()
            setListening(false)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("当前设备不支持语音识别")
            return
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { runOnUiThread { setListening(false) } }
                override fun onError(error: Int) {
                    runOnUiThread {
                        setListening(false)
                        toast("语音识别失败（错误码 $error）")
                    }
                }
                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = list?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        val cur = b.etInput.text.toString()
                        val merged = if (cur.isBlank()) text else "$cur $text"
                        b.etInput.setText(merged)
                        b.etInput.setSelection(merged.length)
                    }
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

    private fun setListening(on: Boolean) {
        listening = on
        val color = ContextCompat.getColor(
            this,
            if (on) R.color.accent else R.color.icon_tint
        )
        b.btnMic.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    private fun exportSession() {
        if (session.messages.isEmpty()) {
            toast("当前会话还没有内容")
            return
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("# ${session.title.ifBlank { "灵犀AI 会话" }}\n\n")
        sb.append("> 导出时间 ${fmt.format(Date())} · 共 ${session.messages.size} 条\n\n")
        session.messages.forEach { m ->
            val who = if (m.role == "user") "用户" else "灵犀AI"
            sb.append("**$who**：${m.content}\n\n---\n\n")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
            putExtra(Intent.EXTRA_TITLE, session.title.ifBlank { "灵犀AI 会话" })
        }
        startActivity(Intent.createChooser(intent, "导出会话"))
    }

    private fun showAttachOptions() {
        val cfg = configStore.getActiveModel()
        val items = mutableListOf("发送文本文件（txt/md/代码等）")
        if (cfg?.vision == true) {
            items.add(0, "发送图片")
            items.add(1, "拍照提问")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("添加附件")
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "发送图片" -> pickImage.launch("image/*")
                    "拍照提问" -> launchCamera()
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
            val cur = b.etInput.text.toString()
            if (!cur.contains("[文件：$name]")) {
                b.etInput.setText("[文件：$name]\n$cur")
                b.etInput.setSelection(b.etInput.text.length)
            }
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
            text = text.replace("[文件：$fileName]", "").trim()
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
        clearPendingImage()
        pendingFileText = null
        pendingFileName = null
        hideKeyboard()

        val useSearch = b.btnSearch.isChecked && configStore.searchKey.isNotBlank()
        if (useSearch) {
            setStreaming(true)
            val searchingMsg = ChatMessage("assistant", "正在联网搜索…")
            session.messages.add(searchingMsg)
            adapter.notifyItemInserted(session.messages.size - 1)
            b.rvMessages.scrollToPosition(adapter.itemCount - 1)
            Thread {
                val result = try {
                    SearchClient.search(configStore.searchProvider, configStore.searchKey, text.take(200))
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    session.messages.remove(searchingMsg)
                    adapter.notifyDataSetChanged()
                    if (result == null) {
                        setStreaming(false)
                        appendError("联网搜索失败，已转为直接回答")
                        callApi(null)
                    } else {
                        callApi(result)
                    }
                }
            }.start()
        } else {
            callApi(null)
        }
    }

    private fun callApi(searchContext: String?) {
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
        session.messages.add(aiMsg)
        val aiIndex = session.messages.size - 1
        adapter.streamingIndex = aiIndex
        adapter.notifyItemInserted(aiIndex)
        b.rvMessages.scrollToPosition(aiIndex)

        client.streamChat(
            cfg, apiMsgs,
            onDelta = { delta ->
                aiMsg.content += delta
                val now = System.currentTimeMillis()
                if (now - lastNotifyTime > 80) {
                    lastNotifyTime = now
                    runOnUiThread {
                        adapter.notifyItemChanged(aiIndex)
                        scrollToEndIfNearBottom()
                    }
                }
            },
            onDone = {
                runOnUiThread {
                    adapter.notifyItemChanged(aiIndex)
                    sessionStore.save(session)
                    setStreaming(false)
                }
            },
            onError = { err ->
                runOnUiThread {
                    if (aiMsg.content.isBlank()) {
                        aiMsg.content = "出错了：$err"
                    } else {
                        aiMsg.content += "\n\n[中断：$err]"
                    }
                    adapter.notifyItemChanged(aiIndex)
                    sessionStore.save(session)
                    setStreaming(false)
                }
            }
        )
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
