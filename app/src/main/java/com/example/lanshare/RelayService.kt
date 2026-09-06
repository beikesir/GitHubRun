package com.example.lanshare

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台服务：保持连接在后台存活
 * - 常驻通知显示连接状态
 * - 收到新消息弹出通知
 * - 收到图片自动存入相册（按「时间+毫秒」命名，避免同秒覆盖）
 */
class RelayService : Service() {

    companion object {
        private const val TAG = "RelayService"
        private const val CH_ID = "lanshare_relay"
        private const val CH_MSG = "lanshare_msg"
        private const val NOTI_RELAY = 1001
        private var msgId = 2000
    }

    private lateinit var ws: WebSocketManager

    /** 收到消息：发通知 + 图片存相册 */
    private val msgListener: MsgListener = { msg ->
        when (msg.type) {
            "image" -> {
                // saveDataUrl 返回的是「显示名」（如 lan_20260903_143025_123.jpg），不是文件路径，
                // 因此直接拼接即可，无需 java.io.File 取 name（原先漏了 import 会导致编译失败）
                val saved = saveImageToGallery(msg.content)
                notifyMsg("[图片]", saved?.let { "已保存到相册：$it" } ?: "收到一张图片")
            }
            "file" -> {
                // 文件落到公共下载目录，保留原始文件名
                val saved = if (msg.content.isBlank()) null
                            else FileSaver.saveDataUrl(this, msg.content, msg.fileName, msg.mime)
                val name = msg.fileName.ifBlank { "文件" }
                notifyMsg("📎 $name",
                    saved?.let { "已保存到下载目录：LanShare/$it" } ?: "收到文件 $name（点击打开应用查看）")
            }
            "locked" -> notifyMsg("🔒 加密消息", "本机口令不匹配，无法解密")
            else -> {
                val from = if (msg.senderName.isNotBlank()) "${msg.senderName}：" else ""
                notifyMsg(msg.content, "$from${msg.content}")
            }
        }
    }

    private val stateListener: (Boolean, String) -> Unit = { _, text ->
        updateRelayNotification(text)
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        ws = (application as LanShareApp).wsManager
        ws.addListener(msgListener)
        ws.addStateListener(stateListener)
        startForeground(NOTI_RELAY, buildRelayNotification("● 启动中…"))
        // 连接：isActive() 内含 ready/connecting 判断，
        // 避免在界面已建连的情况下再建第二条（此前正是重复消息的根源）
        if (!ws.isActive()) {
            ws.connect(PrefsManager.getUrl(this), PrefsManager.getChannel(this))
        } else {
            ws.ensureConnected()
        }
        Log.d(TAG, "前台服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ws.ensureConnected()
        return START_STICKY      // 被系统杀掉后自动重启
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ws.removeListener(msgListener)
        ws.removeStateListener(stateListener)
        super.onDestroy()
    }

    // ==================== 通知 ====================
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "中继状态", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_MSG, "新消息", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun pendingIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, 0, i, flag)
    }

    private fun buildRelayNotification(text: String) =
        NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("局域网快传 · 频道 ${ws.currentChannel()}")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent())
            .setColor(0xFF7AA2F7.toInt())
            .setOngoing(true)
            .build()

    private fun updateRelayNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTI_RELAY, buildRelayNotification(text))
    }

    private fun notifyMsg(title: String, body: String) {
        val n = NotificationCompat.Builder(this, CH_MSG)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent())
            .setColor(0xFF7AA2F7.toInt())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(msgId++, n)
    }

    // ==================== 图片存相册 ====================
    /** 复用 GallerySaver：带毫秒命名 + Android 9 以下扫描媒体库 */
    private fun saveImageToGallery(dataUrl: String): String? {
        return GallerySaver.saveDataUrl(this, dataUrl)
    }
}
