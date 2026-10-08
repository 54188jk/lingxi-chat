package com.lingxi.chat.control

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件与文件夹操作：AI 的文件动作和「文件与文件夹」页面共用这一套。
 *
 * 三条硬规矩：
 * 1. 只能在内部储存和灵犀自己的目录里活动，路径先过 [resolve]，`../` 这类越界写法当场挡掉；
 * 2. 删除一律先进回收站（内部储存/.回收站），不直接抹掉，用户随时能还原；
 * 3. 每个操作都回「成没成 + 给用户看的一句话」，上层不用猜。
 */
object FileOps {

    class Node(
        val file: File,
        val isDir: Boolean,
        val size: Long,
        val modified: Long,
        val canWrite: Boolean
    ) {
        val name: String get() = file.name
        val path: String get() = file.absolutePath
    }

    /** 一条操作的结论：ok 给模型判断，note 是给用户看的那句话 */
    class Done(val ok: Boolean, val note: String)

    /** 路径解析结果：file 为 null 时看 error */
    class Where(val file: File?, val error: String) {
        val ok: Boolean get() = file != null && error.isEmpty()
    }

    private const val TRASH = ".回收站"

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun timeText(ms: Long): String = timeFmt.format(Date(ms))

    fun sizeText(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(Locale.US, bytes / 1024.0 / 1024.0 / 1024.0)
    }

    // ---------------- 位置 ----------------

    private fun extRoot(): File? = Environment.getExternalStorageDirectory()

    private fun appRoot(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    private fun allowedRoots(context: Context): List<File> =
        listOfNotNull(extRoot(), appRoot(context)).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }

    /** 常用去处：页面顶部的快捷入口和 AI 都用这些名字指位置 */
    fun roots(context: Context): List<Pair<String, File>> {
        val ext = extRoot()
        val list = ArrayList<Pair<String, File>>()
        if (ext != null) {
            list.add("内部储存" to ext)
            list.add("下载" to File(ext, "Download"))
            list.add("文档" to File(ext, "Documents"))
            list.add("图片" to File(ext, "DCIM"))
            list.add("视频" to File(ext, "Movies"))
            list.add("音乐" to File(ext, "Music"))
            list.add("录音" to File(ext, "Recordings"))
            list.add("蓝牙接收" to File(ext, "Bluetooth"))
            list.add("回收站" to File(ext, TRASH))
        }
        list.add("灵犀文件夹" to appRoot(context))
        return list.filter { it.second.isDirectory || it.second.mkdirs() }
    }

    /** 显示成「内部储存/下载」这种用户看得懂的样子 */
    fun display(path: String): String {
        val root = extRoot()?.absolutePath ?: return path
        return when {
            path == root -> "内部储存"
            path.startsWith(root) -> "内部储存" + path.removePrefix(root)
            else -> path
        }
    }

    /**
     * 把用户或模型写的位置解析成真实文件，同时挡掉越界。
     * 允许「下载/照片」这类相对内部储存的写法，也允许「下载」「文档」这些去处名字。
     */
    fun resolve(context: Context, input: String): Where {
        val raw = input.trim().trim('"', '\'')
        if (raw.isEmpty()) return Where(null, "没说要操作哪个位置")
        val ext = extRoot()
        val alias = roots(context).firstOrNull {
            raw == it.first || raw.startsWith(it.first + "/") || raw.startsWith(it.first + File.separator)
        }
        val base = when {
            raw.startsWith("/") -> File(raw)
            alias != null -> File(alias.second, raw.removePrefix(alias.first).trimStart('/', File.separatorChar))
            ext != null -> File(ext, raw.trimStart('/', File.separatorChar))
            else -> File(appRoot(context), raw.trimStart('/', File.separatorChar))
        }
        val canonical = runCatching { base.canonicalFile }.getOrDefault(base)
        val inside = allowedRoots(context).any {
            canonical.path == it.path || canonical.path.startsWith(it.path + File.separator)
        }
        return if (!inside) Where(null, "只能动内部储存和灵犀自己目录里的东西：${display(canonical.path)}")
        else Where(canonical, "")
    }

