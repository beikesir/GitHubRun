package com.example.lanshare

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.example.lanshare.databinding.ActivityShareBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统分享入口：相册 / 截屏 / 第三方 App 分享图片到本应用
 * 复用全局单例连接（不再自建连接，避免“自己发的图变成他人消息”）
 */
class ShareActivity : AppCompatActivity() {

    private lateinit var b: ActivityShareBinding
    private lateinit var ws: WebSocketManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityShareBinding.inflate(layoutInflater)
        setContentView(b.root)

        ws = (application as LanShareApp).wsManager

        // 说明：Intent.getParcelableExtra<T>() 这类「单参数 + reified 泛型」并非 Android 平台 API，
        // 平台只有双参数版 getParcelableExtra(String, Class<T>)（API 33+）。
        // 这里统一用 androidx.core 的 IntentCompat，自动兼容新旧系统，避免类型参数编译报错。
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> {
                val one = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java)
                one?.let { listOf(it) } ?: emptyList()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val many = IntentCompat.getParcelableArrayListExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java)
                many ?: emptyList()
            }
            else -> emptyList()
        }

        if (uris.isEmpty()) { showResult("未获取到图片", false); return }

        val channel = PrefsManager.getChannel(this)
        val url = PrefsManager.getUrl(this)
        if (!ws.isReady()) ws.connect(url, channel)

        sendAll(uris, channel)
    }

    private fun sendAll(uris: List<Uri>, channel: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 等待连接就绪（最多 10 秒）
            var waited = 0
            while (!ws.isReady() && waited < 10_000) { delay(200); waited += 200 }

            if (!ws.isReady()) {
                withContext(Dispatchers.Main) { showResult("❌ 无法连接中继，请检查电脑是否运行 relay", false) }
                return@launch
            }

            var ok = 0
            var fail = 0
            for (u in uris) {
                val dataUrl = ImageUtils.uriToDataUrl(this@ShareActivity, u)
                if (dataUrl != null && ws.sendImage(dataUrl)) ok++ else fail++
                if (uris.size > 1) delay(250)
            }

            withContext(Dispatchers.Main) {
                val msg = when {
                    fail == 0 -> "✅ 已发送 $ok 张到频道 $channel"
                    ok == 0 -> "❌ 发送失败"
                    else -> "⚠️ 成功 $ok 张，失败 $fail 张"
                }
                showResult(msg, fail == 0)
            }
        }
    }

    private fun showResult(msg: String, success: Boolean) {
        b.progress.visibility = View.GONE
        b.tvResult.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        b.root.postDelayed({ finish() }, if (success) 1200 else 2500)
    }
}
