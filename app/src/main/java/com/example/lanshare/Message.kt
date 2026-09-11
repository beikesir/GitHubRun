package com.example.lanshare

/**
 * 聊天消息
 * @param content 文本内容；若是图片则为 data URL（data:image/jpeg;base64,xxx）
 * @param isImage 是否为图片消息
 * @param isMe    是否为自己发送
 * @param time    显示用时间字符串（HH:mm）
 * @param senderName 发送者设备名（他人消息才有意义）
 * @param encrypted  该消息在传输中是否加密
 * @param locked     加密但本机无法解密（未配置口令或口令不匹配）
 */
data class Message(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val content: String,
    val isImage: Boolean = false,
    val isMe: Boolean = false,
    val time: String = "",
    val senderName: String = "",
    val encrypted: Boolean = false,
    val locked: Boolean = false,
    /** 是否为文件消息（任意格式，图片走 isImage 而非此标志） */
    val isFile: Boolean = false,
    /** 文件原始名（含扩展名），用于展示与保存 */
    val fileName: String = "",
    /** 文件 MIME 类型 */
    val mime: String = "",
    /** 文件原始字节大小 */
    val size: Long = 0L,
    /** 正文未随消息到达（中继因体积过大未落盘的历史条目），仅剩元数据 */
    val omitted: Boolean = false,
    /**
     * 来源标记：对方经「系统分享」发来时为 "share"，接收端自动落盘；
     * 应用内主动发送为空串，需长按手动下载。
     */
    val source: String = "",
    /**
     * 阶段1：正文外置到中继磁盘后的引用直链。
     * fileUrl 非空表示正文不在 content 里——列表用 thumbUrl 快速渲染，
     * 查看大图/下载用 fileUrl，避免解码庞大的 base64。
     */
    val fileId: String = "",
    val fileUrl: String = "",
    val thumbUrl: String = "",
    /** 时间戳（毫秒）：列表据此把「相邻 20 秒内、连续的图片」自动折成一组 */
    val ts: Long = System.currentTimeMillis(),
    /**
     * 本地留底：图片/文件正文在私有目录 media/ 下的文件名。
     * 非空表示内容已存本机，重启后仍可显示，不再依赖中继。
     */
    val localPath: String = ""
) {
    /** 缓存解码后的图片（查看大图用），避免列表滚动时重复解码（不参与 equals） */
    var bitmap: android.graphics.Bitmap? = null

    /**
     * 批次缩略图专用缓存（小尺寸）。
     * 与 bitmap 分开存放，避免缩略图把大图缓存挤成低清，导致点开全屏后模糊。
     */
    var thumbBitmap: android.graphics.Bitmap? = null

    /**
     * 图片原始宽高缓存：决定「横图以宽为准 / 竖图以高为准」的显示规则。
     * 探测一次后复用，避免每次绑定都重新解码边界。
     * 与 bitmap 一样放在类体内 —— 不参与构造与 equals，也不会被持久化。
     */
    var imgW: Int = 0
    var imgH: Int = 0

    /** 依据扩展名给出直观图标 */
    fun icon(): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "📕"
            "doc", "docx" -> "📘"
            "xls", "xlsx", "csv" -> "📗"
            "ppt", "pptx" -> "📙"
            "zip", "rar", "7z", "tar", "gz" -> "🗜️"
            "mp3", "wav", "flac", "ogg" -> "🎵"
            "mp4", "mkv", "avi", "mov", "webm" -> "🎬"
            "apk" -> "📦"
            "txt", "md", "log", "json", "xml" -> "📄"
            else -> "📎"
        }
    }

    /** 人类可读的大小 */
    fun sizeText(): String {
        val b = size
        return when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
            b < 1024 * 1024 * 1024 -> String.format("%.1f MB", b / 1024.0 / 1024.0)
            else -> String.format("%.2f GB", b / 1024.0 / 1024.0 / 1024.0)
        }
    }
}