    private fun at(context: Context, path: String): Pair<File?, String?> {
        val r = resolve(context, path)
        return r.file to (r.error.ifBlank { null })
    }

    private fun node(f: File) = Node(
        f, f.isDirectory,
        if (f.isDirectory) dirSize(f) else f.length(),
        f.lastModified(), f.canWrite()
    )

    /** 上一层；已经在允许范围最外面时返回 null */
    fun parentOf(context: Context, path: String): Node? {
        val (f, err) = at(context, path)
        if (err != null || f == null) return null
        val parent = f.parentFile ?: return null
        val roots = allowedRoots(context)
        if (roots.any { it.path == parent.path }) return null
        if (!roots.any { parent.path.startsWith(it.path + File.separator) }) return null
        return node(parent)
    }

    /** 这个位置是不是在回收站里 */
    fun isTrash(context: Context, path: String): Boolean {
        val (f, err) = at(context, path)
        if (err != null || f == null) return false
        val bin = extRoot()?.let { File(it, TRASH) }?.path ?: return false
        return f.path == bin || f.path.startsWith(bin + File.separator)
    }

    /** 某个位置里面的子项路径 */
    fun child(context: Context, dirPath: String, name: String): String {
        val base = at(context, dirPath).first ?: appRoot(context)
        val clean = name.trim().trimStart('/', File.separatorChar)
        return File(if (base.isDirectory) base else base.parentFile, clean).absolutePath
    }

    fun looksLikeText(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.US) in setOf(
            "txt", "md", "json", "csv", "log", "xml", "html", "ini", "conf", "yaml", "yml", "srt", "vtt"
        )

    /** 由绝对路径做一个列表项（搜索结果点击用） */
    fun nodeAt(context: Context, absolutePath: String): Node? {
        val (f, err) = at(context, absolutePath)
        if (err != null || f == null || !f.exists()) return null
        return node(f)
    }

    // ---------------- 看 ----------------

    /** order：0 名字 / 1 时间 / 2 大小 */
    fun list(context: Context, path: String, order: Int = 0, keyword: String = ""): Pair<List<Node>, String> {
        val (dir, err) = at(context, path)
        if (err != null) return emptyList<Node>() to err
        if (dir == null) return emptyList<Node>() to "读不到这个位置"
        if (!dir.isDirectory) return emptyList<Node>() to "这不是文件夹：${display(dir.path)}"
        val kw = keyword.trim().lowercase(Locale.US)
        val nodes = runCatching { dir.listFiles()?.toList().orEmpty() }.getOrNull()
            ?.filter { kw.isEmpty() || it.name.lowercase(Locale.US).contains(kw) }
            ?.map { node(it) }
            ?.sortedWith(
                when (order) {
                    1 -> compareByDescending<Node> { it.isDir }.thenByDescending { it.modified }
                    2 -> compareByDescending<Node> { it.isDir }.thenByDescending { it.size }
                    else -> compareByDescending<Node> { it.isDir }.thenBy { it.name.lowercase(Locale.US) }
                }
            )
            .orEmpty()
        return nodes to ""
    }

    /** 递归算大小，最深 6 层，免得在超大目录里把界面卡住 */
    private fun dirSize(dir: File, depth: Int = 0): Long {
        if (depth > 6) return 0
        val kids = dir.listFiles() ?: return 0
        var sum = 0L
        for (k in kids) {
            sum += if (k.isDirectory) dirSize(k, depth + 1) else k.length()
            if (sum < 0) return 0
        }
        return sum
    }

    /** 深层搜索（最多五层）的结构版，页面点结果要跳过去 */
    fun searchNodes(context: Context, from: String, keyword: String, limit: Int = 60): List<Node> {
        val (start, err) = at(context, if (from.isBlank()) "内部储存" else from)
        if (err != null || start == null || !start.isDirectory) return emptyList()
        val kw = keyword.trim().lowercase(Locale.US)
        if (kw.isEmpty()) return emptyList()
        val out = ArrayList<Node>()
        walkNode(start, kw, 0, out, limit)
        return out
    }

