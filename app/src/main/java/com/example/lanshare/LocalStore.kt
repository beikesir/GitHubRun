package com.example.lanshare

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地消息持久化（客户端只依赖自身存储，中继不再回显历史）
 *
 * 设计要点：
 *  - 元数据存 JSON，每个频道一个文件；写入采用「先写临时文件再重命名」的原子替换，
 *    避免进程被杀时留下半个文件导致整个频道的记录损坏。
 *  - 图片 / 文件正文另存私有目录 media/。正文不进 JSON——
 *    一张照片 base64 后可达数 MB，塞进 JSON 会让每次读写都很慢。
 *  - 外置消息（正文在中继上、本地 content 为空）会在保存时补拉到本机，
 *    这样即使中继重启，历史里的图片依然能显示。
 *  - 支持按保留天数自动清理，避免长期占用存储空间。
 */
object LocalStore {

    private const val TAG = "LocalStore"

    /** 默认保留天数 */
    const val DEFAULT_KEEP_DAYS = 7

    /** 保护 JSON 文件读写，避免界面线程与后台服务同时写入 */
    private val LOCK = Any()

    private fun mediaDir(ctx: Context): File =
        File(ctx.filesDir, "media").apply { if (!exists()) mkdirs() }

    private fun fileFor(ctx: Context, ch: String): File =
        File(ctx.filesDir, "msg_$ch.json")

    // ==================== 正文存取 ====================

