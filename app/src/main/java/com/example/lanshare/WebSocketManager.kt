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
    val encrypted: Boolean = false,
    /** 是否为本机发出（历史回补时用 sender.id 判定） */
    val me: Boolean = false,
    /** 文件消息专用：原始文件名（如 报告.pdf） */
    val fileName: String = "",
    /** 文件消息专用：MIME 类型（如 application/pdf） */
    val mime: String = "",
    /** 文件消息专用：原始字节大小（压缩前的图片或文件本体大小） */
    val size: Long = 0L,
    /** 批次 ID：同批发送的多个文件共用，界面据此聚合为可折叠组件 */
    val batchId: String = "",
    /** 本条在批次中的序号（从 0 开始） */
    val batchIndex: Int = 0,
    /** 该批次总条数 */
    val batchTotal: Int = 0
)

/** 消息监听器 */
typealias MsgListener = (msg: Incoming) -> Unit

/**
 * 文件元数据：随消息一同传输，供接收端还原文件名、类型与大小。
 * 单独抽出而非散落成多个参数，便于明文/加密两条路径复用同一结构。
 */
data class FileMeta(
    val name: String,
    val mime: String,
    val size: Long
)

/**
 * 批次元数据：一次选择多个图片/文件发送时，这些消息共用同一个 batchId，
 * 接收端据此聚合为一个可折叠组件。
 * 采用「多条独立消息 + 共用批次号」而非单条巨消息，好处是：
 * 单条失败不影响整批、避免触犯中继单条上限。
 */
data class BatchMeta(
    val batchId: String,
    val batchIndex: Int,
    val batchTotal: Int
)

class WebSocketManager {

    companion object {
        private const val TAG = "WsManager"
        private const val MAX_RETRY = 8
        /** 去重缓存上限：超出后按先进先出淘汰，防止长时间运行内存无限增长 */
        private const val SEEN_CAP = 512
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
    private val historyListeners = CopyOnWriteArrayList<(String, List<Incoming>) -> Unit>()
    private val errorListeners = CopyOnWriteArrayList<(String, String) -> Unit>()

    private var ws: WebSocket? = null
    private var url = ""
    private var channel = "0000"
    private var retryCount = 0
    private var userClosed = false

    @Volatile
    private var ready = false

    /** 是否正在建连（防止 ensureConnected/connect 被多处并发调用而建出多条连接） */
    @Volatile
    private var connecting = false

    /**
     * 连接纪元（generation）。
     * 每次 doConnect 递增；旧 socket 被 close 后其 listener 仍可能回调 onFailure/onClosed，
     * 若不加以区分，旧回调会把新连接的 ready/connecting 清掉并广播「失败」，
     * 紧接着新连接 onOpen 又广播「已连接」——造成状态在两者间反复横跳。
     * 回调首行比对纪元，过期回调直接丢弃。
     */
    private val connGen = java.util.concurrent.atomic.AtomicInteger(0)

    /** 是否已有待执行的重连（避免多处 onFailure 各排一个定时器，叠加成多条连接） */
    @Volatile
    private var reconnectScheduled = false

    /**
     * 已处理过的消息ID（服务端分配）。
     * 防御性去重：即便曾出现多连接或重连补发，也不会重复渲染同一条消息。
     */
    private val seenIds = java.util.Collections.newSetFromMap(
        java.util.Collections.synchronizedMap(java.util.LinkedHashMap<String, Boolean>(256))
    )
    private var seenOrder = java.util.ArrayDeque<String>()

    private val pending = ArrayList<String>()

    /** 登记消息ID，返回 true 表示已见过（应丢弃） */
    private fun markSeen(id: String?): Boolean {
        if (id.isNullOrEmpty()) return false          // 旧中继无ID，退化为不去重
        synchronized(seenIds) {
            if (!seenIds.add(id)) return true
            seenOrder.addLast(id)
            if (seenOrder.size > SEEN_CAP) {
                val old = seenOrder.removeFirst()
                seenIds.remove(old)
            }
        }
        return false
    }

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

    /**
     * 历史监听：参数 (channel, List<Incoming>)。
     * 中继在加入频道时补发最近消息；历史里的消息需要按 sender.id 判定是否自己发的。
     */
    fun addHistoryListener(l: (String, List<Incoming>) -> Unit) {
        if (!historyListeners.contains(l)) historyListeners.add(l)
    }
    fun removeHistoryListener(l: (String, List<Incoming>) -> Unit) { historyListeners.remove(l) }

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
        val same = (url == this.url && channel == this.channel)
        // 目标未变时，只要「已连接 / 正在连接 / 已排重连」任一成立就复用。
        // 此前仅判断 ready||connecting，于是在「失败后退避等待」期间，
        // 外部（Activity 恢复、前台服务重启）一调用即落入下方分支：
        // 重置 retryCount 并立刻 doConnect()，既把退避打回 1 秒，
        // 又主动 close 掉尚在途的连接——表现为状态在「已连接」与「失败」间反复横跳。
        if (same && (ready || connecting || reconnectScheduled)) {
            Log.d(TAG, "目标未变且已有连接/重连在途，保持现有节奏（不重置退避）")
            return
        }
        this.url = url
        this.channel = channel
        this.userClosed = false
        // 仅当切换了地址或频道才重置退避；否则保留退避进度，避免密集重试
        if (!same) this.retryCount = 0
        doConnect()
    }

