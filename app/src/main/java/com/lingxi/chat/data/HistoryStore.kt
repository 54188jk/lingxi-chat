package com.lingxi.chat.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.Manifest
import java.io.File

/**
 * 历史安装包归档：下载完成后往内部储存的「历史记录」文件夹放一份 APK，
 * 用户在文件管理器里能直接看到并点开安装，系统清缓存也不会丢。
 *
 * Android 11 以上想在内部储存根目录建文件夹必须有「所有文件访问」权限；
 * 没有该权限时退到 MediaStore 写入 下载/历史记录，同样可见可安装。
 */
object HistoryStore {

    const val FOLDER = "历史记录"

    /** 落盘结果：展示给用户的路径 + 可直接安装的文件或 Uri */
    class Saved(val displayPath: String, val file: File?, val uri: Uri?)

    /** 能不能直接在内部储存根目录建「历史记录」文件夹 */
    fun canUseRoot(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** 引导用户去开权限：11+ 跳「所有文件访问」设置页，10 及以下需要运行时授权 */
    fun openRootPermissionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun rootDir(): File? {
        val base = Environment.getExternalStorageDirectory() ?: return null
        return File(base, FOLDER)
    }

    /**
     * 把下载好的 APK 复制一份进「历史记录」。
     * 任何一步失败都不影响本次安装，只是少一份存档。
     * 位置由用户选过的方式决定：选过「根目录」就优先根目录，选过「下载目录」就优先 MediaStore。
     */
    fun save(context: Context, src: File, version: String): Saved? {
        if (!src.exists()) return null
        val name = "灵犀AI-v$version.apk"
        val store = ConfigStore(context)
        val rootFirst = store.archiveLocation != "download"
        val saved = if (rootFirst) {
            saveToRoot(context, src, name) ?: saveToMediaStore(context, src, name)
        } else {
            saveToMediaStore(context, src, name) ?: saveToRoot(context, src, name)
        } ?: saveToAppDir(context, src, name)
        saved?.let { store.lastArchivePath = it.displayPath }
        return saved
    }

    private fun saveToRoot(context: Context, src: File, name: String): Saved? = try {
        if (!canUseRoot(context)) {
            null
        } else {
            val dir = rootDir()
            if (dir == null || ( !dir.exists() && !dir.mkdirs())) null
            else {
                val dst = File(dir, name)
                src.copyTo(dst, overwrite = true)
                if (dst.length() != src.length()) null else Saved("内部储存/$FOLDER", dst, null)
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun saveToMediaStore(context: Context, src: File, name: String): Saved? = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) null else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$FOLDER/")
                put(MediaStore.MediaColumns.SIZE, src.length())
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            )
            if (uri == null) null
            else if (context.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } == null) null
            else Saved("内部储存/下载/$FOLDER", null, uri)
        }
    } catch (e: Exception) {
        null
    }

    /** 最后兜底：应用专属目录，任何设备都可写，只是别的文件管理器看不到 */
    private fun saveToAppDir(context: Context, src: File, name: String): Saved? = try {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, FOLDER)
        if (dir.exists() || dir.mkdirs()) {
            val dst = File(dir, name)
            src.copyTo(dst, overwrite = true)
            Saved("应用文件夹/$FOLDER", dst, null)
        } else null
    } catch (e: Exception) {
        null
    }

    /** 一条归档记录：文件或 MediaStore Uri 二者其一可用即可安装 */
    class Item(val version: String, val file: File?, val uri: Uri?, val displayPath: String) {

        /** 给列表用的一行文案：版本号 + 大小 + 位置 */
        fun displayVersion(context: Context): String {
            val kb = size(context)
            val sizeText = if (kb <= 0) "大小未知" else "%.1f MB".format(java.util.Locale.US, kb / 1024.0 / 1024.0)
            return "v$version · $sizeText · $displayPath"
        }

        fun size(context: Context): Long {
            file?.let { f -> return runCatching { f.length() }.getOrDefault(0L) }
            val u = uri ?: return 0L
            return runCatching {
                context.contentResolver.query(u, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
            }.getOrDefault(0L)
        }
    }

    /** 当前会存到哪个位置（给设置页显示） */
    fun targetDescription(context: Context): String =
        if (canUseRoot(context)) "内部储存/$FOLDER"
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "内部储存/下载/$FOLDER"
        else "应用文件夹/$FOLDER（授予存储权限后改为内部储存/$FOLDER）"

    private fun versionOf(name: String): String =
        name.removePrefix("灵犀AI-v").removeSuffix(".apk")

    /** 已归档的安装包，按版本名从新到旧 */
    fun list(context: Context): List<Item> {
        val out = LinkedHashMap<String, Item>()
        fun put(item: Item) {
            if (item.version.isNotBlank() && !out.containsKey(item.version)) out[item.version] = item
        }
        runCatching {
            rootDir()?.takeIf { it.isDirectory }?.listFiles { f ->
                f.isFile && f.name.endsWith(".apk")
            }?.forEach { f ->
                put(Item(versionOf(f.name), f, null, "内部储存/$FOLDER"))
            }
        }
        runCatching {
            val appDir = File(context.getExternalFilesDir(null) ?: context.filesDir, FOLDER)
            appDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }?.forEach { f ->
                put(Item(versionOf(f.name), f, null, "应用文件夹/$FOLDER"))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val proj = arrayOf(android.provider.BaseColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("Download/$FOLDER%"), null
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(android.provider.BaseColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val nm = c.getString(nameCol) ?: continue
                        if (!nm.endsWith(".apk")) continue
                        val uri = Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            c.getLong(idCol).toString()
                        )
                        put(Item(versionOf(nm), null, uri, "内部储存/下载/$FOLDER"))
                    }
                }
            }
        }
        return out.entries.sortedByDescending { it.key }.map { it.value }
    }

    /** 删掉某个归档版本，返回是否真的删掉了 */
    fun delete(context: Context, item: Item): Boolean = runCatching {
        val f = item.file
        val u = item.uri
        when {
            f != null -> f.delete()
            u != null -> context.contentResolver.delete(u, null, null) > 0
            else -> false
        }
    }.getOrDefault(false)
}
