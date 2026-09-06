package com.example.lanshare

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
 * 图片保存到相册（Pictures/LanShare）
 * 文件名含毫秒，避免同一秒内多张图互相覆盖
 */
object GallerySaver {

    private const val TAG = "GallerySaver"

    /** 保存 Bitmap，返回显示名；失败返回 null */
    fun saveBitmap(ctx: Context, bmp: Bitmap): String? {
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val name = "lan_$stamp.jpg"
            val bytes = bitmapToBytes(bmp)
            write(ctx, name, bytes)
            name
        } catch (e: Exception) {
            Log.e(TAG, "保存失败: ${e.message}")
            null
        }
    }

    /** 保存 data URL（来自网络的图片），返回显示名 */
    fun saveDataUrl(ctx: Context, dataUrl: String): String? {
        return try {
            val b64 = dataUrl.substringAfter("base64,", "")
            if (b64.isEmpty()) return null
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val name = "lan_$stamp.jpg"
            write(ctx, name, bytes)
            name
        } catch (e: Exception) {
            Log.e(TAG, "保存失败: ${e.message}")
            null
        }
    }

    private fun bitmapToBytes(bmp: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        return out.toByteArray()
    }

    private fun write(ctx: Context, name: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/LanShare")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri: Uri? = ctx.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) throw Exception("无法创建媒体记录")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw Exception("无法写入")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
        } else {
            // Android 9 及以下：写入公共图片目录并通知媒体库扫描
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES)
            val folder = File(dir, "LanShare").apply { if (!exists()) mkdirs() }
            val f = File(folder, name)
            FileOutputStream(f).use { it.write(bytes) }
            ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                Uri.fromFile(f)))
        }
    }
}
