package com.example.lanshare

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 全局唯一的 WebSocket 管理器（由 Application 持有）
 * 支持多个消费者（界面 / 前台服务）同时监听消息，避免各处重复建连
 */
/**
 * 收到的消息（含发送者与加密状态）
 * @param type       text / image / locked（locked = 加密但本机无法解密）
 * @param content    文本内容或图片 data URL
 * @param senderName 发送者设备名（用于同频道区分来源）
 * @param encrypted  该消息在传输中是否被加密
 */
data class Incoming(
    val type: String,
    val content: String,
    val senderName: String = "",
    val encrypted: Boolean = false
)

/** 消息监听器 */
typealias MsgListener = (msg: Incoming) -> Unit

class WebSocketManager {

    companion object {
        private const val TAG = "WsManager"
        private const val MAX_RETRY = 8
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)      // 心跳，减少切后台被断
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<MsgListener>()
    private val sentListeners = CopyOnWriteArrayList<MsgListener>()
    private val stateListeners = CopyOnWriteArrayList<(Boolean, String) -> Unit>()
    private val errorListeners = CopyOnWriteArrayList<(String, String) -> Unit>()

    private var ws: WebSocket? = null
    private var url = ""
    private var channel = "0000"
    private var retryCount = 0
    private var userClosed = false

    @Volatile
    private var ready = false

    private val pending = ArrayList<String>()

    /** 频道口令（本地保存，发给中继校验） */
    private val passwords = HashMap<String, String>()

    /** 发送者信息，由 Application 注入（避免此处直接依赖 Context） */
    var sender: Sender? = null

    // ==================== 监听器注册 ====================
    fun addListener(l: MsgListener) {
        if (!listeners.contains(l)) listeners.add(l)
    }
    fun removeListener(l: MsgListener) { listeners.remove(l) }

    /** 本地“已发出”事件：界面用它渲染自己发的消息（me=true） */
    fun addSentListener(l: MsgListener) {
        if (!sentListeners.contains(l)) sentListeners.add(l)
    }
    fun removeSentListener(l: MsgListener) { sentListeners.remove(l) }

    /** 错误监听：参数 (code, message)，如 auth_failed / rate_limit / too_large */
    fun addErrorListener(l: (String, String) -> Unit) {
        if (!errorListeners.contains(l)) errorListeners.add(l)
    }
    fun removeErrorListener(l: (String, String) -> Unit) { errorListeners.remove(l) }

    fun addStateListener(l: (Boolean, String) -> Unit) {
        if (!stateListeners.contains(l)) stateListeners.add(l)
    }
    fun removeStateListener(l: (Boolean, String) -> Unit) { stateListeners.remove(l) }

    // ==================== 连接 ====================
    fun connect(url: String, channel: String) {
        this.url = url
        this.channel = channel
        this.userClosed = false
        this.retryCount = 0
        doConnect()
    }

    private fun doConnect() {
        if (url.isBlank()) return
        ready = false
        notifyState(false, "● 连接中…")
        val req = Request.Builder().url(url).build()

        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "连接成功")
                retryCount = 0
                webSocket.send(JSONObject().apply {
                    put("action", "join")
                    put("data", channel)
                    put("password", passwords[channel] ?: "")
                }.toString())
                ready = true
                flushPending()
                notifyState(true, "● 已连接（$channel）")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val act = json.optString("action")
                    if (act == "joined") return
                    if (act == "error") {
                        val code = json.optString("code")
                        val message = json.optString("message", "服务端错误")
                        mainHandler.post {
                            errorListeners.forEach { runCatching { it(code, message) } }
                        }
                        return
                    }
                    val chan = json.optString("channel", "")
                    if (chan != channel) return           // 只处理当前频道

                    // 发送者信息（旧客户端发来的消息可能没有 sender 字段）
                    val senderObj = json.optJSONObject("sender")
                    val senderName = senderObj?.optString("name", "") ?: ""
                    val wasEncrypted = json.optString("type", "text") == "encrypted"

                    var type = json.optString("type", "text")
                    var content = if (type == "image")
                        json.optString("data", "") else json.optString("text", "")

                    // ---- 端到端解密 ----
                    if (type == "encrypted") {
                        val pwd = passwords[chan]
                        val nonce = json.optString("nonce", "")
                        val cipher = json.optString("data", "")
                        val plain = if (pwd.isNullOrEmpty() || nonce.isEmpty()) null
                                    else CryptoHelper.decrypt(chan, pwd, nonce, cipher)
                        if (plain != null) {
                            val inner = JSONObject(plain)
                            type = inner.optString("type", "text")
                            content = if (type == "image")
                                inner.optString("data", "") else inner.optString("text", "")
                        } else {
                            // 未配置口令或口令不匹配：标记不可读，不猜测内容
                            type = "locked"
                            content = ""
                        }
                    }

