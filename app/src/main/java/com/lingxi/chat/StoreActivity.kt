package com.lingxi.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lingxi.chat.control.FileOps
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.databinding.ActivityStoreBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 手机桌面的「应用商店」：三条路都能装上应用。
 * 一是用户自己填的 APK 直链（下载 → 检查包是否完整 → 交系统安装），
 * 二是扫本机已有的安装包，三是列出手机上装好的应用并直接打开。
 */
class StoreActivity : BaseActivity() {

    private lateinit var b: ActivityStoreBinding
    private val store by lazy { ConfigStore(this) }
    private val density by lazy { resources.displayMetrics.density }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private data class AppItem(val name: String, val url: String, val size: Long)

    /** 正在下载的应用 → 它的副标题，用来回填进度 */
    private val progress = HashMap<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStoreBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnAddApp.setOnClickListener { askAddApp() }
        b.btnRescanApks.setOnClickListener { scanLocalApks() }
        b.btnOpenInstalled.setOnClickListener { listInstalled() }

        renderApps()
        scanLocalApks()
    }

    // ---------------- 商店列表 ----------------

    private fun loadApps(): List<AppItem> = runCatching {
        val arr = JSONArray(store.storeApps)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AppItem(o.optString("name"), o.optString("url"), o.optLong("size", 0L))
        }
    }.getOrDefault(emptyList())

    private fun saveApps(list: List<AppItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("url", it.url).put("size", it.size))
        }
        store.storeApps = arr.toString()
    }

    private fun renderApps() {
        val items = loadApps()
        b.tvStoreEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        b.llApps.removeAllViews()
        progress.clear()
        items.forEach { item ->
            b.llApps.addView(
                row(item.name, sizeText(item.size) + " · " + hostOf(item.url), "下载安装",
                    onClick = { download(item) },
                    onLong = { askRemove(item) },
                    subView = { tv -> progress[item.url] = tv })
            )
        }
    }

    private fun askAddApp() {
        val pad = (18 * density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val name = EditText(this).apply {
            hint = "名字（比如：某播放器）"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
        }
        val link = EditText(this).apply {
            hint = "APK 下载地址（以 .apk 结尾）"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            textSize = 14f
        }
        box.addView(name)
        box.addView(link)
        AlertDialog.Builder(this)
            .setTitle("添加一个应用")
            .setMessage("只填以 .apk 结尾的直链。下载完糯叽会先检查包是否完整，再交给你确认安装。")
            .setView(box)
            .setPositiveButton("加进来") { _, _ ->
                val n = name.text.toString().trim()
                val u = link.text.toString().trim()
                when {
                    n.isEmpty() -> toast("先给个名字")
                    !u.startsWith("https://") -> toast("地址要以 https:// 开头")
                    !u.lowercase(Locale.US).endsWith(".apk") -> toast("这不像安装包地址（应以 .apk 结尾）")
                    else -> {
                        saveApps(loadApps() + AppItem(n, u, 0L))
                        renderApps()
                        toast("已经加进商店了")
                    }
                }
            }
            .setNegativeButton("算了", null)
            .show()
    }

    private fun askRemove(item: AppItem) {
        AlertDialog.Builder(this)
            .setTitle("把「${item.name}」从商店里去掉？")
            .setMessage("只是不再显示这一条，已经装好的应用不受影响。")
            .setPositiveButton("去掉") { _, _ ->
                saveApps(loadApps().filterNot { it.url == item.url })
                renderApps()
            }
            .setNegativeButton("留着", null)
            .show()
    }

    // ---------------- 下载 ----------------

    private fun download(item: AppItem) {
        progress[item.url]?.text = "准备下载…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetch(item) }
            when (result.first) {
                null -> {
                    toast("下载失败：${result.second}")
                    progress.remove(item.url)
                    renderApps()
                }
                else -> {
                    progress.remove(item.url)
                    val apk = result.first!!
                    val bad = UpdateUi.checkApk(apk, item.size)
                    if (bad.isNotEmpty()) {
                        apk.delete()
                        toast("安装包不对，没有交给你安装：$bad")
                    } else {
                        UpdateUi.installFile(this@StoreActivity, apk)
                    }
                }
            }
        }
    }

    /** 下到缓存目录，边下边把进度写在条目副标题上 */
    private fun fetch(item: AppItem): Pair<File?, String> {
        val dir = File(cacheDir, "store").apply { if (!exists()) mkdirs() }
        val out = File(dir, safeName(item.name) + ".apk")
        runCatching { out.delete() }
        return runCatching {
            val req = Request.Builder().url(item.url).header("User-Agent", "LingxiChat-Android").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null to "对方返回 ${resp.code}"
                val body = resp.body ?: return@runCatching null to "对方没给内容"
                val total = if (item.size > 0) item.size else body.contentLength()
                body.byteStream().use { input ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            if (total > 0 && done % (512 * 1024L) < 64 * 1024L) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 99)
                                runOnUiThread { progress[item.url]?.text = "下载中 $pct%" }
                            }
                        }
                    }
                }
                out to ""
            }
        }.getOrElse { null to (it.message ?: "网络断了") }
    }

    private fun safeName(raw: String): String =
        raw.trim().map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("").ifBlank { "app" }

    // ---------------- 本机安装包 ----------------

    private fun scanLocalApks() {
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { FileOps.searchNodes(this@StoreActivity, "", ".apk", 60) }
            b.llLocalApks.removeAllViews()
            if (found.isEmpty()) {
                b.tvApkNote.text = if (FileOps.canUseStorage(this@StoreActivity)) {
                    "内部储存里暂时没找到安装包。"
                } else {
                    "要扫本机安装包，请先在设置里给糯叽「所有文件访问」权限。"
                }
                return@launch
            }
            b.tvApkNote.text = "找到 ${found.size} 个，点一下就装（装之前会先检查包是否完整）。"
            found.forEach { node ->
                b.llLocalApks.addView(
                    row(node.name, FileOps.sizeText(node.size) + " · " + FileOps.display(node.path), "安装",
                        onClick = {
                            val bad = UpdateUi.checkApk(node.file, 0L)
                            if (bad.isNotEmpty()) toast("这个安装包不完整：$bad")
                            else UpdateUi.installFile(this@StoreActivity, node.file)
                        })
                )
            }
        }
    }

    // ---------------- 已安装应用 ----------------

    private fun listInstalled() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val listed = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        }.getOrDefault(emptyList())
        b.llInstalled.removeAllViews()
        val seen = LinkedHashSet<Pair<String, String>>()
        listed.forEach { info ->
            val pkg = info.activityInfo?.packageName ?: return@forEach
            if (pkg == packageName) return@forEach
            val label = info.loadLabel(pm).toString()
            seen.add(label to pkg)
        }
        val panel = b.llInstalled
        if (seen.isEmpty()) {
            panel.addView(note("没找到能直接打开的应用。手机上装了应用却不显示，通常是系统限制了这里能看到的范围。"))
            return
        }
        panel.addView(note("共 " + seen.size + " 个。点一下就把那个应用带到前台。"))
        val sorted = seen.toList().sortedBy { it.first.lowercase(Locale.US) }
        for (pair in sorted) {
            val label = pair.first
            val pkg = pair.second
            panel.addView(
                row(label, pkg, "打开", onClick = {
                    val i = packageManager.getLaunchIntentForPackage(pkg)
                    if (i == null) toast("这个应用不让外部打开")
                    else runCatching { startActivity(i) }.onFailure { toast("打不开：" + it.message) }
                })
            )
        }
    }

    // ---------------- 通用行 ----------------

    private fun row(
        title: String,
        sub: String,
        action: String,
        onClick: () -> Unit,
        onLong: (() -> Unit)? = null,
        subView: ((TextView) -> Unit)? = null
    ): View {
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        line.background = ContextCompat.getDrawable(this, R.drawable.bg_chip)
        val h = (12 * density).toInt()
        line.setPadding((14 * density).toInt(), h, (12 * density).toInt(), h)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (8 * density).toInt()
        line.layoutParams = lp

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val t1 = TextView(this)
        t1.text = title
        t1.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        t1.textSize = 15f
        t1.isSingleLine = true
        val t2 = TextView(this)
        t2.text = sub
        t2.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        t2.textSize = 11f
        t2.maxLines = 2
        t2.setPadding(0, (2 * density).toInt(), 0, 0)
        col.addView(t1)
        col.addView(t2)
        line.addView(col)
        subView?.invoke(t2)

        val btn = TextView(this)
        btn.text = action
        btn.setTextColor(ContextCompat.getColor(this, R.color.accent))
        btn.textSize = 13f
        btn.gravity = Gravity.CENTER
        btn.setBackgroundResource(R.drawable.bg_chip)
        btn.setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        btnLp.marginStart = (10 * density).toInt()
        btn.layoutParams = btnLp
        btn.setOnClickListener { onClick() }
        line.addView(btn)

        if (onLong != null) {
            line.setOnLongClickListener { onLong(); true }
        }
        return line
    }

    private fun note(text: String): View {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        tv.textSize = 12f
        val p = (4 * density).toInt()
        tv.setPadding(0, p, 0, p)
        return tv
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)

    private fun sizeText(bytes: Long): String = when {
        bytes <= 0 -> "大小未知"
        bytes >= 1_048_576 -> "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
        else -> "%.0f KB".format(Locale.US, bytes / 1024.0)
    }
}
