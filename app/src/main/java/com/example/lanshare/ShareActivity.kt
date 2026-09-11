package com.example.lanshare

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat

/**
 * 系统分享入口（相册 / 截屏 / 第三方 App 分享图片或任意文件）。
 *
 * 这里刻意**不做任何界面**：取出 URI 后立刻转交 RelayService 在后台发送，
 * 随即 finish。窗口主题是完全透明的（Theme.LanShare.Transparent），
 * 因此用户感知不到 Activity 的存在——分享即走，不再弹出等待界面。
 *
 * 发送任务交给前台服务，Activity 销毁后仍能跑完，不会因界面消失而中断。
 * 走这条路径发出的内容带 source="share"，接收端据此自动落盘。
 */
class ShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }

        // 转交后台服务：即便此处立即 finish，发送也会在服务端继续完成
        val i = Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_SHARE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            // 把 URI 的临时读权限一并带过去，否则服务端可能读不到内容
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        } catch (e: Exception) {
            startService(i)
        }
        finish()
    }

    private fun extractUris(src: Intent?): List<Uri> {
        // 说明：Intent.getParcelableExtra<T>() 这类「单参数 + reified 泛型」并非 Android 平台 API，
        // 平台只有双参数版（API 33+）。统一用 androidx.core 的 IntentCompat 兼容新旧系统。
        return when (src?.action) {
            Intent.ACTION_SEND -> {
                val one = IntentCompat.getParcelableExtra(
                    src, Intent.EXTRA_STREAM, Uri::class.java)
                one?.let { listOf(it) } ?: emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(
                    src, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            }
            else -> emptyList()
        }
    }
}