                    if (content.isNotEmpty() || type == "locked") {
                        val incoming = Incoming(type, content, senderName, wasEncrypted)
                        mainHandler.post {
                            listeners.forEach { runCatching { it(incoming) } }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "消息解析失败: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = when {
                    t.message?.contains("Software caused connection abort") == true ->
                        "连接被系统中断，重连中…"
                    t.message?.contains("Failed to connect") == true ->
                        "无法连接中继，检查 IP 与防火墙"
                    else -> t.message ?: "连接失败"
                }
                Log.w(TAG, "连接失败: $msg")
                ready = false
                if (!userClosed) {
                    scheduleReconnect()
                    notifyState(false, "● $msg")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                if (!userClosed) scheduleReconnect()
            }
        })
    }

    /** 指数退避重连 */
    private fun scheduleReconnect() {
        if (userClosed) return
        if (retryCount >= MAX_RETRY) {
            notifyState(false, "● 重连失败，请检查中继")
            return
        }
        val delay = (1000L * (1 shl retryCount)).coerceAtMost(15_000L)
        retryCount++
        Log.d(TAG, "${delay}ms 后第 $retryCount 次重连")
        mainHandler.postDelayed({ doConnect() }, delay)
    }

    // ==================== 发送 ====================
    /**
     * 构造待发送消息
     * 该频道已配置口令 => 内层内容用 AES-GCM 加密，外层为 {type:"encrypted", nonce, data}
     * 未配置口令 => 明文（向后兼容浏览器/旧客户端的明文频道）
     */
    private fun buildPayload(type: String, content: String): String? {
        val json = JSONObject().apply { put("channel", channel) }
        val pwd = passwords[channel]

        if (!pwd.isNullOrEmpty()) {
            val inner = JSONObject().apply {
                put("type", type)
                if (type == "image") put("data", content) else put("text", content)
            }.toString()
            val sealed = CryptoHelper.encrypt(channel, pwd, inner) ?: return null
            json.put("type", "encrypted")
            json.put("nonce", sealed.first)
            json.put("data", sealed.second)
        } else {
            json.put("type", type)
            if (type == "image") json.put("data", content) else json.put("text", content)
        }

        sender?.let { s ->
            json.put("sender", JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
            })
        }
        return json.toString()
    }

    fun sendImage(dataUrl: String): Boolean {
        val msg = buildPayload("image", dataUrl) ?: return false
        val ok = sendRaw(msg)
        if (ok) dispatchSent("image", dataUrl)
        return ok
    }

    fun sendText(text: String): Boolean {
        val msg = buildPayload("text", text) ?: return false
        val ok = sendRaw(msg)
        if (ok) dispatchSent("text", text)
        return ok
    }

    /** 把“自己发出去”的消息告知界面，便于本地立刻渲染 */
    private fun dispatchSent(type: String, content: String) {
        val incoming = Incoming(
            type = type,
            content = content,
            senderName = sender?.name ?: "",
            encrypted = !passwords[channel].isNullOrEmpty()
        )
        mainHandler.post {
            sentListeners.forEach { runCatching { it(incoming) } }
        }
    }

    private fun sendRaw(msg: String): Boolean {
        return if (ready && ws != null) {
            ws!!.send(msg)
        } else {
            synchronized(pending) { pending.add(msg) }
            if (!userClosed) scheduleReconnect()
            false
        }
    }

    private fun flushPending() {
        val list = synchronized(pending) { ArrayList(pending).also { pending.clear() } }
        list.forEach { ws?.send(it) }
        if (list.isNotEmpty()) Log.d(TAG, "补发 ${list.size} 条")
    }

    // ==================== 状态 ====================
    fun isReady() = ready
    fun currentChannel() = channel

    /** 设置某频道的口令（切换/重连后自动带上） */
    fun setPassword(ch: String, pwd: String) {
        // 口令变化 => 该频道已派生的加解密密钥失效，必须清缓存
        CryptoHelper.clearCache(ch)
        passwords[ch] = pwd
        // 若在频道内且口令变化，重新加入以生效
        if (ready && ch == channel) {
            ws?.send(JSONObject().apply {
                put("action", "join"); put("data", ch); put("password", pwd)
            }.toString())
        }
    }

    fun setChannel(ch: String) {
        if (ch == channel) return
        channel = ch
        if (ready) {
            ws?.send(JSONObject().apply {
                put("action", "join"); put("data", ch)
                put("password", passwords[ch] ?: "")
            }.toString())
        }
    }

    /** 从后台返回 / 服务保活时确保连接 */
    fun ensureConnected() {
        if (!ready && !userClosed) { retryCount = 0; doConnect() }
    }

    private fun notifyState(connected: Boolean, text: String) {
        mainHandler.post { stateListeners.forEach { runCatching { it(connected, text) } } }
    }

    fun close() {
        userClosed = true
        ready = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { ws?.close(1000, "bye") }
        ws = null
        synchronized(pending) { pending.clear() }
    }
}
