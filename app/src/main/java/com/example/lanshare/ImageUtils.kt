package com.example.lanshare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream

object ImageUtils {

    private const val TAG = "ImageUtils"

    /**
     * 从 URI 读取图片 -> 转 Base64 data URL。
     *
     * @param original true  = 原图直传：不缩放、不重编码，保留原始字节（体积可能较大）
     *                 false = 压缩：修正 EXIF 旋转后缩放到 maxSize 内并转 JPEG
     * 输出格式与浏览器端完全一致：data:image/xxx;base64,xxxx
     */
    fun uriToDataUrl(
        ctx: Context, uri: Uri,
        maxSize: Int = 1600, quality: Int = 85,
        original: Boolean = false
    ): String? {
        return try {
            if (original) return readOriginal(ctx, uri)

            // 1. 先只读边界，避免大图 OOM
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // 2. 计算采样率
            var sample = 1
            while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
                sample *= 2
            }

            // 3. 解码
            val raw = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                })
            } ?: return null

            // 4. 修正 EXIF 旋转（手机照片常见）
            val oriented = rotateBitmap(raw, readRotation(ctx, uri))

            // 5. 精确缩放到 maxSize 内
            val ratio = minOf(
                maxSize.toFloat() / oriented.width,
                maxSize.toFloat() / oriented.height,
                1f
            )
            val scaled = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    oriented,
                    (oriented.width * ratio).toInt(),
                    (oriented.height * ratio).toInt(),
                    true
                )
            } else oriented

            // 6. 压缩并 Base64
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val base64 = android.util.Base64.encodeToString(
                baos.toByteArray(),
                android.util.Base64.NO_WRAP
            )
            "data:image/jpeg;base64,$base64"
        } catch (e: Exception) {
            Log.e(TAG, "图片处理失败", e)
            null
        }
    }

    /**
     * 原图直传：直接把文件字节 Base64 化，不做任何解码/重编码。
     * 这样既完整保留画质，也避免大图解码带来的内存压力与 OOM 风险。
     */
    private fun readOriginal(ctx: Context, uri: Uri): String? {
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

    private fun readRotation(ctx: Context, uri: Uri): Int {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
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
