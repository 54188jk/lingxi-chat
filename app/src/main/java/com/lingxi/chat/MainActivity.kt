package com.lingxi.chat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.lingxi.chat.data.ChatMessage
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.data.Session
import com.lingxi.chat.data.SessionStore
import com.lingxi.chat.databinding.ActivityMainBinding
import com.lingxi.chat.net.OpenAiClient
import com.lingxi.chat.net.SearchClient
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        configStore = ConfigStore(this)
        sessionStore = SessionStore(this)

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

        b.btnAttach.setOnClickListener { showAttachOptions() }
        b.btnRemoveImage.setOnClickListener { clearPendingImage() }
        b.btnSearch.isChecked = configStore.searchEnabled
        b.btnSearch.setOnCheckedChangeListener { _, checked ->
            configStore.searchEnabled = checked
            if (checked && configStore.searchKey.isBlank()) {
                toast("还没配置搜索服务，去设置里填一下搜索 API Key")
            }
        }

        b.btnSend.setOnClickListener {
            if (streaming) {
                client.cancel()
            } else {
                send()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshModelLabel()
    }

    private fun refreshModelLabel() {
        val cfg = configStore.getActiveModel()
        b.tvModel.text = if (cfg != null) "${cfg.name} · ${cfg.model}" else "点右上角齿轮，先添加模型配置"
    }

    private fun refreshTitle() {
        b.tvTitle.text = session.title.ifBlank { "新会话" }
    }

    private fun newSession() {
        if (streaming) client.cancel()
        session = Session()
        newAdapter()
        clearPendingImage()
        refreshTitle()
    }

    private fun loadSession(id: String) {
        val s = sessionStore.load(id) ?: return
        if (streaming) client.cancel()
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
        val canDelete = idx >= 0 && (!streaming || idx != adapter.streamingIndex)
        val items = if (canDelete) arrayOf("复制内容", "删除此消息") else arrayOf("复制内容")
        android.app.AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (items[which]) {
                    "复制内容" -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("message", msg.content))
                        toast("已复制")
                    }
                    "删除此消息" -> {
                        session.messages.removeAt(idx)
                        adapter.notifyItemRemoved(idx)
                        sessionStore.save(session)
                    }
                }
            }
            .show()
    }

    private fun scrollToEndIfNearBottom() {
        val lm = b.rvMessages.layoutManager as LinearLayoutManager
        val last = lm.findLastVisibleItemPosition()
        if (last >= adapter.itemCount - 3) {
            b.rvMessages.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun showAttachOptions() {
        val cfg = configStore.getActiveModel()
        val items = mutableListOf("发送文本文件（txt/md/代码等）")
        if (cfg?.vision == true) items.add(0, "发送图片")
        android.app.AlertDialog.Builder(this)
            .setTitle("添加附件")
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "发送图片" -> pickImage.launch("image/*")
                    else -> pickFile.launch("*/*")
                }
            }
            .show()
    }

    private fun handleImage(uri: android.net.Uri) {
        try {
            val stream = contentResolver.openInputStream(uri) ?: return
            val bytes = stream.readBytes()
            stream.close()
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
        var sysPrompt = "你是「灵犀AI」手机助手，用简体中文回答，回答简洁清晰。今天是 $today。"
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

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.etInput.windowToken, 0)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
