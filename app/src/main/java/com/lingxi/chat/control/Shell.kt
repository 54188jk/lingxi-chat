package com.lingxi.chat.control

import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/** 一条 shell 通道：能执行命令、能拿回字节 */
interface Shell {
    fun exec(cmd: String): ByteArray
    fun ok(cmd: String): Boolean
}

private fun Shell.text(cmd: String): String = String(exec(cmd), Charsets.UTF_8)

/** Root 通道：`su -c` */
/**
 * 等进程收尾：API 26 以下没有带超时的 waitFor，
 * 而输出流已经读到 EOF，进程正常会立即退出，直接等就行。
 */
internal fun waitWithin(p: Process) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroy()
    } else {
        runCatching { p.waitFor() }.onFailure { p.destroy() }
    }
}

object RootShell : Shell {

    @Volatile
    private var probed: Boolean? = null

    fun available(): Boolean {
        probed?.let { return it }
        val id = runCatching { text("id") }.getOrDefault("")
        val ok = id.contains("uid=0")
        probed = ok
        return ok
    }

    override fun exec(cmd: String): ByteArray = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes()
        waitWithin(p)
        out
    }.getOrDefault(ByteArray(0))

    override fun ok(cmd: String): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", cmd + "; echo LXST_\$?")
            .redirectErrorStream(true).start()
        val out = p.inputStream.readBytes()
        waitWithin(p)
        out.toString(Charsets.UTF_8).contains("LXST_0")
    }.getOrDefault(false)
}

/**
 * Shizuku 通道：以 shell 身份执行，不需要 ROOT。
 *
 * v13 的 Shizuku.newProcess 是包内私有方法，只能反射拿；返回的
 * ShizukuRemoteProcess 继承 java.lang.Process，按 Process 用即可。
 */
object ShizukuShell : Shell {

    private fun process(cmd: String): Process? = runCatching {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as? Process
    }.getOrNull()

    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun authorized(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 触发 Shizuku 授权弹窗（需要 Shizuku 已在运行） */
    fun requestAuth(): Boolean = runCatching {
        if (!running()) false else {
            if (!authorized()) Shizuku.requestPermission(0x4C49)
            true
        }
    }.getOrDefault(false)

    override fun exec(cmd: String): ByteArray = runCatching {
        val p = process(cmd) ?: return@runCatching ByteArray(0)
        val out = p.inputStream.readBytes()
        waitWithin(p)
        out
    }.getOrDefault(ByteArray(0))

    override fun ok(cmd: String): Boolean = runCatching {
        val p = process(cmd + "; echo LXST_\$?") ?: return@runCatching false
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        waitWithin(p)
        out.contains("LXST_0")
    }.getOrDefault(false)
}
