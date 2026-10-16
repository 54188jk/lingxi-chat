package com.lingxi.chat.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * 多线路下载，两条腿同时跑，谁先把整包交出来谁赢：
 * 1. 支持分段（Range）的线路切成若干段、几条连接同时抓——单个连接被限速时，速度能叠起来；
 *    某段失败就换条线重抓那一段，不用整份重来。
 * 2. 不支持分段的线路（Gitee 的附件直链就是这样：给了 Range 也照样回整包）继续单线整包竞速，
 *    12 秒没进账就掐掉那条，别吊死在半死连接上。
 * 两条腿各写各的临时文件，先下完并通过大小校验的那份改名成交付物，另一份当场删掉。
 */
object FastFetcher {

    /** 每段至少这么大，切太碎请求开销反而更大 */
    private const val MIN_CHUNK = 256L * 1024

    class Fail(message: String) : Exception(message)

    private class Probe(val url: String, val label: String, var size: Long, var acceptRanges: Boolean)

    fun fetch(
        sources: List<Pair<String, String>>,
        dst: File,
        expectedSize: Long,
        client: OkHttpClient,
        connections: Int = 4,
        onProgress: (got: Long, total: Long, speedBps: Long, label: String) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): File {
        if (sources.isEmpty()) throw Fail("没有可用的下载地址")
        dst.parentFile?.mkdirs()
        val probes = probe(sources, client, isCancelled)
        var total = expectedSize
        if (total <= 0) total = probes.map { it.size }.filter { it > 0 }.maxOrNull() ?: 0L
        val ranged = probes.filter { it.acceptRanges && it.size > 0 }
        val plain = probes.filter { !(it.acceptRanges && it.size > 0) }

        val claimed = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        val stopOther = AtomicBoolean(false)
        val errs = Collections.synchronizedList(ArrayList<String>())
        val reported = AtomicLong(0)

        fun report(got: Long, tot: Long, sp: Long, label: String) {
            // 两条腿各报各的进度，界面只看目前更大的那个，免得来回跳
            var cur = reported.get()
            while (got > cur && !reported.compareAndSet(cur, got)) cur = reported.get()
            onProgress(max(reported.get(), got), tot, sp, label)
        }

        fun claimWin(tmp: File) {
            if (claimed.compareAndSet(false, true)) {
                done.set(true)
                stopOther.set(true)
                tmp.renameTarget(dst)
            } else {
                runCatching { tmp.delete() }
            }
        }

        val engines = ArrayList<Thread>()
        if (ranged.isNotEmpty() && total > MIN_CHUNK) {
            val segFile = File(dst.parentFile, dst.name + ".seg.dl")
            engines.add(Thread {
                try {
                    segmented(ranged, segFile, total, client, connections,
                        { got, t, sp, lb -> report(got, t, sp, lb) },
                        { isCancelled() || stopOther.get() })
                    claimWin(segFile)
                } catch (e: Throwable) {
                    runCatching { segFile.delete() }
                    errs.add(e.message ?: "分段线路失败")
                }
            })
        }
        val single = if (plain.isNotEmpty()) plain else ranged
        if (single.isNotEmpty()) {
            val oneFile = File(dst.parentFile, dst.name + ".one.dl")
            engines.add(Thread {
                try {
                    wholeRace(single, oneFile, total, client,
                        { got, t, lb -> report(got, t, 0, lb) },
                        { isCancelled() || stopOther.get() })
                    claimWin(oneFile)
                } catch (e: Throwable) {
                    runCatching { oneFile.delete() }
                    errs.add(e.message ?: "整包线路失败")
                }
            })
        }
        engines.forEach { it.isDaemon = true; it.start() }
        while (engines.any { it.isAlive } && !isCancelled()) Thread.sleep(200)
        stopOther.set(true)
        engines.forEach { runCatching { it.interrupt() } }

        if (isCancelled()) { cleanup(dst); throw Fail("已取消") }
        if (!done.get() || !dst.isFile || (total > 0 && dst.length() != total)) {
            cleanup(dst)
            throw Fail(errs.joinToString("；").ifBlank { "几条线路都没下成" })
        }
        cleanup(dst)
        return dst
    }

    private fun cleanup(dst: File) {
        dst.parentFile?.listFiles()?.forEach {
            if (it != dst && it.name.startsWith(dst.name)) runCatching { it.delete() }
        }
    }