    private fun walkNode(dir: File, kw: String, depth: Int, out: ArrayList<Node>, limit: Int) {
        if (depth > 5 || out.size >= limit) return
        val kids = dir.listFiles() ?: return
        for (k in kids.sortedBy { it.name.lowercase(Locale.US) }) {
            if (out.size >= limit) return
            if (k.name.startsWith(".")) continue
            if (k.name.lowercase(Locale.US).contains(kw)) out.add(node(k))
            if (k.isDirectory) walkNode(k, kw, depth + 1, out, limit)
        }
    }

    fun stat(context: Context, path: String): Done {
        val (f, err) = at(context, path)
        if (err != null) return Done(false, err)
        if (f == null || !f.exists()) return Done(false, "这个东西不存在：${display(path)}")
        val n = node(f)
        return Done(true, buildString {
            append(if (n.isDir) "文件夹" else "文件").append("：").append(n.name).append('\n')
            append("位置：").append(display((f.parentFile ?: f).path)).append('\n')
            append("大小：").append(sizeText(n.size))
            if (n.isDir) {
                val kids = f.listFiles()?.toList().orEmpty()
                append("（").append(kids.count { it.isDirectory }).append(" 个子文件夹，")
                    .append(kids.count { !it.isDirectory }).append(" 个文件）")
            }
            append('\n').append("修改于：").append(timeText(n.modified))
            append('\n').append(if (n.canWrite) "可以写入" else "只读（这个位置不让我写）")
        })
    }

    fun search(context: Context, from: String, keyword: String, limit: Int = 60): Done {
        if (keyword.isBlank()) return Done(false, "没说要找什么名字")
        val (start, err) = at(context, if (from.isBlank()) "内部储存" else from)
        if (err != null) return Done(false, err)
        val hits = searchNodes(context, start!!.path, keyword, limit)
        if (hits.isEmpty()) return Done(true, "在 ${display(start.path)} 往下五层里，没找到带「$keyword」的东西")
        return Done(true, "找到 ${hits.size} 个：\n" + hits.joinToString("\n") {
            (if (it.isDir) "[文件夹] " else "[文件] ") + display(it.path) + " · " + sizeText(it.size)
        })
    }

    /** 读文本文件内容（最多 maxChars 个字符） */
    fun readText(context: Context, path: String, maxChars: Int = 4000): Done {
        val (f, err) = at(context, path)
        if (err != null) return Done(false, err)
        if (f == null) return Done(false, "位置没给对")
        if (!f.isFile) return Done(false, "这不是文件：${display(f.path)}")
        if (f.length() > 2_000_000) return Done(false, "这个文件 ${sizeText(f.length())} 太大，只看 2MB 以内的文本")
        return runCatching { Done(true, f.readText(Charsets.UTF_8).take(maxChars)) }
            .getOrElse { Done(false, "这个文件读不出来，多半不是文本：${f.name}") }
    }

    fun freeGb(context: Context): String {
        val root = extRoot() ?: appRoot(context)
        val total = runCatching { android.os.StatFs(root.path).blockCountLong * 1024L }.getOrDefault(0L)
        val free = runCatching { android.os.StatFs(root.path).availableBlocksLong * 1024L }.getOrDefault(0L)
        return if (total <= 0) "容量读不到" else "还剩 ${sizeText(free)}（共 ${sizeText(total)}）"
    }

    /** 能不能直接动内部储存；拿不到就只能待在灵犀自己的文件夹里 */
    fun canUseStorage(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        val root = extRoot() ?: return false
        val probe = File(root, ".lingxi_write_test")
        return runCatching {
            probe.writeText("x")
            probe.exists() && probe.delete()
        }.getOrDefault(false)
    }

    // ---------------- 写 ----------------

