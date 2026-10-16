package com.lingxi.chat.data

import android.content.Context
import android.os.Environment
import com.lingxi.chat.net.FastFetcher
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 手指特效要用的手部识别文件：7.5MB，不进安装包。
 * 先在本机的「特效模型」目录里找，找到且校验对得上就直接用；找不到才下——多条线路分段并行同时抓，
 * 哪条不通就换那条线重抓没下完的那一段，不用整份重来。
 * 目录优先落在用户自己能打开看的内部储存；没那个权限就落在应用自己的目录，不打扰用户也照样能用。
 */
object EffectModel {

    const val FILE_NAME = "hand_landmarker.task"
    const val FOLDER = "特效模型"
    const val SIZE = 7_819_105L

    /**
     * 官方原件校验值（本机 `certutil -hashfile` 记的，只用于自查，不写进任何给用户看的文案）：
     * sha256 = fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1
     * 换模型版本时这里和 SIZE 一起核对。
     */
    private const val SHA256 = "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"

    /**
     * 模型文件挂在「哪一版 Release」下面下载——这一版发了附件就一直在那儿，不随应用版本往上搬，
     * 免得每出一个新版本都要重传一次 7.5MB。真要换模型时才在这里改 tag 并往那一版重新挂附件。
     */
    private const val RELEASE_TAG = "v1.186"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun sizeOk(f: File) = f.isFile && f.length() == SIZE

    /** 字节数对上还不够：得真是那份文件才行（截半截、被门户页换掉的都能当场识别出来重下） */
    private fun usable(f: File): Boolean {
        if (!sizeOk(f)) return false
        val digest = sha256Of(f) ?: return false
        return digest.equals(SHA256, ignoreCase = true)
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

    /** 两处都找一遍：用户自己拷进去的那份也算数；只认字节数，校验交给 [ensure]，免得每次开页面都算一遍哈希 */
    fun find(context: Context): File? {
        val list = listOf(
            File(writableDir(context), FILE_NAME),
            File(File(Environment.getExternalStorageDirectory(), FOLDER), FILE_NAME),
            File(File(context.getExternalFilesDir(null), FOLDER), FILE_NAME)
        )
        return list.firstOrNull { runCatching { sizeOk(it) }.getOrDefault(false) }
    }

    /** 真正能用与否的判断：字节数 + 内容校验，读之前先过这一关 */
    fun isReady(f: File): Boolean = usable(f)

    fun statusText(context: Context): String {
        val f = find(context) ?: return "还没下载：约 7.5MB，下一次就一直留在本机"
        return if (usable(f)) "已就绪 · ${SIZE / 1048576}MB · ${f.absolutePath}"
        else "本机那份不对（文件损坏或被改过），下次进特效会重新下载"
    }

    fun remove(context: Context): Boolean {
        val f = find(context) ?: return false
        return runCatching { f.delete() }.getOrDefault(false)
    }

    private fun sources(): List<Pair<String, String>> {
        val gh = "https://github.com/54188jk/lingxi-chat/releases/download/$RELEASE_TAG/$FILE_NAME"
        return listOf(
            "https://gitee.com/wuzhuf/lingxi-chat/releases/download/$RELEASE_TAG/$FILE_NAME" to "国内线路",
            "https://gh-proxy.com/$gh" to "加速线路 1",
            "https://ghfast.top/$gh" to "加速线路 2",
            "https://ghproxy.net/$gh" to "加速线路 3",
            gh to "官方线路",
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/$FILE_NAME" to "原始来源"
        )
    }

    /**
     * 有就用、没有就下；下完还要过内容校验，不合格当场删掉重来。
     * `onProgress(百分比, 已下字节)` 可能在别的线程回调，调用方自己切回界面。
     * 一条线都不通就返回 null，界面按「拿不到就先用不了这个特效」处理，不假装成功。
     */
    suspend fun ensure(context: Context, onProgress: (Int, Long) -> Unit): File? {
        find(context)?.let { if (usable(it)) return it else remove(context) }
        val dir = writableDir(context)
        val out = File(dir, FILE_NAME)
        return try {
            FastFetcher.fetch(sources(), out, SIZE, client, connections = 4, onProgress = { got, total, _, _ ->
                val pct = if (total <= 0) 0 else (got * 100 / total).toInt().coerceIn(0, 99)
                onProgress(pct, got)
            })
            if (!usable(out)) {
                runCatching { out.delete() }
                // 校验没过就再给它一次机会（换一批线路重来）
                FastFetcher.fetch(sources(), out, SIZE, client, connections = 4, onProgress = { got, total, _, _ ->
                    val pct = if (total <= 0) 0 else (got * 100 / total).toInt().coerceIn(0, 99)
                    onProgress(pct, got)
                })
            }
            if (usable(out)) out.also { onProgress(100, SIZE) } else {
                runCatching { out.delete() }
                null
            }
        } catch (_: Throwable) {
            runCatching { out.delete() }
            dir.listFiles()?.forEach {
                if (it.name.startsWith("$FILE_NAME.")) runCatching { it.delete() }
            }
            null
        }
    }
}
