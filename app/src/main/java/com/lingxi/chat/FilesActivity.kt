package com.lingxi.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lingxi.chat.control.FileOps
import com.lingxi.chat.data.ConfigStore
import com.lingxi.chat.databinding.ActivityFilesBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文件与文件夹：在自己手机上建文件夹、改名、挪动、复制、删进回收站、还原、搜索。
 * 只走内部储存和本应用目录，删除一律先进回收站，用户随时能还原。
 */
class FilesActivity : BaseActivity() {

    private lateinit var b: ActivityFilesBinding
    private val store by lazy { ConfigStore(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var path = "内部储存"
    private var nodes: List<FileOps.Node> = emptyList()
    private var filter = ""
    private var pending: Pending? = null

    private class Pending(val op: String, val node: FileOps.Node)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFilesBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.rvFiles.layoutManager = LinearLayoutManager(this)
        b.rvFiles.adapter = adapter
        b.btnBack.setOnClickListener { finish() }
        b.btnNewFolder.setOnClickListener { askNewFolder(path) }
        b.btnEmptyAction.setOnClickListener { askNewFolder(path) }
        b.btnSort.setOnClickListener {
            store.fileSort = (store.fileSort + 1) % 3
            refresh()
        }
        b.btnTrash.setOnClickListener { go("回收站") }
        b.btnAsk.setOnClickListener { handToAgent() }
        b.btnSearch.setOnClickListener {
            val show = b.llSearchRow.visibility != View.VISIBLE
            b.llSearchRow.visibility = if (show) View.VISIBLE else View.GONE
            if (show) b.etSearch.requestFocus()
        }
        b.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {
                filter = s?.toString().orEmpty().trim()
                refresh()
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        b.tvSearchDeep.setOnClickListener { deepSearch() }
        b.tvPath.setOnClickListener { goUp() }
        b.tvPath.setOnLongClickListener {
            go("内部储存")
            true
        }
        b.tvPending.setOnClickListener { clearPending() }
        b.tvStorageWarn.setOnClickListener { askStoragePermission() }

        buildPlaces()
        savedInstanceState?.getString("path")?.let { path = it }
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("path", path)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- 顶部去处 ----------------

    private fun buildPlaces() {
        b.llPlaces.removeAllViews()
        val density = resources.displayMetrics.density
        val hereCanonical = FileOps.resolve(this, path).file?.path ?: ""
        FileOps.roots(this).forEach { (name, dir) ->
            val chip = TextView(this).apply {
                text = name
                textSize = 11f
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        this@FilesActivity,
                        if (dir.path == hereCanonical) R.color.accent else R.color.text_secondary
                    )
                )
                setBackgroundResource(R.drawable.bg_chip)
                setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
                setOnClickListener { go(name) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (6 * density).toInt()
            b.llPlaces.addView(chip, lp)
        }
    }

    // ---------------- 列表 ----------------

    private val adapter = object : RecyclerView.Adapter<FilesHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            FilesHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

        override fun getItemCount() = nodes.size

        override fun onBindViewHolder(holder: FilesHolder, position: Int) {
            val n = nodes[position]
            holder.name.text = n.name
            holder.meta.text = buildString {
                append(if (n.isDir) "文件夹" else FileOps.sizeText(n.size))
                append(" · ").append(FileOps.timeText(n.modified))
                if (!n.canWrite) append(" · 只读")
            }
            holder.icon.setImageResource(if (n.isDir) R.drawable.ic_folder else R.drawable.ic_file)
            holder.root.setOnClickListener {
                if (n.isDir) go(n.path) else FileOps.run(this@FilesActivity, "open", n.path, "").let { d ->
                    if (!d.ok) toast(d.note)
                }
            }
            holder.more.setOnClickListener { showMenu(n) }
        }
    }

    class FilesHolder(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v
        val icon: android.widget.ImageView = v.findViewById(R.id.ivIcon)
        val name: TextView = v.findViewById(R.id.tvName)
        val meta: TextView = v.findViewById(R.id.tvMeta)
        val more: TextView = v.findViewById(R.id.btnMore)
    }

    private fun go(to: String) {
        path = to
        filter = ""
        b.etSearch.setText("")
        buildPlaces()
        refresh()
    }

    private fun refresh() {
        b.btnSort.text = when (store.fileSort) {
            1 -> "按时间"
            2 -> "按大小"
            else -> "按名字"
        }
        val target = path
        scope.launch {
            val (list, err) = withContext(Dispatchers.IO) { FileOps.list(this@FilesActivity, target, store.fileSort, filter) }
            if (isFinishing || isDestroyed) return@launch
            if (err.isNotEmpty()) {
                toast(err)
                nodes = emptyList()
            } else {
                nodes = list
            }
            b.tvPath.text = FileOps.display(
                FileOps.resolve(this@FilesActivity, target).file?.path ?: target
            ) + if (filter.isNotBlank()) " · 筛选「$filter」" else ""
            b.llEmpty.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
            b.tvEmpty.text = if (filter.isNotBlank()) "这里没有名字带「$filter」的东西" else "这个文件夹是空的"
            b.btnEmptyAction.visibility = if (filter.isBlank()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
            val inTrash = FileOps.isTrash(this@FilesActivity, target)
            b.btnEmptyAction.text = if (inTrash) "关闭回收站" else "新建一个文件夹"
            if (inTrash) b.btnEmptyAction.setOnClickListener { go("内部储存") } else b.btnEmptyAction.setOnClickListener { askNewFolder(path) }
            b.tvFree.text = withContext(Dispatchers.IO) { "存储剩余：" + FileOps.freeGb(this@FilesActivity) }
            val can = withContext(Dispatchers.IO) { FileOps.canUseStorage(this@FilesActivity) }
            b.tvStorageWarn.visibility = if (can) View.GONE else View.VISIBLE
            if (!can) {
                b.tvStorageWarn.text = "还没给存储权限，现在只能整理灵犀自己的文件夹。点这里去开启（开启后才能整理下载、文档这些位置）"
            }
            drawPending()
        }
    }

    // ---------------- 单条操作 ----------------

    private fun showMenu(n: FileOps.Node) {
        val trash = FileOps.isTrash(this, path)
        val items = when {
            n.isDir && trash -> arrayOf("打开", "还原到原来的位置", "彻底删除", "在里面新建文件夹", "属性")
            trash -> arrayOf("还原到原来的位置", "彻底删除", "属性")
            n.isDir -> arrayOf("打开", "在里面新建文件夹", "重命名", "复制到…", "移动到…", "删除（放进回收站）", "属性")
            FileOps.looksLikeText(n.name) -> arrayOf("打开", "看内容", "重命名", "复制到…", "移动到…", "删除（放进回收站）", "属性")
            else -> arrayOf("打开", "重命名", "复制到…", "移动到…", "删除（放进回收站）", "属性")
        }
        AlertDialog.Builder(this)
            .setTitle(n.name)
            .setItems(items) { _, which ->
                val pick = items[which]
                when {
                    pick.startsWith("打开") -> onOpen(n)
                    pick.startsWith("看内容") -> showText(n)
                    pick.startsWith("还原") -> run("还原") { FileOps.restore(this, n.path) }
                    pick.startsWith("彻底删除") -> confirmPurge(n)
                    pick.startsWith("在里面新建") -> askNewFolder(n.path)
                    pick.startsWith("重命名") -> askRename(n)
                    pick.startsWith("复制到") -> setPending("copy", n)
                    pick.startsWith("移动到") -> setPending("move", n)
                    pick.startsWith("删除") -> confirmDelete(n)
                    pick.startsWith("属性") -> AlertDialog.Builder(this)
                        .setTitle(n.name)
                        .setMessage(FileOps.stat(this, n.path).note)
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onOpen(n: FileOps.Node) {
        if (n.isDir) go(n.path)
        else {
            val d = FileOps.run(this, "open", n.path, "")
            if (!d.ok) toast(d.note)
        }
    }

    private fun confirmDelete(n: FileOps.Node) {
        AlertDialog.Builder(this)
            .setTitle(if (n.isDir) "删除这个文件夹？" else "删除这个文件？")
            .setMessage(
                "「${n.name}」会先放进回收站，不会马上消失。\n" +
                        "之后在 文件与文件夹 → 回收站 里还能还原。"
            )
            .setPositiveButton("放进回收站") { _, _ -> run("删除") { FileOps.trash(this, n.path) } }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmPurge(n: FileOps.Node) {
        AlertDialog.Builder(this)
            .setTitle("彻底删除？")
            .setMessage("「${n.name}」已经在回收站里。彻底删除后就找不回来了，确定吗？")
            .setPositiveButton("彻底删除") { _, _ -> run("彻底删除") { FileOps.purge(this, n.path) } }
            .setNegativeButton("再想想", null)
            .show()
    }

    private fun askNewFolder(inPath: String) {
        askText("新建文件夹", "位置：${FileOps.display(FileOps.resolve(this, inPath).file?.path ?: inPath)}", "新文件夹名字") { name ->
            if (name.isBlank()) {
                toast("名字不能空着")
                return@askText
            }
            run("新建文件夹") { FileOps.mkdir(this, FileOps.child(this, inPath, name)) }
        }
    }

    private fun askRename(n: FileOps.Node) {
        askText("重命名", "当前名字：${n.name}", "改成什么名字") { name ->
            if (name.isBlank()) {
                toast("名字不能空着")
                return@askText
            }
            run("重命名") { FileOps.rename(this, n.path, name) }
        }
    }

    private fun showText(n: FileOps.Node) {
        scope.launch {
            val read = withContext(Dispatchers.IO) { FileOps.readText(this@FilesActivity, n.path, 6000) }
            if (isFinishing || isDestroyed) return@launch
            if (!read.ok) {
                toast(read.note)
                return@launch
            }
            val body = read.note
            val scroll = android.widget.ScrollView(this@FilesActivity)
            val tv = TextView(this@FilesActivity)
            tv.text = body
            tv.setPadding(40, 20, 40, 20)
            tv.setTextIsSelectable(true)
            tv.textSize = 13f
            scroll.addView(tv)
            AlertDialog.Builder(this@FilesActivity)
                .setTitle(n.name)
                .setView(scroll)
                .setNeutralButton("复制全文") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(n.name, body))
                    toast("内容已复制")
                }
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    /** 点顶部路径回上一层；已经在最外面就不动 */
    private fun goUp() {
        val up = FileOps.parentOf(this, path)
        if (up == null) {
            toast("这里已经是根了")
            return
        }
        go(up.path)
    }

    // ---------------- 复制 / 移动的暂存 ----------------

    private fun setPending(op: String, n: FileOps.Node) {
        pending = Pending(op, n)
        drawPending()
        toast(if (op == "copy") "好，先记住要复制「${n.name}」。进去哪个文件夹，点下面那条横条就能落地。" else "好，先记住要挪走「${n.name}」。进目标文件夹后点下面那条横条。")
    }

    private fun clearPending() {
        pending = null
        drawPending()
    }

    private fun drawPending() {
        val p = pending
        if (p == null) {
            b.tvPending.visibility = View.GONE
            return
        }
        val here = FileOps.resolve(this, path).file
        b.tvPending.visibility = View.VISIBLE
        b.tvPending.text = (if (p.op == "copy") "准备复制" else "准备挪走") + "：${p.node.name} → 到这里\n（点我可以取消；换个文件夹再点就是换个去处）"
        b.tvPending.setOnClickListener {
            if (here == null) {
                toast("当前位置读不到，换一个文件夹再试")
                return@setOnClickListener
            }
            val done = if (p.op == "copy") FileOps.copy(this, p.node.path, path) else FileOps.move(this, p.node.path, path)
            toast(done.note)
            pending = null
            refresh()
        }
    }

    // ---------------- 深层搜索 ----------------

    private fun deepSearch() {
        askText("在深层文件夹里找", "从「${FileOps.display(FileOps.resolve(this, path).file?.path ?: path)}」往下找，最多五层", "要包含的名字") { kw ->
            if (kw.isBlank()) return@askText
            scope.launch {
                val hits = withContext(Dispatchers.IO) { FileOps.searchNodes(this@FilesActivity, path, kw, 60) }
                if (isFinishing || isDestroyed) return@launch
                if (hits.isEmpty()) {
                    toast("这里往下的五层里没有名字带「$kw」的东西")
                    return@launch
                }
                val labels = hits.map { (if (it.isDir) "文件夹  " else "文件  ") + it.name }
                AlertDialog.Builder(this@FilesActivity)
                    .setTitle("找到 ${hits.size} 个")
                    .setItems(labels.toTypedArray()) { _, which ->
                        val hit = hits[which]
                        if (hit.isDir) {
                            go(hit.path)
                        } else {
                            path = hit.file.parentFile?.path ?: path
                            filter = hit.name
                            b.etSearch.setText(filter)
                            buildPlaces()
                            refresh()
                        }
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    // ---------------- 交给灵犀 ----------------

    private fun handToAgent() {
        val here = FileOps.resolve(this, path).file?.path ?: path
        val draft = "把「${FileOps.display(here)}」里的文件按类型整理进子文件夹（图片、文档、压缩包、安装包、其他），" +
                "先列出你打算怎么安排，再动手。"
        val i = Intent(this, MainActivity::class.java)
        i.putExtra("prefill_text", draft)
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(i)
        finish()
    }

    private fun askStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            com.lingxi.chat.data.HistoryStore.openRootPermissionSettings(this)
        } else {
            com.lingxi.chat.data.HistoryStore.requestRuntimePermission(this)
        }
    }

    // ---------------- 小工具 ----------------

    private fun run(label: String, block: () -> FileOps.Done) {
        scope.launch {
            val done = withContext(Dispatchers.IO) { block() }
            if (isFinishing || isDestroyed) return@launch
            toast(if (done.ok) "$label：${done.note}" else done.note)
            refresh()
        }
    }

    private fun askText(title: String, hint: String, label: String, onOk: (String) -> Unit) {
        val density = resources.displayMetrics.density
        val box = FrameLayout(this)
        val input = EditText(this).apply {
            setHint(hint)
            textSize = 13f
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            setBackgroundResource(R.drawable.bg_input_bar)
            setSingleLine()
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
        box.addView(input, lp)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("好", { _, _ -> onOk(input.text.toString().trim()) })
            .setNegativeButton("取消", null)
            .show()
    }
}
