package com.example.lanshare

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat

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
        /** 消息通知固定 ID：反复更新同一条，而不是堆出一串通知 */
        const val NOTI_MSG = 1002

        /** 通知聚合窗口（毫秒）：这段时间内的多条消息合并成一条通知，避免刷屏 */
        private const val NOTI_AGGREGATE_MS = 2500L

        /** 聚合通知里最多展示几条摘要 */
        private const val NOTI_MAX_LINES = 6

        /** 系统分享转发：ShareActivity 收到后转交本服务在后台发送 */
        const val ACTION_SHARE = "com.example.lanshare.ACTION_SHARE"

    }

    private lateinit var ws: WebSocketManager

    /** 收到消息：发通知 + 图片存相册 */
    private val msgListener: MsgListener = { msg ->
        // 只有对方「通过系统分享」发来的图片 / 文件才自动落盘；
        // 常规消息保持安静，由用户在界面上长按手动下载。
        val autoSave = msg.source == "share"
        when (msg.type) {
            "image" -> {
                // saveDataUrl 返回的是「显示名」（如 lan_20260903_143025_123.jpg），不是文件路径，
                // 因此直接拼接即可，无需 java.io.File 取 name（原先漏了 import 会导致编译失败）
                // 阶段1：引用式消息优先走直链，content 为空时才退回 data URL
                val saved = if (autoSave) {
                    if (msg.fileUrl.isNotBlank()) GallerySaver.saveFromUrl(this, msg.fileUrl)
                    else saveImageToGallery(msg.content)
                } else null
                notifyMsg("[图片]",
                    saved?.let { "已保存到相册：$it" } ?: "收到一张图片（长按可下载）")
            }
            "file" -> {
                // 文件落到公共下载目录，保留原始文件名
                // 外置后 content 为空、内容在 fileUrl 直链上，必须优先走直链
                val saved = if (autoSave) {
                    if (msg.fileUrl.isNotBlank())
                        FileSaver.saveFromUrl(this, msg.fileUrl, msg.fileName, msg.mime)
                    else if (msg.content.isNotBlank())
                        FileSaver.saveDataUrl(this, msg.content, msg.fileName, msg.mime)
                    else null
                } else null
                val name = msg.fileName.ifBlank { "文件" }
                notifyMsg("📎 $name",
                    saved?.let { "已保存到下载目录：LanShare/$it" } ?: "收到文件 $name（长按可下载）")
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
        if (intent?.action == ACTION_SHARE) handleShare(intent)
        return START_STICKY      // 被系统杀掉后自动重启
    }

    // ==================== 后台分享发送 ====================
    /**
     * 系统分享过来的内容在这里直接发出，全程无界面：
     * 不弹窗、不阻塞用户，结果只用一条 Toast 轻提示。
     * 消息带 viaShare=true，接收端会自动落盘。
     */
    private fun handleShare(intent: Intent) {
        val uris: List<Uri> = try {
            IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "读取分享内容失败: ${e.message}")
            emptyList()
        }
        if (uris.isEmpty()) return

        // 用普通线程而非协程：服务没有 lifecycleScope，且发送是一次性的短任务
        Thread {
            var waited = 0
            while (!ws.isReady() && waited < 10_000) {
                Thread.sleep(200); waited += 200
            }
            if (!ws.isReady()) {
                toast("❌ 未连接中继，分享失败")
                return@Thread
            }

            var ok = 0
            var fail = 0
            for (u in uris) {
                val mime = contentResolver.getType(u) ?: "application/octet-stream"
                val isImage = mime.startsWith("image/")
                val dataUrl = if (isImage) ImageUtils.uriToDataUrl(this, u)
                              else readAllAsDataUrl(u, mime)
                val sent = if (dataUrl == null) false else {
                    if (isImage) ws.sendImage(dataUrl, viaShare = true)
                    else ws.sendFile(queryName(u), mime, querySize(u), dataUrl, viaShare = true)
                }
                if (sent) ok++ else fail++
                if (uris.size > 1) Thread.sleep(250)
            }
            toast(if (fail == 0) "✅ 已发送 $ok 项到频道 ${ws.currentChannel()}"
                  else "⚠️ 成功 $ok 项，失败 $fail 项")
        }.start()
    }

    private fun toast(t: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun querySize(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst()) return c.getLong(i)
        }
        return 0L
    }

    /** 任意格式文件读成 data URL（原样，不压缩） */
    private fun readAllAsDataUrl(uri: Uri, mime: String): String? {
        return try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "读取文件失败: ${e.message}")
            null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        notiHandler.removeCallbacks(notiFlush)
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

    /** 累积待提示的内容：短时间内的多条合并成一条通知，避免连续收图时刷屏 */
    private val notiBuf = mutableListOf<Pair<String, String>>()
    private val notiHandler = Handler(Looper.getMainLooper())
    private val notiFlush = Runnable { flushNoti() }

    private fun notifyMsg(title: String, body: String) {
        synchronized(notiBuf) { notiBuf.add(title to body) }
        notiHandler.removeCallbacks(notiFlush)
        notiHandler.postDelayed(notiFlush, NOTI_AGGREGATE_MS)
    }

    /**
     * 把累积的多条合并为一条通知：同 ID 覆盖更新，
     * 因此无论来多少条，通知栏始终只有一条。
     */
    private fun flushNoti() {
        val items = synchronized(notiBuf) { notiBuf.toList().also { notiBuf.clear() } }
        if (items.isEmpty()) return
        val n = items.size
        val last = items.last()

        val style = NotificationCompat.InboxStyle()
        items.takeLast(NOTI_MAX_LINES).forEach { (t, b) ->
            style.addLine(if (b.startsWith(t)) b else "$t：$b")
        }
        if (n > NOTI_MAX_LINES) style.setSummaryText("共 $n 条新消息")

        val nb = NotificationCompat.Builder(this, CH_MSG)
            .setContentTitle(if (n > 1) "$n 条新消息" else last.first)
            .setContentText(last.second)
            .setStyle(style)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent())
            .setColor(0xFF7AA2F7.toInt())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTI_MSG, nb)
    }

    // ==================== 图片存相册 ====================
    /** 复用 GallerySaver：带毫秒命名 + Android 9 以下扫描媒体库 */
    private fun saveImageToGallery(dataUrl: String): String? {
        return GallerySaver.saveDataUrl(this, dataUrl)
    }
}
