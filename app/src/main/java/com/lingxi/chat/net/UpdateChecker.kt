package com.lingxi.chat.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val LATEST_URL = "https://api.github.com/repos/54188jk/lingxi-chat/releases/latest"
    private const val LIST_URL = "https://api.github.com/repos/54188jk/lingxi-chat/releases?per_page=30"
    private const val RELEASES_PAGE = "https://github.com/54188jk/lingxi-chat/releases"

    /**
     * 版本检查源列表。
     *
     * 多个源并行查询，谁先返回就用谁的结果；若都成功，取版本号更高的那个，
     * 所以「GitHub + Gitee」可以共存并自动择优，全程免 Token。
     * 新增公开源时在这里加一行即可，App 会自动参与测速。
     */
    private val checkSources = listOf(
        // Gitee 排第一：国内直连，通常最先返回且速度最快
        CheckSource("Gitee", "https://gitee.com/api/v5/repos/wuzhuf/lingxi-chat/releases/latest", GITEE),
        CheckSource("GitHub", LATEST_URL, GITHUB)
    )

    /**
     * Gitee 上按约定命名的下载直链（免 Token，国内直连最快，用作下载首选源）。
     *
     * 注意：正式签名版资产名带 `-release` 后缀（灵犀AI-v1.161-release.apk），
     * 与 GitHub 上的 lingxi-vX.apk 命名不同，所以这里必须用 Gitee 的命名规则。
     * 拿不到确切资产名时（Gitee API 没返回）才会退化到按约定拼接。
     */
    fun giteeDownloadUrl(version: String): String =
        "https://gitee.com/wuzhuf/lingxi-chat/releases/download/v$version/" +
                java.net.URLEncoder.encode("灵犀AI-v$version-release.apk", "UTF-8").replace("+", "%20")

    private const val GITHUB = 0
    private const val GITEE = 1

    private class CheckSource(val name: String, val url: String, val format: Int)

    data class ReleaseInfo(
        val version: String,
        val notes: String,
        val downloadUrl: String,
        /** 发布时间，形如 2026-10-07 10:30:55（本地时区） */
        val publishedAt: String = "",
        /** 安装包字节数，未知为 0 */
        val sizeBytes: Long = 0L,
        val pageUrl: String = RELEASES_PAGE,
        /** 这个版本信息来自哪个源 */
        val sourceName: String = "GitHub"
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 并行查询所有源，用 CompletionService 真正「谁先返回用谁」：
     * 不再按列表顺序阻塞等待排在前面的源，因此即便 Gitee 排在首位且偶发变慢，
     * GitHub 一旦先返回也能立即被采用，整体检查耗时取决于最快的那个源。
     * 收集到的成功结果里取版本号最高的一个返回。
     */
    fun fetchLatestFast(timeoutMs: Long = 8000): ReleaseInfo? {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(checkSources.size)
        val completion = java.util.concurrent.ExecutorCompletionService<ReleaseInfo?>(pool)
        checkSources.forEach { src -> completion.submit { fetchFrom(src) } }
        val results = mutableListOf<ReleaseInfo>()
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            repeat(checkSources.size) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) return@repeat
                try {
                    // poll 取最先完成的任务，future.get() 此刻不会阻塞；
                    // 慢源（如偶发变慢的 Gitee）不会再卡住整体
                    completion.poll(remain, java.util.concurrent.TimeUnit.MILLISECONDS)
                        ?.get()
                        ?.let { results.add(it) }
                } catch (e: Exception) {
                    // 该源失败或超时，继续取下一个已完成的源
                }
            }
        } finally {
            pool.shutdownNow()
        }
        if (results.isEmpty()) return null
        return results.maxByOrNull { info -> versionValue(info.version) }
    }

    private fun versionValue(v: String): Long =
        v.split(".").fold(0L) { acc, s -> acc * 1000 + (s.toIntOrNull() ?: 0) }

    private fun fetchFrom(src: CheckSource): ReleaseInfo? {
        return try {
            val req = Request.Builder()
                .url(src.url)
                .header("User-Agent", "LingxiChat-Android")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: return null)
                if (src.format == GITEE) parseGiteeRelease(json) else parseRelease(json, src.name)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Gitee 的 release JSON 与 GitHub 有两处差异：
     * 1. 没有 published_at，时间在 created_at（形如 2026-10-07T14:18:33+08:00）
     * 2. 资产条目里没有 size / content_type，要靠文件名后缀挑出 apk
     */
    private fun parseGiteeRelease(json: JSONObject): ReleaseInfo? {
        val tag = json.optString("tag_name", "").removePrefix("v")
        val parts = tag.split(".")
        if (tag.isBlank() || parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return null
        var url = ""
        var size = 0L
        json.optJSONArray("assets")?.let { assets ->
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name", "").endsWith(".apk")) {
                    url = a.optString("browser_download_url", "")
                    size = a.optLong("size", 0L)
                    break
                }
            }
        }
        if (url.isBlank()) url = giteeDownloadUrl(tag)
        return ReleaseInfo(
            version = tag,
            notes = json.optString("body", ""),
            downloadUrl = url,
            publishedAt = formatCreatedAt(json.optString("created_at", "")),
            sizeBytes = size,
            pageUrl = json.optString("html_url", "https://gitee.com/wuzhuf/lingxi-chat/releases"),
            sourceName = "Gitee"
        )
    }

    /** 解析 Gitee 的 created_at（带时区偏移） */
    private fun formatCreatedAt(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
            val date = parser.parse(raw) ?: return ""
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(date)
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseRelease(json: JSONObject, sourceName: String): ReleaseInfo? {
        val tag = json.optString("tag_name", "").removePrefix("v")
        val parts = tag.split(".")
        // 只要纯数字版本号，公告/测试之类的 tag 跳过
        if (tag.isBlank() || parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return null
        val notes = json.optString("body", "")
        var url = ""
        var size = 0L
        val assets = json.optJSONArray("assets")
        if (assets != null && assets.length() > 0) {
            val asset = assets.getJSONObject(0)
            url = asset.optString("browser_download_url", "")
            size = asset.optLong("size", 0L)
        }
        if (url.isBlank()) url = RELEASES_PAGE
        return ReleaseInfo(
            tag, notes, url,
            publishedAt = formatPublished(json.optString("published_at", "")),
            sizeBytes = size,
            pageUrl = json.optString("html_url", RELEASES_PAGE),
            sourceName = sourceName
        )
    }

    fun fetchLatest(): ReleaseInfo? =
        fetchLatestFast() ?: fetchFrom(CheckSource("GitHub", LATEST_URL, GITHUB))

    /** 历史版本列表的内存缓存，5 分钟内复用，避免反复打 GitHub/Gitee */
    @Volatile
    private var releasesCache: Pair<Long, List<ReleaseInfo>>? = null
    private const val RELEASES_CACHE_MS = 5 * 60_000L

    /** GitHub 返回 ISO 时间，转成本地时区的 yyyy-MM-dd HH:mm:ss */
    private fun formatPublished(iso: String): String {
        if (iso.isBlank()) return ""
        return try {
            val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = parser.parse(iso) ?: return ""
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(date)
        } catch (e: Exception) {
            ""
        }
    }

    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * 历史版本列表（含最新版），失败返回空列表。
     *
     * 旧实现只请求 GitHub 单一源，国内访问 GitHub API 经常要等数秒，
     * 这是「历史版本页打开慢」的根因。现改为 Gitee / GitHub 并行竞速，
     * 谁先返回非空结果就用谁；成功结果进内存缓存 5 分钟，避免反复请求。
     */
    fun fetchReleases(): List<ReleaseInfo> {
        val cached = releasesCache
        if (cached != null && System.currentTimeMillis() - cached.first < RELEASES_CACHE_MS) {
            return cached.second
        }
        val list = runCatching {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val completion = java.util.concurrent.ExecutorCompletionService<List<ReleaseInfo>>(pool)
                completion.submit { fetchReleasesFromGitee() } // Gitee 先提交：国内优先
                completion.submit { fetchReleasesFromGitHub() }
                val deadline = System.currentTimeMillis() + 9000
                repeat(2) {
                    val remain = deadline - System.currentTimeMillis()
                    if (remain <= 0) return@repeat
                    try {
                        // 取最先完成且非空的结果，慢源不会阻塞快源
                        completion.poll(remain, java.util.concurrent.TimeUnit.MILLISECONDS)
                            ?.get()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { return@runCatching it }
                    } catch (e: Exception) {
                        // 该源失败，继续取下一个已完成的源
                    }
                }
                emptyList()
            } finally {
                pool.shutdownNow()
            }
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) releasesCache = System.currentTimeMillis() to list
        return list
    }

    /** 历史版本：Gitee 源（国内直连，通常最快）。字段与 GitHub 略有差异，单独解析。 */
    private fun fetchReleasesFromGitee(): List<ReleaseInfo> {
        return try {
            val req = Request.Builder()
                .url("https://gitee.com/api/v5/repos/wuzhuf/lingxi-chat/releases?per_page=30")
                .header("User-Agent", "LingxiChat-Android")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = org.json.JSONArray(resp.body?.string() ?: return emptyList())
                val out = mutableListOf<ReleaseInfo>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val tag = o.optString("tag_name", "").removePrefix("v")
                    if (tag.split(".").any { it.toIntOrNull() == null }) continue
                    var url = ""
                    var size = 0L
                    o.optJSONArray("assets")?.let { assets ->
                        for (k in 0 until assets.length()) {
                            val a = assets.optJSONObject(k) ?: continue
                            if (a.optString("name", "").endsWith(".apk")) {
                                url = a.optString("browser_download_url", "")
                                size = a.optLong("size", 0L)
                                break
                            }
                        }
                    }
                    if (url.isBlank()) url = giteeDownloadUrl(tag)
                    out.add(
                        ReleaseInfo(
                            version = tag,
                            notes = o.optString("body", ""),
                            downloadUrl = url,
                            publishedAt = formatCreatedAt(o.optString("created_at", "")),
                            sizeBytes = size,
                            pageUrl = o.optString("html_url", "https://gitee.com/wuzhuf/lingxi-chat/releases"),
                            sourceName = "Gitee"
                        )
                    )
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 历史版本：GitHub 源（兜底）。 */
    private fun fetchReleasesFromGitHub(): List<ReleaseInfo> {
        return try {
            val req = Request.Builder()
                .url(LIST_URL)
                .header("User-Agent", "LingxiChat-Android")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = org.json.JSONArray(resp.body?.string() ?: return emptyList())
                val out = mutableListOf<ReleaseInfo>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optBoolean("draft")) continue
                    val tag = o.optString("tag_name", "").removePrefix("v")
                    if (tag.split(".").any { it.toIntOrNull() == null }) continue
                    var url = ""
                    var size = 0L
                    o.optJSONArray("assets")?.let { assets ->
                        for (k in 0 until assets.length()) {
                            val a = assets.optJSONObject(k) ?: continue
                            if (a.optString("content_type", "").contains("android") ||
                                a.optString("name", "").endsWith(".apk")
                            ) {
                                url = a.optString("browser_download_url", "")
                                size = a.optLong("size", 0L)
                                break
                            }
                        }
                    }
                    if (url.isBlank()) continue
                    out.add(
                        ReleaseInfo(
                            version = tag,
                            notes = o.optString("body", ""),
                            downloadUrl = url,
                            publishedAt = formatPublished(o.optString("published_at", "")),
                            sizeBytes = size,
                            pageUrl = o.optString("html_url", RELEASES_PAGE)
                        )
                    )
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
