package com.lingxi.chat.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 手指特效要用的手部识别文件：随安装包内置（assets/hand_landmarker.task，7.5MB）。
 * 第一次用到时自动从包里放到本机的「特效模型」目录——那是永久目录，放一次以后一直从那儿读，
 * 不再需要联网下载。放完还要对一次内容，对不上就删掉重放，免得半个文件被当成好的用下去。
 * 目录优先落在用户自己能打开看的内部储存；没那个权限就落在应用自己的目录。
 */
object EffectModel {

    const val FILE_NAME = "hand_landmarker.task"
    const val FOLDER = "特效模型"
    const val SIZE = 7_819_105L

    /**
     * 内置原件的校验值（本机 `certutil -hashfile` 记的，只用于自查，不写进给用户看的文案）：
     * sha256 = fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1
     * 换模型时这里和 SIZE 一起核对。
     */
    private const val SHA256 = "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"


    private fun sizeOk(f: File) = f.isFile && f.length() == SIZE

    /** 字节数对上还不够：得真是那份文件才行（复制被截断、被改过的都能当场查出来重放） */
    private fun usable(f: File): Boolean {
        if (!sizeOk(f)) return false
        return sha256Of(f)?.equals(SHA256, ignoreCase = true) == true
    }

    private fun sha256Of(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** 用户能直接打开看的那个目录（需要「所有文件访问」），拿不到就用应用自己的外部目录 */
    private fun writableDir(context: Context): File {
        val preferred = File(Environment.getExternalStorageDirectory(), FOLDER)
        val target = if (HistoryStore.canUseRoot(context)) preferred
        else File(context.getExternalFilesDir(null), FOLDER)
        runCatching { if (!target.isDirectory) target.mkdirs() }
        return if (target.isDirectory) target else context.getExternalFilesDir(null)!!
    }

    /** 两处都找一遍：用户自己拷进去的那份也算数 */
    fun find(context: Context): File? {
        val list = listOf(
            File(writableDir(context), FILE_NAME),
            File(File(Environment.getExternalStorageDirectory(), FOLDER), FILE_NAME),
            File(File(context.getExternalFilesDir(null), FOLDER), FILE_NAME)
        )
        return list.firstOrNull { runCatching { sizeOk(it) }.getOrDefault(false) }
    }

    /** 这份文件能不能真拿来用：字节数 + 内容核对，读之前先过这一关 */
    fun isReady(f: File): Boolean = usable(f)

    fun statusText(context: Context): String {
        val f = find(context) ?: return "还没放好：第一次用特效时会自动放到本机（安装包已内置，不用再下载）"
        return if (usable(f)) "已就绪 · ${SIZE / 1048576}MB · ${f.absolutePath}"
        else "本机那份不对，下次进特效会自动重放一份"
    }

    fun remove(context: Context): Boolean {
        val f = find(context) ?: return false
        return runCatching { f.delete() }.getOrDefault(false)
    }

    /**
     * 有就用、没有就从安装包里放一份到永久目录。
     * `onProgress(百分比, 已写字节)` 可能在别的线程回调，调用方自己切回界面。
     * 放不成返回 null（比如目录不可写、包里的内容读不出来），界面按「这个特效暂时用不了」处理，不假装成功。
     */
    suspend fun ensure(context: Context, onProgress: (Int, Long) -> Unit): File? {
        find(context)?.let { if (usable(it)) return it else remove(context) }
        return place(context, onProgress)
    }

    /** 先写临时名，对完内容再改名——避免半成品被下一次进入时当成现成的读走 */
    private fun place(context: Context, onProgress: (Int, Long) -> Unit): File? {
        val dir = writableDir(context)
        val out = File(dir, FILE_NAME)
        val tmp = File(dir, "$FILE_NAME.new")
        runCatching { tmp.delete() }
        var written = 0L
        val copied = runCatching {
            context.assets.open(FILE_NAME).use { input ->
                FileOutputStream(tmp).use { sink ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                        written += n
                        onProgress(((written * 100) / SIZE).toInt().coerceIn(0, 99), written)
                    }
                    sink.flush()
                }
            }
            true
        }.getOrDefault(false)
        if (!copied || !usable(tmp)) {
            runCatching { tmp.delete() }
            return null
        }
        val settled = runCatching {
            if (out.exists()) out.delete()
            tmp.renameTo(out) || copyInto(tmp, out)
        }.getOrDefault(false)
        runCatching { tmp.delete() }
        if (!settled || !usable(out)) return null
        onProgress(100, SIZE)
        return out
    }

    /** 改名不成的机型（跨分区或被权限挡着）就整份拷一次，别把已经验证过的东西扔掉 */
    private fun copyInto(src: File, dst: File): Boolean = runCatching {
        src.inputStream().use { a -> FileOutputStream(dst).use { b -> a.copyTo(b, 1 shl 16) } }
        dst.length() == src.length()
    }.getOrDefault(false)
}