    private fun doConnect() {
        if (url.isBlank()) return

        // ---- 单飞：已有连接正在建立则直接复用，避免建出第二条 ----
        if (connecting) {
            Log.d(TAG, "已有连接正在建立，跳过重复 doConnect")
            return
        }
        connecting = true

        // ---- 关键：必须关闭旧连接再重建 ----
        // 此前直接覆盖 ws 字段而不关闭，导致多个 socket 同时存活且都加入了同一频道，
        // 中继给每个 socket 各广播一次 => 界面里同一条消息重复出现多次
        val myGen = connGen.incrementAndGet()   // 开启新纪元，令旧连接的回调全部失效
        runCatching { ws?.close(1000, "reconnect") }
        ws = null
        ready = false
        notifyState(false, "● 连接中…")

        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myGen != connGen.get()) {   // 过期连接：直接关掉，不干扰当前连接
                    Log.d(TAG, "忽略过期连接的 onOpen")
                    runCatching { webSocket.close(1000, "stale") }
                    return
                }
                Log.d(TAG, "连接成功")
                connecting = false
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
                if (myGen != connGen.get()) return       // 过期连接，丢弃
                try {
                    val json = JSONObject(text)
                    val act = json.optString("action")
                    if (act == "joined") return

                    // 服务端补发的历史（含加密条目）
                    if (act == "history") {
                        val ch = json.optString("channel", channel)
                        val arr = json.optJSONArray("messages") ?: return
                        val list = ArrayList<Incoming>()
                        for (i in 0 until arr.length()) {
                            parseHistoryEntry(ch, arr.optJSONObject(i) ?: continue)?.let { list.add(it) }
                        }
                        if (list.isNotEmpty()) {
                            mainHandler.post {
                                historyListeners.forEach { runCatching { it(ch, list) } }
                            }
                        }
                        return
                    }

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

                    // 服务端分配的稳定ID：重复投递直接丢弃（多连接/重连补发防御）
                    val msgId = json.optString("id", "")
                    if (markSeen(msgId)) {
                        Log.d(TAG, "丢弃重复消息 id=$msgId")
                        return
                    }

                    // 发送者信息（旧客户端发来的消息可能没有 sender 字段）
                    val senderObj = json.optJSONObject("sender")
                    val senderName = senderObj?.optString("name", "") ?: ""
                    val wasEncrypted = json.optString("type", "text") == "encrypted"

                    var type = json.optString("type", "text")
                    var content = if (type == "image" || type == "file")
                        json.optString("data", "") else json.optString("text", "")
                    // 文件元数据（明文频道直接带在消息上）
                    var fileName = if (type == "file") json.optString("fileName", "") else ""
                    var mime = if (type == "file") json.optString("mime", "") else ""
                    var fileSize = if (type == "file") json.optLong("size", 0L) else 0L
                    // 批次归属：明文频道直接取外层，加密频道解密后从内层覆盖
                    var batchId = json.optString("batchId", "")
                    var batchIndex = json.optInt("batchIndex", 0)
                    var batchTotal = json.optInt("batchTotal", 0)

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
                            content = if (type == "image" || type == "file")
                                inner.optString("data", "") else inner.optString("text", "")
                            fileName = inner.optString("fileName", "")
                            mime = inner.optString("mime", "")
                            fileSize = inner.optLong("size", 0L)
                            batchId = inner.optString("batchId", "")
                            batchIndex = inner.optInt("batchIndex", 0)
                            batchTotal = inner.optInt("batchTotal", 0)
                        } else {
                            // 未配置口令或口令不匹配：标记不可读，不猜测内容
                            type = "locked"
                            content = ""
                            fileName = ""; mime = ""; fileSize = 0L
                            batchId = ""; batchIndex = 0; batchTotal = 0
                        }
                    }

                    if (content.isNotEmpty() || type == "locked") {
                        val incoming = Incoming(
                            type = type,
                            content = content,
                            senderName = senderName,
                            encrypted = wasEncrypted,
                            fileName = fileName,
                            mime = mime,
                            size = fileSize,
                            batchId = batchId,
                            batchIndex = batchIndex,
                            batchTotal = batchTotal
                        )
                        mainHandler.post {
                            listeners.forEach { runCatching { it(incoming) } }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "消息解析失败: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myGen != connGen.get()) {           // 旧连接的失败不应影响当前连接
                    Log.d(TAG, "忽略过期连接的 onFailure: ${t.message}")
                    return
                }
                connecting = false
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
                if (myGen != connGen.get()) return
                connecting = false
                ready = false
                if (!userClosed) scheduleReconnect()
            }
        })
    }

    /** 解析一条历史记录：解密、判定是否本机发出 */
    private fun parseHistoryEntry(ch: String, e: JSONObject): Incoming? {
        // 去重：历史与实时广播可能重叠（中继补发 + 后续推送）
        if (markSeen(e.optString("id", ""))) return null

        val senderObj = e.optJSONObject("sender")
        val senderName = senderObj?.optString("name", "") ?: ""
        val senderId = senderObj?.optString("id", "") ?: ""
        val wasEncrypted = e.optString("type", "text") == "encrypted"

        var type = e.optString("type", "text")
        // 服务端历史条目文本字段为 content，实时广播为 text —— 两者都兼容，
        // 否则历史文本消息会取到空串并被下方 isEmpty 判断丢弃（此前安卓端看不到历史文本）
        val textField = e.optString("content", "").ifEmpty { e.optString("text", "") }
        var content = if (type == "image" || type == "file")
            e.optString("data", "") else textField
        var fileName = if (type == "file") e.optString("fileName", "") else ""
        var mime = if (type == "file") e.optString("mime", "") else ""
        var fileSize = if (type == "file") e.optLong("size", 0L) else 0L
        var batchId = e.optString("batchId", "")
        var batchIndex = e.optInt("batchIndex", 0)
        var batchTotal = e.optInt("batchTotal", 0)

        if (type == "encrypted") {
            val pwd = passwords[ch]
            val nonce = e.optString("nonce", "")
            val cipher = e.optString("data", "")
            val plain = if (pwd.isNullOrEmpty() || nonce.isEmpty()) null
                        else CryptoHelper.decrypt(ch, pwd, nonce, cipher)
            if (plain != null) {
                val inner = JSONObject(plain)
                type = inner.optString("type", "text")
                content = if (type == "image" || type == "file")
                    inner.optString("data", "") else inner.optString("text", "")
                fileName = inner.optString("fileName", "")
                mime = inner.optString("mime", "")
                fileSize = inner.optLong("size", 0L)
                batchId = inner.optString("batchId", "")
                batchIndex = inner.optInt("batchIndex", 0)
                batchTotal = inner.optInt("batchTotal", 0)
            } else {
                type = "locked"
                content = ""
                fileName = ""; mime = ""; fileSize = 0L
                batchId = ""; batchIndex = 0; batchTotal = 0
            }
        }
        if (content.isEmpty() && type != "locked") return null

        return Incoming(
            type = type,
            content = content,
            senderName = senderName,
            encrypted = wasEncrypted,
            fileName = fileName,
            mime = mime,
            size = fileSize,
            batchId = batchId,
            batchIndex = batchIndex,
            batchTotal = batchTotal,
            // 历史里自己发的消息也要靠右侧显示
            me = senderId.isNotEmpty() && senderId == sender?.id
        )
    }

    /** 指数退避重连（同一时刻只允许一个待执行任务，避免叠加成多条连接） */
    private fun scheduleReconnect() {
        if (userClosed) return
        if (reconnectScheduled) {
            Log.d(TAG, "已有待执行的重连，跳过重复排程")
            return
        }
        if (retryCount >= MAX_RETRY) {
            notifyState(false, "● 重连失败，请检查中继")
            return
        }
        reconnectScheduled = true
        val delay = (1000L * (1 shl retryCount)).coerceAtMost(15_000L)
        retryCount++
        Log.d(TAG, "${delay}ms 后第 $retryCount 次重连")
        mainHandler.postDelayed({
            reconnectScheduled = false
            doConnect()
        }, delay)
    }

    // ==================== 发送 ====================
    /**
     * 构造待发送消息
     * 该频道已配置口令 => 内层内容用 AES-GCM 加密，外层为 {type:"encrypted", nonce, data}
     * 未配置口令 => 明文（向后兼容浏览器/旧客户端的明文频道）
     */
    /**
     * 构造待发送消息
     * 该频道已配置口令 => 内层内容用 AES-GCM 加密，外层为 {type:"encrypted", nonce, data}
     * 未配置口令 => 明文（向后兼容浏览器/旧客户端的明文频道）
     *
     * @param type  text / image / file
     * @param content 文本内容、图片 data URL 或文件 data URL
     * @param meta  文件元数据（type==file 时必填）
     */
    private fun buildPayload(type: String, content: String, meta: FileMeta? = null, batch: BatchMeta? = null): String? {
        val json = JSONObject().apply { put("channel", channel) }
        val pwd = passwords[channel]

        // 内层负载：image/file 走 data 字段，text 走 text 字段
        val inner = JSONObject().apply {
            put("type", type)
            when (type) {
                "image", "file" -> put("data", content)
                else -> put("text", content)
            }
            if (meta != null) {
                put("fileName", meta.name)
                put("mime", meta.mime)
                put("size", meta.size)
            }
            // 批次信息放进内层，保证加密频道解密后仍可还原分组
            if (batch != null) {
                put("batchId", batch.batchId)
                put("batchIndex", batch.batchIndex)
                put("batchTotal", batch.batchTotal)
            }
        }.toString()

        if (!pwd.isNullOrEmpty()) {
            val sealed = CryptoHelper.encrypt(channel, pwd, inner) ?: return null
            json.put("type", "encrypted")
            json.put("nonce", sealed.first)
            json.put("data", sealed.second)
        } else {
            json.put("type", type)
            when (type) {
                "image", "file" -> {
                    json.put("data", content)
                    // 明文频道也要带文件元数据，否则接收端无法还原文件名
                    if (meta != null) {
                        json.put("fileName", meta.name)
                        json.put("mime", meta.mime)
                        json.put("size", meta.size)
                    }
                    if (batch != null) {
                        json.put("batchId", batch.batchId)
                        json.put("batchIndex", batch.batchIndex)
                        json.put("batchTotal", batch.batchTotal)
                    }
                }
                else -> json.put("text", content)
            }
        }

        sender?.let { s ->
            json.put("sender", JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
            })
        }
        return json.toString()
    }

    /**
     * 发送文本消息。
     * 未连接时不丢弃：由 sendRaw 排入 pending，待重连成功后自动补发。
     * @return true 已即时发出；false 已排队（调用方可提示“将自动重试”）
     */
    fun sendText(text: String): Boolean {
        val msg = buildPayload("text", text) ?: return false
        val ok = sendRaw(msg)
        if (ok) dispatchSent("text", text)
        return ok
    }

    fun sendImage(dataUrl: String, batch: BatchMeta? = null): Boolean {
        val msg = buildPayload("image", dataUrl, batch = batch) ?: return false
        val ok = sendRaw(msg)
        if (ok) dispatchSent("image", dataUrl, null, batch)
        return ok
    }

    /**
     * 发送任意格式的文件。
     * @param fileName 原始文件名（含扩展名），用于接收端还原
     * @param mime     MIME 类型
     * @param size     原始字节大小
     * @param dataUrl  data:<mime>;base64,<内容>
     */
    fun sendFile(fileName: String, mime: String, size: Long, dataUrl: String, batch: BatchMeta? = null): Boolean {
        val meta = FileMeta(fileName, mime, size)
        val msg = buildPayload("file", dataUrl, meta, batch) ?: return false
        val ok = sendRaw(msg)
        if (ok) dispatchSent("file", dataUrl, meta, batch)
        return ok
    }

    /** 把“自己发出去”的消息告知界面，便于本地立刻渲染 */
    private fun dispatchSent(type: String, content: String, meta: FileMeta? = null, batch: BatchMeta? = null) {
        val incoming = Incoming(
            type = type,
            content = content,
            senderName = sender?.name ?: "",
            encrypted = !passwords[channel].isNullOrEmpty(),
            fileName = meta?.name ?: "",
            mime = meta?.mime ?: "",
            size = meta?.size ?: 0L,
            batchId = batch?.batchId ?: "",
            batchIndex = batch?.batchIndex ?: 0,
            batchTotal = batch?.batchTotal ?: 0
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
            // 先向中继认领/更新口令，让口令不一致的客户端无法加入该频道
            ws?.send(JSONObject().apply {
                put("action", "setpassword"); put("data", ch); put("password", pwd)
            }.toString())
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

    /**
     * 从后台返回 / 服务保活时确保连接。
     * 注意：不再无条件 doConnect() —— 若已有连接或正在建连则直接返回，
     * 避免在 Activity 与 Service 中各自触发建连而产生多条连接。
     */
    fun ensureConnected() {
        if (userClosed) return
        // 已连接 / 正在连接 / 已有待执行重连 => 都不应再触发建连
        if (ready || connecting || reconnectScheduled) return
        if (url.isBlank()) {
            // 尚未配置地址：由调用方通过 connect(url, channel) 提供
            return
        }
        retryCount = 0
        doConnect()
    }

    /** 该管理器是否已在连接或已连上（供 UI/服务判断是否还需建连） */
    fun isActive() = ready || connecting

    /** 上一次广播出去的状态，用于去重 */
    @Volatile
    private var lastState: Pair<Boolean, String>? = null

    private fun notifyState(connected: Boolean, text: String) {
        // 瞬态状态（如连续的「连接中…」）会被多次触发，
        // 若不去重，UI 会在「已连接 / 连接失败」之间肉眼可见地抖动。
        val cur = connected to text
        if (lastState == cur) return
        lastState = cur
        mainHandler.post { stateListeners.forEach { runCatching { it(connected, text) } } }
    }

    fun close() {
        userClosed = true
        ready = false
        connecting = false
        connGen.incrementAndGet()      // 令在途的旧回调全部失效
        reconnectScheduled = false
        lastState = null               // 关闭后允许重新广播同内容状态
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { ws?.close(1000, "bye") }
        ws = null
        synchronized(pending) { pending.clear() }
    }
}
