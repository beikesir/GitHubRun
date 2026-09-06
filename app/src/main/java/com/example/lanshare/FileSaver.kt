package com.example.lanshare

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任意格式文件保存到公共下载目录（Download/LanShare）。
 *
 * 与 GallerySaver（图片 -> 相册）分开，是因为文件种类不限、需保留原始文件名与扩展名，
 * 且应落在"下载"而非"图片"，符合用户使用直觉。
 *
 * 文件名冲突时追加 _HHmmss_SSS 时间戳，避免覆盖同名文件。
 */
object FileSaver {

    private const val TAG = "FileSaver"

    /**
     * 保存 data URL 形式的文件内容。
     * @param dataUrl  data:<mime>;base64,<内容>
     * @param fileName 原始文件名（含扩展名）
     * @param mime     MIME 类型，用于让系统识别文件类型
     * @return 实际保存的文件名；失败返回 null
     */
    fun saveDataUrl(ctx: Context, dataUrl: String, fileName: String, mime: String): String? {
        return try {
            val b64 = dataUrl.substringAfter("base64,", "")
            if (b64.isEmpty()) {
                Log.e(TAG, "内容为空或格式错误")
                return null
            }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val name = safeName(fileName)
            write(ctx, name, mime, bytes)
            name
        } catch (e: Exception) {
            Log.e(TAG, "保存失败: ${e.message}")
            null
        }
    }

    /** 清洗文件名：去掉路径分隔符与系统非法字符，并保证非空 */
    private fun safeName(raw: String): String {
        var n = raw.trim().replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
        if (n.isEmpty() || n == "." || n == "..") n = "file"
        // 限制长度，避免极端文件名导致 MediaStore 插入失败
        if (n.length > 120) {
            val ext = n.substringAfterLast('.', "")
            n = if (ext.isNotEmpty() && ext.length < 10)
                n.substring(0, 120 - ext.length - 1) + "." + ext
            else n.substring(0, 120)
        }
        return n
    }

    private fun write(ctx: Context, name: String, mime: String, bytes: ByteArray) {
        val type = mime.ifBlank { "application/octet-stream" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 走 MediaStore，无需存储权限即可写入公共下载目录
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/LanShare")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri: Uri? = ctx.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) throw Exception("无法创建下载记录")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw Exception("无法写入文件")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
        } else {
            // Android 9 及以下：直接写入公共下载目录
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS)
            val folder = File(dir, "LanShare").apply { if (!exists()) mkdirs() }
            val f = uniqueFile(folder, name)
            FileOutputStream(f).use { it.write(bytes) }
            ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)))
        }
    }

    /** Android 9 及以下：同名文件自动加时间戳，避免覆盖 */
    private fun uniqueFile(folder: File, name: String): File {
        val f = File(folder, name)
        if (!f.exists()) return f
        val stamp = SimpleDateFormat("HHmmss_SSS", Locale.getDefault()).format(Date())
        val dot = name.lastIndexOf('.')
        return if (dot > 0) File(folder, "${name.substring(0, dot)}_$stamp.${name.substring(dot + 1)}")
               else File(folder, "${name}_$stamp")
    }
}