    fun mkdir(context: Context, path: String): Done {
        val (f, err) = at(context, path)
        if (err != null) return Done(false, err)
        if (f == null) return Done(false, "位置没给对")
        if (f.exists()) {
            return Done(false, if (f.isDirectory) "这个文件夹已经在了：${display(f.path)}" else "已经有同名文件：${display(f.path)}")
        }
        return if (runCatching { f.mkdirs() }.getOrDefault(false)) Done(true, "建好了：${display(f.path)}")
        else Done(false, "这个位置写不了，建不出来：${display(f.path)}")
    }

    /** to 可以是新名字（原地改名），也可以是完整路径（改名 + 挪走） */
    fun rename(context: Context, path: String, to: String): Done {
        val (src, e1) = at(context, path)
        if (e1 != null) return Done(false, e1)
        if (src == null || !src.exists()) return Done(false, "找不到要改的东西：${display(path)}")
        val target = to.trim().trim('"', '\'')
        if (target.isEmpty()) return Done(false, "新名字没写")
        val dst = if (target.contains('/')) {
            val (d, e2) = at(context, target)
            if (e2 != null) return Done(false, e2)
            d ?: return Done(false, "新位置没给对")
        } else File(src.parentFile, target)
        return moveInto(src, dst, "改好名字，现在叫：")
    }

    fun move(context: Context, from: String, to: String): Done {
        val (srcRaw, e1) = at(context, from)
        if (e1 != null) return Done(false, e1)
        if (srcRaw == null) return Done(false, "位置没给对")
        val src = srcRaw
        if (!src.exists()) return Done(false, "找不到要挪的东西：${display(src.path)}")
        val (dstBase, e2) = at(context, to)
        if (e2 != null) return Done(false, e2)
        if (dstBase == null) return Done(false, "要去的位置没给对")
        val dst = if (dstBase.isDirectory) File(dstBase, src.name) else dstBase
        if (dst.path.startsWith(src.path + File.separator)) return Done(false, "文件夹不能挪进它自己里面")
        return moveInto(src, dst, "已挪到：")
    }

    private fun moveInto(src: File, dst: File, okPrefix: String): Done {
        if (dst.exists()) return Done(false, "要去的地方已经有同名的东西：${display(dst.path)}")
        dst.parentFile?.mkdirs()
        val ok = runCatching {
            if (src.renameTo(dst)) true
            else if (src.isDirectory) copyDir(src, dst) && src.deleteRecursively()
            else {
                src.copyTo(dst)
                src.delete()
            }
        }.getOrDefault(false)
        return if (ok) Done(true, "$okPrefix${display(dst.path)}")
        else Done(false, "挪不动：${display(src.path)} → ${display(dst.path)}（多半是没写权限；也可以先复制再删）")
    }

    fun copy(context: Context, from: String, to: String): Done {
        val (srcRaw, e1) = at(context, from)
        if (e1 != null) return Done(false, e1)
        if (srcRaw == null) return Done(false, "位置没给对")
        val src = srcRaw
        if (!src.exists()) return Done(false, "找不到要复制的东西：${display(src.path)}")
        val (dstBase, e2) = at(context, to)
        if (e2 != null) return Done(false, e2)
        if (dstBase == null) return Done(false, "要放到的位置没给对")
        val dst = if (dstBase.isDirectory) File(dstBase, src.name) else dstBase
        if (dst.path.startsWith(src.path + File.separator)) return Done(false, "文件夹不能复制进它自己里面")
        val made = runCatching {
            dst.parentFile?.mkdirs()
            if (src.isDirectory) copyDir(src, dst) else {
                src.copyTo(dst, overwrite = true)
                true
            }
        }.getOrDefault(false)
        return if (made) Done(true, "复制好了：${display(dst.path)}")
        else Done(false, "复制失败：${display(dst.path)}（空间不够或者写不进去）")
    }