    private fun File.renameTarget(to: File) {
        if (to.exists()) to.delete()
        if (!renameTo(to)) {
            inputStream().use { a -> FileOutputStream(to).use { b -> a.copyTo(b, 1 shl 16) } }
            runCatching { delete() }
        }
    }

    /** 每条线问一次「能不能只给第 0-1 个字节」：回 206 的才真支持分段，顺带把总大小问回来 */
    private fun probe(
        sources: List<Pair<String, String>>,
        client: OkHttpClient,
        isCancelled: () -> Boolean
    ): List<Probe> {
        val out = Collections.synchronizedList(ArrayList<Probe>())
        val threads = sources.map { (url, label) ->
            Thread {
                if (isCancelled()) return@Thread
                val p = Probe(url, label, -1, false)
                runCatching {
                    client.newCall(
                        Request.Builder().url(url)
                            .header("Range", "bytes=0-1")
                            .header("User-Agent", "LingxiChat-Android")
                            .build()
                    ).execute().use { r ->
                        val cr = r.header("Content-Range")
                        if (r.code == 206 && cr != null) {
                            p.size = cr.substringAfterLast('/').toLongOrNull() ?: -1L
                            p.acceptRanges = true
                        } else if (r.code == 200) {
                            val len = r.body?.contentLength() ?: -1L
                            p.size = len
                            p.acceptRanges = len > MIN_CHUNK &&
                                r.header("Accept-Ranges").orEmpty().contains("bytes", true)
                        }
                    }
                }
                if (p.size > 0) out.add(p)
            }.apply { isDaemon = true; start() }
        }
        threads.forEach { runCatching { it.join(8_000) } }
        // 探测回来的顺序是乱的，按传进来的期望优先级重排
        val order = sources.withIndex().associate { (i, s) -> s.first to i }
        return out.sortedBy { order[it.url] ?: Int.MAX_VALUE }
    }

    private fun segmented(
        probes: List<Probe>,
        dst: File,
        total: Long,
        client: OkHttpClient,
        connections: Int,
        onProgress: (Long, Long, Long, String) -> Unit,
        isCancelled: () -> Boolean
    ) {
        runCatching { dst.delete() }
        RandomAccessFile(dst, "rw").use { it.setLength(total) }
        val chunk = max(MIN_CHUNK, total / (connections.toLong() * 4L))
        val parts = ConcurrentLinkedQueue<LongArray>()
        var from = 0L
        while (from < total) {
            parts.add(longArrayOf(from, min(total - 1, from + chunk - 1)))
            from += chunk
        }
        val got = AtomicLong(0)
        val left = AtomicInteger(parts.size)
        val rr = AtomicInteger(0)
        val broken = AtomicBoolean(false)
        val calls = Collections.synchronizedList(ArrayList<okhttp3.Call>())
        val labels = probes.joinToString("/") { it.label }
        val reason = arrayOf("分段没能全部下完")

        val workers = (0 until connections).map {
            Thread {
                while (!broken.get() && !isCancelled()) {
                    val seg = parts.poll() ?: break
                    var ok = false
                    for (tryNo in probes.indices) {
                        if (ok || broken.get() || isCancelled()) break
                        val p = probes[(rr.getAndIncrement() and 0x7FFFFFFF) % probes.size]
                        ok = runOne(p, seg, dst, got, client, calls, isCancelled)
                        if (!ok) reason[0] = "${p.label} 有一段没下完"
                    }
                    if (!ok) { broken.set(true); break }
                    if (left.decrementAndGet() == 0) break
                }
            }.apply { isDaemon = true; start() }
        }
        var last = 0L
        var lastAt = System.currentTimeMillis()
        var shown = 0L
        while (workers.any { it.isAlive } && !isCancelled()) {
            Thread.sleep(500)
            val d = got.get()
            val dt = max(1L, System.currentTimeMillis() - lastAt)
            val inst = (d - last) * 1000 / dt
            if (inst > 0) shown = shown / 2 + inst / 2
            if (d > 0) { last = d; lastAt = System.currentTimeMillis() }
            onProgress(d, total, shown, labels)
        }
        workers.forEach { runCatching { it.join(2_000) } }
        onProgress(got.get(), total, shown, labels)
        synchronized(calls) { calls.forEach { runCatching { it.cancel() } } }
        if (isCancelled()) { runCatching { dst.delete() }; throw Fail("已取消") }
        if (broken.get() || got.get() != total || left.get() > 0) {
            runCatching { dst.delete() }
            throw Fail(reason[0])
        }
    }

