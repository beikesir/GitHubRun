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
     * 从 URI 读取图片 -> 修正 EXIF 旋转 -> 压缩到 maxSize -> 转 JPEG -> Base64 data URL
     * 输出格式与油猴脚本完全一致：data:image/jpeg;base64,xxxx
     */
    fun uriToDataUrl(ctx: Context, uri: Uri, maxSize: Int = 1600, quality: Int = 85): String? {
        return try {
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
