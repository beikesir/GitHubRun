package com.example.lanshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File

object ImageUtils {

    private const val TAG = "ImageUtils"

    /**
     * 从 URI 读取图片 -> 原样转 Base64 data URL（不缩放、不重编码）。
     *
     * 早期版本提供「原图 / 压缩」开关，压缩会重编码为 JPEG 并缩放到长边 1600，
     * 画质与格式皆有损失；该开关现已移除，图片一律保留原始字节与格式。
     * 输出与浏览器端完全一致：data:image/xxx;base64,xxxx
     */
    fun uriToDataUrl(ctx: Context, uri: Uri): String? {
        return try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            if (bytes.isEmpty()) return null

            // 用文件头嗅探真实 MIME，避免分享来源未正确声明类型时接收端无法识别
            val mime = sniffMime(bytes, ctx.contentResolver.getType(uri))
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            "data:$mime;base64,$base64"
        } catch (e: Exception) {
            Log.e(TAG, "原图读取失败", e)
            null
        }
    }

    /**
     * 解码 data URL 供界面显示，并按 EXIF 旋转到正确朝向。
     *
     * 发送端已不再重编码，照片的 EXIF 朝向信息被完整保留；
     * 而原生 BitmapFactory 不会自动应用该信息，故在显示侧修正，
     * 避免竖拍照片在聊天里横躺。
     *
     * @param maxEdge 采样目标边长，仅影响显示用的解码尺寸，不影响传输内容
     */
    fun decodeForDisplay(dataUrl: String, maxEdge: Int = 1280): Bitmap? {
        return try {
            val base64 = dataUrl.substringAfter("base64,", "")
            if (base64.isEmpty()) return null
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val bmp = decodeSampled(bytes, maxEdge) ?: return null
            val degrees = readRotation(bytes)
            if (degrees == 0) bmp else rotateBitmap(bmp, degrees)
        } catch (e: Exception) {
            Log.e(TAG, "图片解码失败", e)
            null
        }
    }

    /**
     * 从 HTTP 直链下载并解码（外置消息 content 为空时使用）。
     *
     * 引用式消息落盘后只留 fileId，退后台重进 / 重启后 content 为空，
     * 此时必须从 fileUrl 直链取回原图，否则全屏查看会误报「图片解码失败」。
     */
    fun decodeFromUrl(url: String, maxEdge: Int = 2048): Bitmap? {
        return try {
            val conn = java.net.URL(url).openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            val bytes = conn.getInputStream().use { it.readBytes() }
            if (bytes.isEmpty()) return null
            val bmp = decodeSampled(bytes, maxEdge) ?: return null
            val degrees = readRotation(bytes)
            if (degrees == 0) bmp else rotateBitmap(bmp, degrees)
        } catch (e: Exception) {
            Log.e(TAG, "直链解码失败", e)
            null
        }
    }

    /**
     * 解码本机留底的图片（media/ 下的副本）。
     *
     * 客户端已改为只依赖自身存储，查看大图应优先读本机副本——
     * 中继重启后直链会失效，而本地文件依然在，不会误报「图片解码失败」。
     */
    fun decodeFromFile(file: File, maxEdge: Int = 2048): Bitmap? {
        return try {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return null
            val bmp = decodeSampled(bytes, maxEdge) ?: return null
            val degrees = readRotation(bytes)
            if (degrees == 0) bmp else rotateBitmap(bmp, degrees)
        } catch (e: Exception) {
            Log.e(TAG, "本地图片解码失败", e)
            null
        }
    }

    /**
     * 探测图片原始宽高（只解边界，不分配像素内存）。
     *
     * 列表排版要区分「横图以宽为准 / 竖图以高为准」，必须先知道原图是横是竖。
     * inJustDecodeBounds 只读文件头信息，速度快、不占内存。
     * 结果由调用方缓存，一张图只探测一次。
     */
    fun probeSize(dataUrl: String): Pair<Int, Int>? {
        return try {
            val base64 = dataUrl.substringAfter("base64,", "")
            if (base64.isEmpty()) return null
            boundsOf(android.util.Base64.decode(base64, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            null
        }
    }

    /** 从本机留底文件探测宽高：直接读文件流，不必把整份内容读进内存 */
    fun probeSize(file: File): Pair<Int, Int>? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            java.io.FileInputStream(file).use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        } catch (e: Exception) {
            null
        }
    }

    private fun boundsOf(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    /** 按目标边长采样解码，避免超大原图在列表里直接撑爆内存 */
    private fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
            sample *= 2
        }
        return BitmapFactory.decodeStream(
            ByteArrayInputStream(bytes), null,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }

    /** 依据魔数识别常见图片类型，识别不出时回退到 ContentResolver 的声明，再回退 jpeg */
    private fun sniffMime(bytes: ByteArray, declared: String?): String {
        fun hex(vararg idx: Int) = idx.map { bytes.getOrNull(it)?.toInt()?.and(0xFF) ?: -1 }
        val head = hex(0, 1, 2, 3)
        val known = when {
            head[0] == 0xFF && head[1] == 0xD8                       -> "image/jpeg"
            head[0] == 0x89 && head[1] == 0x50 && head[2] == 0x4E    -> "image/png"
            head[0] == 0x47 && head[1] == 0x49 && head[2] == 0x46    -> "image/gif"
            head[0] == 0x52 && head[1] == 0x49 && head[2] == 0x46 &&
            bytes.getOrNull(8)?.toInt()?.and(0xFF) == 0x57           -> "image/webp"
            head[0] == 0x42 && head[1] == 0x4D                       -> "image/bmp"
            else -> null
        }
        if (known != null) return known
        return if (!declared.isNullOrBlank() && declared.startsWith("image/")) declared
               else "image/jpeg"
    }

    /** 从字节流读取 EXIF 朝向（原图直传后该信息完整保留） */
    private fun readRotation(bytes: ByteArray): Int {
        return try {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun rotateBitmap(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        return try {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (e: OutOfMemoryError) {
            bmp
        }
    }
}
