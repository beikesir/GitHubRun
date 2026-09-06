package com.example.lanshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.ByteArrayInputStream

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