    /** 抓一段：写满返回 true；线断了或被取消返回 false，让别的线补这一段 */
    private fun runOne(
        p: Probe,
        seg: LongArray,
        tmp: File,
        got: AtomicLong,
        client: OkHttpClient,
        calls: MutableList<okhttp3.Call>,
        isCancelled: () -> Boolean
    ): Boolean {
        var call: okhttp3.Call? = null
        var pos = seg[0]
        try {
            call = client.newCall(
                Request.Builder().url(p.url)
                    .header("Range", "bytes=${seg[0]}-${seg[1]}")
                    .header("User-Agent", "LingxiChat-Android")
                    .build()
            ).also { synchronized(calls) { calls.add(it) } }
            call.execute().use { r ->
                if (r.code != 206) return false
                val input = r.body?.byteStream() ?: return false
                RandomAccessFile(tmp, "rw").channel.use { ch ->
                    val buf = ByteArray(128 * 1024)
                    while (pos <= seg[1] && !isCancelled()) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        val want = min(n.toLong(), seg[1] - pos + 1).toInt()
                        var off = 0
                        while (off < want && pos <= seg[1]) {
                            val w = ch.write(ByteBuffer.wrap(buf, off, want - off), pos)
                            if (w <= 0) break
                            off += w
                            pos += w
                            got.addAndGet(w.toLong())
                        }
                    }
                }
            }
            return pos > seg[1]
        } catch (_: Throwable) {
            return false
        } finally {
            synchronized(calls) { call?.let { calls.remove(it) } }
            runCatching { call?.cancel() }
        }
    }

    /** 整包竞速：每条线各下各的，12 秒没进账就掐掉那条；先下完且大小对得上的那份交出去 */
    private fun wholeRace(
        probes: List<Probe>,
        dst: File,
        total: Long,
        client: OkHttpClient,
        onProgress: (Long, Long, String) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val taken = AtomicBoolean(false)
        val winnerIdx = AtomicInteger(-1)
        val lastTick = probes.map { AtomicLong(System.currentTimeMillis()) }
        val callSlots = probes.map { arrayOfNulls<okhttp3.Call>(1) }
        val threads = probes.mapIndexed { idx, p ->
            val mine = File(dst.parentFile, "${dst.name}.$idx")
            Thread {
                var sum = 0L
                val call = client.newCall(
                    Request.Builder().url(p.url).header("User-Agent", "LingxiChat-Android").build()
                )
                callSlots[idx][0] = call
                try {
                    call.execute().use { r ->
                        if (!r.isSuccessful) return@Thread
                        val input = r.body?.byteStream() ?: return@Thread
                        val declared = if (total > 0) total else r.body?.contentLength() ?: 0L
                        FileOutputStream(mine).use { out ->
                            val buf = ByteArray(128 * 1024)
                            while (!taken.get() && !isCancelled()) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                sum += n
                                lastTick[idx].set(System.currentTimeMillis())
                                onProgress(sum, declared, p.label)
                            }
                        }
                        if (declared > 0 && mine.length() != declared) return@Thread
                        if (taken.compareAndSet(false, true)) winnerIdx.set(idx)
                    }
                } catch (_: Throwable) {
                } finally {
                    if (winnerIdx.get() != idx) runCatching { mine.delete() }
                }
            }.apply { isDaemon = true; start() }
        }
        val deadline = System.currentTimeMillis() + 300_000
        while (!taken.get() && threads.any { it.isAlive } &&
            System.currentTimeMillis() < deadline && !isCancelled()
        ) {
            Thread.sleep(500)
            val now = System.currentTimeMillis()
            probes.indices.forEach { i ->
                if (!threads[i].isAlive) return@forEach
                if (now - lastTick[i].get() > 12_000L) {
                    lastTick[i].set(now)
                    runCatching { callSlots[i][0]?.cancel() }
                }
            }
        }
        callSlots.forEach { runCatching { it[0]?.cancel() } }
        threads.forEach { runCatching { it.interrupt() } }
        val win = winnerIdx.get()
        if (!taken.get() || win < 0) throw Fail("几条整包线路都没下成")
        File(dst.parentFile, "${dst.name}.$win").renameTarget(dst)
    }
}
