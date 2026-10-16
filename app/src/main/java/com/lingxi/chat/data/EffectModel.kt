package com.lingxi.chat.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * 手指特效要用的手部识别文件：7.5MB，不进安装包。
 * 先在本机的「特效模型」目录里找，找到就直接用；找不到才下——几个下载地址同时开跑，谁先下完用谁，其余立刻掐掉。
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

    /** 模型文件跟着这一版 Release 一起发，换版本时这里跟着改 */
    private const val RELEASE_TAG = "v1.186"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 只认这个字节数：下坏的、被门户页截走的都不会被当成可用文件 */
    private fun usable(f: File) = f.isFile && f.length() == SIZE

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
        return list.firstOrNull { runCatching { usable(it) }.getOrDefault(false) }
    }

    fun statusText(context: Context): String {
        val f = find(context) ?: return "还没下载：约 7.5MB，下一次就一直留在本机"
        return "已就绪 · ${SIZE / 1048576}MB · ${f.absolutePath}"
    }

    fun remove(context: Context): Boolean {
        val f = find(context) ?: return false
        return runCatching { f.delete() }.getOrDefault(false)
    }

    private fun sources(): List<String> {
        val gh = "https://github.com/54188jk/lingxi-chat/releases/download/$RELEASE_TAG/$FILE_NAME"
        return listOf(
            "https://gitee.com/wuzhuf/lingxi-chat/releases/download/$RELEASE_TAG/$FILE_NAME",
            "https://gh-proxy.com/$gh",
            "https://ghfast.top/$gh",
            gh,
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/$FILE_NAME"
        )
    }

    /**
     * 有就用、没有就下。`onProgress(百分比, 已下字节)` 可能在任意线程回调，调用方自己切回界面。
     * 一路都不通就返回 null，界面按「拿不到就先用不了这个特效」处理，不假装成功。
     */
    suspend fun ensure(context: Context, onProgress: (Int, Long) -> Unit): File? {
        find(context)?.let { return it }
        val dir = writableDir(context)
        val out = File(dir, FILE_NAME)
        val urls = sources()
        val progress = AtomicLong(0)
        val winner = kotlinx.coroutines.CompletableDeferred<File?>()
        val calls = ArrayList<Call>()
        coroutineScope {
            urls.forEachIndexed { i, url ->
                launch(Dispatchers.IO) {
                    val mine = File(dir, "$FILE_NAME.part$i")
                    var call: Call? = null
                    try {
                        if (winner.isCompleted) return@launch
                        call = client.newCall(Request.Builder().url(url).build()).also { synchronized(calls) { calls.add(it) } }
                        call.execute().use { resp ->
                            if (!resp.isSuccessful) return@launch
                            val body = resp.body ?: return@launch
                            body.byteStream().use { input ->
                                FileOutputStream(mine).use { os ->
                                    val buf = ByteArray(64 * 1024)
                                    var n = input.read(buf)
                                    while (n > 0 && !winner.isCompleted) {
                                        os.write(buf, 0, n)
                                        val got = progress.addAndGet(n.toLong())
                                        onProgress(max(0, min(99, (got * 100 / SIZE).toInt())), got)
                                        n = input.read(buf)
                                    }
                                }
                            }
                            if (winner.isCompleted || !usable(mine)) {
                                runCatching { mine.delete() }
                            } else if (rename(mine, out)) {
                                winner.complete(out)
                            } else {
                                runCatching { mine.delete() }
                            }
                        }
                    } catch (_: Throwable) {
                        runCatching { mine.delete() }
                    } finally {
                        runCatching { call?.cancel() }
                    }
                }
            }
            val got = withTimeoutOrNull(200_000) { winner.await() }
            synchronized(calls) { calls.forEach { runCatching { it.cancel() } } }
            // 取消所有还没下完的分头，然后清掉半成品
            coroutineContext[Job]?.children?.forEach { it.cancel() }
            dir.listFiles()?.forEach { if (it.name.startsWith("$FILE_NAME.part")) runCatching { it.delete() } }
            if (got == null) winner.complete(null)
        }
        return find(context)
    }

    private fun rename(from: File, to: File): Boolean {
        if (runCatching { from.renameTo(to) && to.length() == SIZE }.getOrDefault(false)) return true
        // 跨目录改名在某些机型上会被挡，退一步整份拷过去
        return runCatching {
            from.inputStream().use { a -> FileOutputStream(to).use { b -> a.copyTo(b, 1 shl 16) } }
            from.delete()
            to.length() == SIZE
        }.getOrDefault(false)
    }
}
