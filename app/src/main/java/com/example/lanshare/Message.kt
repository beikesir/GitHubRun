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
    val locked: Boolean = false
) {
    /** 缓存解码后的图片，避免列表滚动时重复解码（不参与 equals） */
    var bitmap: android.graphics.Bitmap? = null
}
