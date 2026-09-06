package com.example.lanshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.lanshare.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天主界面
 * 不自建连接，而是共享 Application 中的全局单例 WebSocketManager，
 * 并通过前台服务（RelayService）保持后台存活
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var ws: WebSocketManager
    private lateinit var adapter: MessageAdapter

    private var currentChannel = "0000"
    /** 各频道的消息缓存，切换频道时保留 */
    private val cache = HashMap<String, MutableList<Message>>()

    // ==================== 直接发送（选中即发，无中间确认）====================
    // 设计取舍：原先是「选入队列 → 调序 → 点依次发送」三步。
    // 实测中多选项的排序需求很弱，而多一次确认反而拖慢了"传个文件"这种高频动作。
    // 因此改为选完立刻按选择顺序发送，界面不再出现队列面板与「依次发送」按钮。

    @Volatile
    private var sending = false

    /** 单条软上限：中继默认 32MB，base64 会膨胀约 33%，故源文件控制在 20MB 内较稳妥 */
    private val softLimit = 20L * 1024 * 1024

    /** 多选图片：选完即发 */
    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (!uris.isNullOrEmpty()) sendPicked(uris, true)
        }

    /** 多选文件（任意格式）：选完即发 */
    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (!uris.isNullOrEmpty()) sendPicked(uris, false)
        }

    // 收到他人消息
    private val msgListener: MsgListener = { msg ->
        appendMessage(toMessage(msg, msg.me))
    }

    // 自己发出的消息（本地回显）
    private val sentListener: MsgListener = { msg ->
        appendMessage(toMessage(msg, true))
    }

    /** 统一把网络层消息转为界面消息模型（含文件字段） */
    private fun toMessage(msg: Incoming, me: Boolean) = Message(
        content = msg.content,
        isImage = (msg.type == "image"),
        isFile = (msg.type == "file"),
        isMe = me,
        time = nowTime(),
        senderName = msg.senderName,
        encrypted = msg.encrypted,
        locked = (msg.type == "locked"),
        fileName = msg.fileName,
        mime = msg.mime,
        size = msg.size,
        batchId = msg.batchId,
        batchIndex = msg.batchIndex,
        batchTotal = msg.batchTotal
    )

    private val stateListener: (Boolean, String) -> Unit = { connected, text ->
        b.tvStatus.text = text
        b.tvStatus.setTextColor(
            ContextCompat.getColor(this,
                if (connected) R.color.accent else R.color.warn)
        )
    }

    /**
     * 历史回补：整体替换该频道的列表
     * （中继在加入频道时补发最近消息；此处必须替换而非追加，否则与实时消息叠加会重复）
     */
    private val historyListener: (String, List<Incoming>) -> Unit = { ch, list ->
        runOnUiThread {
            val target = cache.getOrPut(ch) { mutableListOf() }
            target.clear()
            list.forEach { msg ->
                target.add(Message(
                    content = msg.content,
                    isImage = (msg.type == "image"),
                    isFile = (msg.type == "file"),
                    isMe = msg.me,
                    time = nowTime(),
                    senderName = msg.senderName,
                    encrypted = msg.encrypted,
                    locked = (msg.type == "locked"),
                    fileName = msg.fileName,
                    mime = msg.mime,
                    size = msg.size
                ))
            }
            if (ch == currentChannel) {
                adapter.notifyDataSetChanged()
                b.rvMessages.scrollToPosition(target.size - 1)
            }
        }
    }

    /** 服务端错误：口令错误则弹窗重输 */
    private val errorListener: (String, String) -> Unit = { code, message ->
        runOnUiThread {
            if (code == "auth_failed") askPassword(currentChannel)
            else toast("中继：$message")
        }
    }

    /** 构建适配器（绑定点击/长按行为） */
    private fun makeAdapter(): MessageAdapter =
        MessageAdapter(
            getList(currentChannel),
            onImageClick = { msg ->                       // 点图片 -> 全屏
                openFullscreen(msg)
            },
            onImageLongClick = { msg ->                   // 长按图片 -> 保存
                AlertDialog.Builder(this)
                    .setTitle("图片")
                    .setItems(arrayOf("保存到相册", "全屏查看")) { _, which ->
                        when (which) {
                            0 -> doSave(msg)
                            1 -> openFullscreen(msg)
                        }
                    }.show()
            },
            onTextLongClick = { msg ->                    // 长按文字 -> 复制
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", msg.content))
                toast("已复制")
            },
            onFileClick = { msg ->                        // 点/长按文件 -> 保存到下载目录
                saveFileMessage(msg)
            }
        )

    /**
     * 全屏查看图片。
     * 优先用已缓存的大图；批次里的图片只缓存了缩略图，此处按原图重新解码，
     * 因此点开后仍是清晰大图，而不是模糊的缩略图放大版。
     */
    private fun openFullscreen(msg: Message) {
        val bmp = msg.bitmap ?: ImageUtils.decodeForDisplay(msg.content, 2048)
            ?.also { msg.bitmap = it }
        if (bmp == null) { toast("图片解码失败"); return }
        ImageHolder.pending = bmp
        startActivity(Intent(this, ImageViewerActivity::class.java))
    }

    /** 保存收到的文件到公共下载目录（LanShare 子目录），并提示路径 */
    private fun saveFileMessage(msg: Message) {
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = FileSaver.saveDataUrl(
                this@MainActivity, msg.content, msg.fileName, msg.mime
            )
            withContext(Dispatchers.Main) {
                toast(if (saved != null) "已保存到下载目录：LanShare/$saved" else "保存失败")
            }
        }
    }

    private fun doSave(msg: Message) {
        lifecycleScope.launch(Dispatchers.IO) {
            val name = GallerySaver.saveDataUrl(this@MainActivity, msg.content)
            withContext(Dispatchers.Main) {
                toast(if (name != null) "已保存到相册：LanShare/$name" else "保存失败")
            }
        }
    }

    /** 口令输入并保存 */
    private fun askPassword(ch: String) {
        val et = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "频道口令"
        }
        AlertDialog.Builder(this).setTitle("频道 $ch 需要口令").setView(et)
            .setPositiveButton("确定") { _, _ ->
                val pwd = et.text.toString()
                PrefsManager.setPassword(this, ch, pwd)
                ws.setPassword(ch, pwd)
            }
            .setNegativeButton("取消", null).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ws = (application as LanShareApp).wsManager
        currentChannel = PrefsManager.getChannel(this)

        setupRecycler()
        setupClick()

        ws.addListener(msgListener)
        ws.addSentListener(sentListener)
        ws.addStateListener(stateListener)
        ws.addHistoryListener(historyListener)
        ws.addErrorListener(errorListener)

        startRelayService()
        askNotificationPermission()
        bindChannel(currentChannel)
    }

    private fun setupRecycler() {
        adapter = makeAdapter()
        b.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.rvMessages.adapter = adapter
        b.tvChannel.text = "📡 频道 $currentChannel"
    }

    private fun setupClick() {
        b.btnSend.setOnClickListener { sendText() }
        b.btnImage.setOnClickListener { pickImages.launch("image/*") }
        b.btnFile.setOnClickListener { pickFiles.launch("*/*") }
        b.btnConfig.setOnClickListener { showConfigDialog() }
        b.btnChannels.setOnClickListener { showChannelDialog() }
        b.tvChannel.setOnClickListener { showChannelDialog() }
        b.btnDiscover.setOnClickListener { discoverChannels() }
    }

    private fun getList(ch: String) = cache.getOrPut(ch) { mutableListOf() }

    /** 绑定频道：已连接则切换，未连接则建立连接 */
    private fun bindChannel(ch: String) {
        currentChannel = ch
        b.tvChannel.text = "📡 频道 $ch"
        adapter = makeAdapter()
        b.rvMessages.adapter = adapter
        adapter.notifyDataSetChanged()

        val url = PrefsManager.getUrl(this)
        ws.setPassword(ch, PrefsManager.getPassword(this, ch))   // 带上口令
        if (ws.isActive()) {
            ws.setChannel(ch)          // 已有连接：只切换频道，不重建
        } else {
            ws.connect(url, ch)
        }
    }

    // ==================== 前台服务与权限 ====================
    private fun startRelayService() {
        val i = Intent(this, RelayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        } catch (e: Exception) {
            Toast.makeText(this, "后台服务启动失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val reqPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                reqPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ==================== 发送 ====================
    private fun sendText() {
        val text = b.etInput.text.toString().trim()
        if (text.isEmpty()) return
        if (!ws.sendText(text)) toast("未连接，消息将自动重试")
        b.etInput.setText("")
    }

    /**
     * 选中即发：把刚选中的一组图片/文件按顺序发出去。
     *
     * 同一批共用一个 batchId，接收端据此把它们渲染成一个可折叠组件。
     * 仍然按「多条独立消息」而非「单条巨消息」发送，好处是：
     * 单条超限失败不影响其余项，也不会触犯中继的单条上限。
     *
     * @param uris     系统选择器返回的 URI 列表（保持用户选择顺序）
     * @param allImage true 表示这批是图片（走压缩通道），false 表示任意文件
     */
    private fun sendPicked(uris: List<Uri>, allImage: Boolean) {
        if (sending) { toast("还有一批正在发送，请稍候"); return }

        // 先做大小校验，超限的直接跳过并提示，不占用后续读取
        val picked = ArrayList<Pair<Uri, String>>()
        var skipped = 0
        for (u in uris) {
            val name = queryName(u)
            if (querySize(u) > softLimit) { skipped++; continue }
            picked.add(u to name)
        }
        if (picked.isEmpty()) {
            toast(if (skipped > 0) "选中的文件超过 ${softLimit / 1024 / 1024}MB，已全部跳过" else "未选择文件")
            return
        }

        sending = true
        val total = picked.size
        val batchId = "b${System.currentTimeMillis()}_${(100000..999999).random()}"

        lifecycleScope.launch(Dispatchers.IO) {
            picked.forEachIndexed { idx, (uri, name) ->
                // 图片一律原图直传（不再压缩），文件原样读取
                val dataUrl = if (allImage)
                    ImageUtils.uriToDataUrl(this@MainActivity, uri)
                else
                    readFileAsDataUrl(uri)

                withContext(Dispatchers.Main) {
                    if (dataUrl == null) {
                        toast("「$name」读取失败，已跳过")
                    } else {
                        val batch = BatchMeta(batchId, idx, total)
                        val ok = if (allImage) {
                            ws.sendImage(dataUrl, batch)
                        } else {
                            ws.sendFile(name, guessMime(name), querySize(uri), dataUrl, batch)
                        }
                        if (!ok) toast("未连接，将自动重试")
                    }
                }
                // 中继限速 100 条/60s，逐条留出余量
                if (idx < total - 1) kotlinx.coroutines.delay(350)
            }
            withContext(Dispatchers.Main) {
                sending = false
                if (skipped > 0) toast("已发送 $total 项，跳过 $skipped 个超限文件")
            }
        }
    }

    /** 任意文件读取为 data URL（不做任何转码，保留原始字节） */
    private fun readFileAsDataUrl(uri: Uri): String? {
        return try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            if (bytes.isEmpty()) return null
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:$mime;base64,$b64"
        } catch (e: Exception) {
            null
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "")
        if (ext.isBlank()) return "application/octet-stream"
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext.lowercase()) ?: "application/octet-stream"
    }

    private fun fmtSize(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format("%.1f KB", n / 1024.0)
        n < 1024L * 1024 * 1024 -> String.format("%.1f MB", n / 1024.0 / 1024.0)
        else -> String.format("%.2f GB", n / 1024.0 / 1024.0 / 1024.0)
    }

    /** 从 ContentResolver 读取文件名 */
    private fun queryName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            } ?: uri.lastPathSegment ?: "file"
        } catch (e: Exception) {
            uri.lastPathSegment ?: "file"
        }
    }

    /** 从 ContentResolver 读取文件大小 */
    private fun querySize(uri: Uri): Long {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst()) c.getLong(i) else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun appendMessage(msg: Message) {
        runOnUiThread {
            val list = getList(currentChannel)
            list.add(msg)
            adapter.notifyItemInserted(list.size - 1)
            b.rvMessages.smoothScrollToPosition(list.size - 1)
        }
    }

    // ==================== 对话框 ====================
    /** 向中继查询可发现频道；🔒 标记需要口令 */
    private fun discoverChannels() {
        val base = PrefsManager.getUrl(this).replace("ws://", "http://")
            .replace("wss://", "https://").trimEnd('/')
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient()
                val req = okhttp3.Request.Builder().url("$base/channels").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                    val body = resp.body?.string() ?: throw Exception("空响应")
                    val jo = org.json.JSONObject(body)
                    val list = jo.getJSONArray("channels")
                    val protArr = jo.optJSONArray("protected")
                    val prot = mutableSetOf<String>()
                    if (protArr != null)
                        for (i in 0 until protArr.length()) prot.add(protArr.getString(i))
                    val names = Array(list.length()) { i ->
                        val c = list.getString(i)
                        if (prot.contains(c)) "🔒 $c" else c
                    }
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("发现频道")
                            .setItems(names) { _, which ->
                                val raw = list.getString(which)
                                if (prot.contains(raw)) askPassword(raw)
                                PrefsManager.setChannel(this@MainActivity, raw)
                                bindChannel(raw)
                            }.show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast("发现失败：${e.message}（确认中继已运行）")
                }
            }
        }
    }

    private fun showConfigDialog() {
        val etUrl = EditText(this).apply {
            setText(PrefsManager.getUrl(this@MainActivity)); setSingleLine()
        }
        val etChan = EditText(this).apply {
            setText(currentChannel); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine()
        }
        val etDev = EditText(this).apply {
            setText(DeviceIdentity.name(this@MainActivity)); setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(60, 20, 60, 0)
            addView(label("中继地址")); addView(etUrl)
            addView(label("聊天室（4~8 位数字）")); addView(etChan)
            addView(label("设备名（同频道内区分发送者）")); addView(etDev)
        }
        AlertDialog.Builder(this).setTitle("配置").setView(box)
            .setPositiveButton("保存并连接") { _, _ ->
                val url = etUrl.text.toString().trim()
                val ch = etChan.text.toString().trim()
                if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                    toast("地址需以 ws:// 开头"); return@setPositiveButton
                }
                if (!Regex("^\\d{4,8}$").matches(ch)) {
                    toast("聊天室需为 4~8 位数字"); return@setPositiveButton
                }
                PrefsManager.setUrl(this, url)
                PrefsManager.setChannel(this, ch)

                // 设备名：留空则恢复默认机型名
                val dev = etDev.text.toString().trim()
                (application as LanShareApp).updateDeviceName(
                    dev.ifBlank { DeviceIdentity.defaultName() }
                )

                ws.connect(url, ch)          // 单例重连到新地址
                bindChannel(ch)
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showChannelDialog() {
        val et = EditText(this).apply {
            setText(currentChannel); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(60, 20, 60, 0)
            addView(label("切换到聊天室")); addView(et)
        }
        AlertDialog.Builder(this).setTitle("聊天室").setView(box)
            .setPositiveButton("切换") { _, _ ->
                val ch = et.text.toString().trim()
                if (!Regex("^\\d{4,8}$").matches(ch)) {
                    toast("需为 4~8 位数字"); return@setPositiveButton
                }
                PrefsManager.setChannel(this, ch)
                bindChannel(ch)
            }
            .setNegativeButton("取消", null).show()
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFFCDD6F4.toInt()); textSize = 13f
        setPadding(0, 14, 0, 4)
    }

    private fun nowTime() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        ws.ensureConnected()
    }

    override fun onDestroy() {
        ws.removeListener(msgListener)
        ws.removeSentListener(sentListener)
        ws.removeStateListener(stateListener)
        ws.removeHistoryListener(historyListener)
        ws.removeErrorListener(errorListener)
        super.onDestroy()
    }
}