    /** data URL → 字节；非 data URL 或格式错误返回 null */
    fun decodeDataUrl(dataUrl: String): ByteArray? {
        if (!dataUrl.startsWith("data:")) return null
        val i = dataUrl.indexOf(',')
        if (i < 0) return null
        return try {
            Base64.decode(dataUrl.substring(i + 1), Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    /** 把 data URL 的正文写入 media/，返回文件名；失败返回 null */
    fun writeMedia(ctx: Context, id: Long, dataUrl: String): String? {
        val bytes = decodeDataUrl(dataUrl)
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val f = File(mediaDir(ctx), "$id.bin")
            f.writeBytes(bytes)
            f.name
        } catch (e: Exception) {
            Log.w(TAG, "写本地媒体失败: ${e.message}")
            null
        }
    }

    /** 取本地媒体文件；路径为空或文件已不存在时返回 null */
    fun mediaFile(ctx: Context, localPath: String): File? {
        if (localPath.isBlank()) return null
        val f = File(mediaDir(ctx), localPath)
        return if (f.exists()) f else null
    }

    /**
     * 补拉外置正文：正文不在本地、但中继上仍有直链时下载到 media/。
     * 返回本地文件名，失败返回 null。
     */
    fun fetchMedia(ctx: Context, m: Message): String? {
        val url = m.fileUrl
        if (url.isBlank()) return null
        return try {
            val resp = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
            val bytes = if (resp.isSuccessful) resp.body?.bytes() else null
            resp.close()
            if (bytes == null || bytes.isEmpty()) null
            else {
                val f = File(mediaDir(ctx), "${m.id}.bin")
                f.writeBytes(bytes)
                f.name
            }
        } catch (e: Exception) {
            Log.w(TAG, "补拉外置正文失败: ${e.message}")
            null
        }
    }

    // ==================== 序列化 ====================

    private fun toJson(m: Message) = JSONObject().apply {
        put("id", m.id)
        // 正文不入库：图片/文件已落到 media/，只有文本直接存
        put("content", if (m.isImage || m.isFile) "" else m.content)
        put("isImage", m.isImage)
        put("isMe", m.isMe)
        put("time", m.time)
        put("senderName", m.senderName)
        put("encrypted", m.encrypted)
        put("locked", m.locked)
        put("isFile", m.isFile)
        put("fileName", m.fileName)
        put("mime", m.mime)
        put("size", m.size)
        put("omitted", m.omitted)
        put("source", m.source)
        put("fileId", m.fileId)
        put("fileUrl", m.fileUrl)
        put("thumbUrl", m.thumbUrl)
        put("ts", m.ts)
        put("localPath", m.localPath)
    }

    private fun fromJson(o: JSONObject) = Message(
        id = o.optLong("id", System.currentTimeMillis()),
        content = o.optString("content", ""),
        isImage = o.optBoolean("isImage", false),
        isMe = o.optBoolean("isMe", false),
        time = o.optString("time", ""),
        senderName = o.optString("senderName", ""),
        encrypted = o.optBoolean("encrypted", false),
        locked = o.optBoolean("locked", false),
        isFile = o.optBoolean("isFile", false),
        fileName = o.optString("fileName", ""),
        mime = o.optString("mime", ""),
        size = o.optLong("size", 0L),
        omitted = o.optBoolean("omitted", false),
        source = o.optString("source", ""),
        fileId = o.optString("fileId", ""),
        fileUrl = o.optString("fileUrl", ""),
        thumbUrl = o.optString("thumbUrl", ""),
        ts = o.optLong("ts", System.currentTimeMillis()),
        localPath = o.optString("localPath", "")
    )

    private fun readArr(ctx: Context, ch: String): JSONArray {
        val f = fileFor(ctx, ch)
        if (!f.exists()) return JSONArray()
        return try {
            JSONArray(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "频道 $ch 记录损坏，已重置: ${e.message}")
            JSONArray()
        }
    }

    private fun writeArr(ctx: Context, ch: String, arr: JSONArray) {
        val dst = fileFor(ctx, ch)
        val tmp = File(ctx.filesDir, "msg_$ch.tmp")
        tmp.writeText(arr.toString())
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) {
            // 极少见：重命名失败时退化为直接写，至少不丢数据
            dst.writeText(arr.toString())
            tmp.delete()
        }
    }

    // ==================== 对外接口 ====================

    /**
     * 追加一条消息（须在 IO 线程调用）。
     * 会自动把图片/文件正文落到本机；外置消息则尝试补拉。
     */
    fun append(ctx: Context, ch: String, m: Message) {
        synchronized(LOCK) {
            var rec = m
            if ((m.isImage || m.isFile) && m.localPath.isBlank()) {
                val p = if (m.content.isNotBlank()) writeMedia(ctx, m.id, m.content)
                        else fetchMedia(ctx, m)
                if (!p.isNullOrBlank()) rec = m.copy(localPath = p)
            }
            val arr = readArr(ctx, ch)
            arr.put(toJson(rec))
            writeArr(ctx, ch, arr)
        }
    }

    /** 读取某频道的全部消息（须在 IO 线程调用） */
    fun load(ctx: Context, ch: String): MutableList<Message> {
        val out = mutableListOf<Message>()
        synchronized(LOCK) {
            val arr = readArr(ctx, ch)
            for (i in 0 until arr.length()) {
                try {
                    out.add(fromJson(arr.getJSONObject(i)))
                } catch (e: Exception) { /* 跳过单条损坏记录 */ }
            }
        }
        return out
    }

    /** 清空某频道记录 */
    fun clear(ctx: Context, ch: String) {
        synchronized(LOCK) {
            val f = fileFor(ctx, ch)
            if (f.exists()) f.delete()
        }
    }

    /**
     * 定期清理：删除超过 keepDays 天的消息与媒体文件。
     * 在 IO 线程调用，返回 Pair(清理消息数, 清理媒体文件数)。
     */
    fun cleanup(ctx: Context, keepDays: Int = DEFAULT_KEEP_DAYS): Pair<Int, Int> {
        val cutoff = System.currentTimeMillis() - keepDays.coerceAtLeast(1) * 24L * 3600 * 1000
        var nMsg = 0
        var nFile = 0

        synchronized(LOCK) {
            // 1. 各频道消息
            val files = ctx.filesDir.listFiles()
            if (files != null) {
                for (f in files) {
                    if (!f.name.startsWith("msg_") || !f.name.endsWith(".json")) continue
                    try {
                        val arr = JSONArray(f.readText())
                        val keep = JSONArray()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            if (o.optLong("ts", 0L) >= cutoff) keep.put(o) else nMsg++
                        }
                        writeArr(ctx, f.name.removePrefix("msg_").removeSuffix(".json"), keep)
                    } catch (e: Exception) {
                        Log.w(TAG, "清理 ${f.name} 失败: ${e.message}")
                    }
                }
            }
            // 2. 媒体文件（按最后修改时间判断）
            val media = mediaDir(ctx).listFiles()
            if (media != null) {
                for (f in media) {
                    if (f.lastModified() < cutoff && f.delete()) nFile++
                }
            }
        }
        return nMsg to nFile
    }
}
