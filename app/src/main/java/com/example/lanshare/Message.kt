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
    val omitted: Boolean = false
) {
    /** 缓存解码后的图片，避免列表滚动时重复解码（不参与 equals） */
    var bitmap: android.graphics.Bitmap? = null

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