    private fun copyDir(src: File, dst: File): Boolean {
        if (!dst.exists() && !dst.mkdirs()) return false
        val kids = src.listFiles() ?: return true
        for (k in kids) {
            val out = File(dst, k.name)
            if (k.isDirectory) {
                if (!copyDir(k, out)) return false
            } else if (runCatching { k.copyTo(out, overwrite = true) }.isFailure) return false
        }
        return true
    }

    /** 删除 = 放进回收站 */
    fun trash(context: Context, path: String): Done {
        val (src, err) = at(context, path)
        if (err != null) return Done(false, err)
        if (src == null || !src.exists()) return Done(false, "找不到要删的东西：${display(path)}")
        val root = extRoot() ?: return Done(false, "这台手机没找到内部储存的位置")
        if (src.path == runCatching { root.canonicalFile.path }.getOrDefault(root.path)) {
            return Done(false, "内部储存本身不能删")
        }
        if (isTrash(context, src.path)) return Done(false, "它已经在回收站里了")
        val bin = File(root, TRASH).apply { if (!exists()) mkdirs() }
        val dst = File(bin, "${dayFmt.format(Date())}-${src.name}")
        val moved = runCatching {
            if (src.renameTo(dst)) true
            else if (src.isDirectory) copyDir(src, dst) && src.deleteRecursively()
            else {
                src.copyTo(dst)
                src.delete()
            }
        }.getOrDefault(false)
        return if (moved) Done(true, "已放进回收站：${src.name}（要找回就去 文件与文件夹 → 回收站）")
        else Done(false, "删不掉：${display(src.path)}")
    }

    /** 从回收站放回原处，或者放回指定位置 */
    fun restore(context: Context, path: String, backTo: String = ""): Done {
        val (src, err) = at(context, path)
        if (err != null) return Done(false, err)
        if (src == null || !src.exists()) return Done(false, "回收站里找不到这个：${display(path)}")
        if (!isTrash(context, src.path)) return Done(false, "只有回收站里的东西能还原")
        val origin = src.name.substringAfter("${dayFmt.format(Date())}-", src.name)
        val dstBase = if (backTo.isNotBlank()) {
            val (d, e2) = at(context, backTo)
            if (e2 != null) return Done(false, e2)
            d ?: return Done(false, "要放回的位置没给对")
        } else extRoot() ?: appRoot(context)
        val target = if (dstBase.isDirectory) File(dstBase, origin) else dstBase
        if (target.exists()) return Done(false, "要放回的地方已经有同名的东西：${display(target.path)}")
        target.parentFile?.mkdirs()
        return if (runCatching { src.renameTo(target) }.getOrDefault(false)) Done(true, "已还原到：${display(target.path)}")
        else Done(false, "还原失败：${display(src.path)}")
    }

