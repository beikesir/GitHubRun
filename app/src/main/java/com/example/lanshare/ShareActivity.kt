package com.example.lanshare

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
 * 系统分享入口：相册 / 截屏 / 第三方 App 分享图片或任意文件到本应用。
 *
 * 走这条路径发出的内容会带上 source="share" 标记，
 * 接收端（电脑扩展 / 另一台手机）据此自动落盘，无需对方再手动下载。
 *
 * 复用全局单例连接，不再自建连接，避免「自己发的图变成他人消息」。
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

        if (uris.isEmpty()) { showResult("未获取到内容", false); return }

        val channel = PrefsManager.getChannel(this)
        val url = PrefsManager.getUrl(this)
        // isActive() 同时覆盖 ready 与 connecting：
        // 若界面/服务已在建连，此处不再发起第二条连接（避免重复消息与连接泄漏）
        if (!ws.isActive()) ws.connect(url, channel)
        else if (ws.currentChannel() != channel) ws.setChannel(channel)

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
                val name = queryName(u)
                val mime = contentResolver.getType(u) ?: "application/octet-stream"
                val isImage = mime.startsWith("image/")
                val dataUrl = if (isImage) ImageUtils.uriToDataUrl(this@ShareActivity, u)
                              else readAllAsDataUrl(u, mime)
                val sent = if (dataUrl == null) false else {
                    if (isImage) ws.sendImage(dataUrl, viaShare = true)
                    else ws.sendFile(name, mime, querySize(u), dataUrl, viaShare = true)
                }
                if (sent) ok++ else fail++
                if (uris.size > 1) delay(250)
            }

            withContext(Dispatchers.Main) {
                val msg = when {
                    fail == 0 -> "✅ 已发送 $ok 项到频道 $channel"
                    ok == 0 -> "❌ 发送失败"
                    else -> "⚠️ 成功 $ok 项，失败 $fail 项"
                }
                showResult(msg, fail == 0)
            }
        }
    }

    /** 从 URI 取展示名，取不到时退回路径末段 */
    private fun queryName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return uri.lastPathSegment ?: "file"
    }

    /** 从 URI 取字节大小 */
    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst()) return c.getLong(i)
        }
        return 0L
    }

    /** 任意格式文件读成 data URL（原样，不压缩） */
    private fun readAllAsDataUrl(uri: Uri, mime: String): String? = try {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        "data:$mime;base64,$b64"
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun showResult(msg: String, success: Boolean) {
        b.progress.visibility = View.GONE
        b.tvResult.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        b.root.postDelayed({ finish() }, if (success) 1200 else 2500)
    }
}
