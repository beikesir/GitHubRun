package com.example.lanshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
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

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { sendImage(it) }
        }

    // 收到他人消息
    private val msgListener: MsgListener = { msg ->
        appendMessage(Message(
            content = msg.content,
            isImage = (msg.type == "image"),
            isMe = false,
            time = nowTime(),
            senderName = msg.senderName,
            encrypted = msg.encrypted,
            locked = (msg.type == "locked")
        ))
    }

    // 自己发出的消息（本地回显）
    private val sentListener: MsgListener = { msg ->
        appendMessage(Message(
            content = msg.content,
            isImage = (msg.type == "image"),
            isMe = true,
            time = nowTime(),
            senderName = msg.senderName,
            encrypted = msg.encrypted
        ))
    }

    private val stateListener: (Boolean, String) -> Unit = { _, text ->
        b.tvStatus.text = text
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
                msg.bitmap?.let {
                    ImageHolder.pending = it
                    startActivity(Intent(this, ImageViewerActivity::class.java))
                } ?: toast("图片尚未解码完成")
            },
            onImageLongClick = { msg ->                   // 长按图片 -> 保存
                AlertDialog.Builder(this)
                    .setTitle("图片")
                    .setItems(arrayOf("保存到相册", "全屏查看")) { _, which ->
                        when (which) {
                            0 -> doSave(msg)
                            1 -> msg.bitmap?.let {
                                ImageHolder.pending = it
                                startActivity(Intent(this, ImageViewerActivity::class.java))
                            }
                        }
                    }.show()
            },
            onTextLongClick = { msg ->                    // 长按文字 -> 复制
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", msg.content))
                toast("已复制")
            }
        )

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
        b.btnImage.setOnClickListener { pickImage.launch("image/*") }
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
        if (ws.isReady()) {
            ws.setChannel(ch)
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

    private fun sendImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dataUrl = ImageUtils.uriToDataUrl(this@MainActivity, uri)
            withContext(Dispatchers.Main) {
                if (dataUrl == null) { toast("图片处理失败"); return@withContext }
                if (!ws.sendImage(dataUrl)) toast("未连接，将自动重试")
            }
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
        ws.removeErrorListener(errorListener)
        super.onDestroy()
    }
}