    /** 彻底删除：只允许已经在回收站里的东西 */
    fun purge(context: Context, path: String): Done {
        val (f, err) = at(context, path)
        if (err != null) return Done(false, err)
        if (f == null || !f.exists()) return Done(false, "找不到：${display(path)}")
        if (!isTrash(context, f.path)) return Done(false, "为了不误删，只有回收站里的东西才能彻底删除")
        return if (runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }.getOrDefault(false))
            Done(true, "已彻底删除：${f.name}")
        else Done(false, "彻底删除失败：${display(f.path)}")
    }

    fun writeText(context: Context, path: String, text: String, append: Boolean): Done {
        val (f, err) = at(context, path)
        if (err != null) return Done(false, err)
        if (f == null) return Done(false, "位置没给对")
        if (f.isDirectory) return Done(false, "那是个文件夹，请给到文件名：${display(f.path)}")
        return runCatching {
            f.parentFile?.mkdirs()
            if (append) f.appendText(text) else f.writeText(text)
            Done(true, (if (append) "已接着写入：" else "已写好：") + display(f.path) + "（${sizeText(f.length())}）")
        }.getOrElse { Done(false, "写不进去：${display(f.path)}（${it.message}）") }
    }

    /** 文件夹交给系统文件管理器，文件按类型交给对应应用 */
    fun openInManager(context: Context, path: String): Done {
        val (f, err) = at(context, path)
        if (err != null) return Done(false, err)
        if (f == null || !f.exists()) return Done(false, "找不到要打开的东西：${display(path)}")
        if (f.isDirectory) {
            return runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.fromFile(f), "resource/folder")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                Done(true, "已叫出系统文件管理器：${display(f.path)}")
            }.getOrElse { Done(false, "这台机器的文件管理器不接受外部调用，直接在这里操作就行") }
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "com.lingxi.chat.fileprovider", f)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeOf(f.name))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            Done(true, "已交给对应应用打开：${f.name}")
        }.getOrElse { Done(false, "这个类型目前没有应用能打开：${f.name}") }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "txt", "md", "json", "csv", "log", "xml", "ini", "conf", "yaml", "yml", "srt", "vtt" -> "text/plain"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/x-wav"
        "pdf" -> "application/pdf"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        "rar" -> "application/x-rar-compressed"
        "7z" -> "application/x-7z-compressed"
        "doc", "docx" -> "application/msword"
        "xls", "xlsx" -> "application/vnd.ms-excel"
        "ppt", "pptx" -> "application/vnd.ms-powerpoint"
        else -> "*/*"
    }

    // ---------------- AI 的统一入口 ----------------

    /** op（做什么）+ path（哪里）+ text（第二个参数：目标位置或要写的内容） */
    fun run(context: Context, op: String, path: String, text: String): Done {
        val key = op.lowercase(Locale.US).trim()
        val where = if (path.isBlank()) "内部储存" else path
        return when {
            key in setOf("list", "ls", "列", "看") -> {
                val (nodes, err) = list(context, where)
                if (err.isNotEmpty()) Done(false, err) else {
                    val head = "${display(resolve(context, where).file?.path ?: where)}：" +
                            "${nodes.count { it.isDir }} 个文件夹、${nodes.count { !it.isDir }} 个文件"
                    if (nodes.isEmpty()) Done(true, "$head，里面是空的")
                    else Done(true, head + "\n" + nodes.take(50).joinToString("\n") {
                        (if (it.isDir) "[夹] " else "[文] ") + it.name + " · " + sizeText(it.size) + " · " + timeText(it.modified)
                    })
                }
            }
            key in setOf("info", "stat", "属性") -> stat(context, where)
            key in setOf("mkdir", "new_folder", "新建文件夹", "建文件夹") ->
                mkdir(context, if (text.isBlank()) where else child(context, where, text))
            key in setOf("rename", "重命名", "改名") -> rename(context, path, text)
            key in setOf("move", "mv", "移动", "挪") -> move(context, path, text)
            key in setOf("copy", "cp", "复制") -> copy(context, path, text)
            key in setOf("delete", "remove", "删除") -> trash(context, path)
            key in setOf("restore", "还原") -> restore(context, path, text)
            key in setOf("purge", "彻底删除") -> purge(context, path)
            key in setOf("search", "find", "找", "搜索") -> search(context, where, text)
            key in setOf("read", "读", "看内容") -> readText(context, path)
            key in setOf("write", "写") -> writeText(context, path, text, false)
            key in setOf("append", "追加") -> writeText(context, path, text, true)
            key in setOf("open", "打开") -> openInManager(context, path)
            key in setOf("free", "容量", "空间") -> Done(true, freeGb(context))
            else -> Done(false, HELP)
        }
    }

    /** 给模型看的可用操作说明 */
    const val HELP =
        "文件操作（fs）的 op 可用：list 看有什么、info 看详情、mkdir 新建文件夹、rename 改名、" +
            "move 挪走、copy 复制、delete 删除（只会放进回收站）、restore 从回收站还原、purge 彻底删除（限回收站内）、" +
            "search 按名字找、read 读文本、write 写文本、append 追加、open 交给系统应用打开、free 看剩余空间。" +
            "path 可以写「内部储存」「下载」「文档」「图片」「回收站」，也可以写「下载/归档」这样的相对路径。"
}
