package com.example.lanshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * 消息列表适配器
 * 交互：
 *   点图片      -> 全屏查看
 *   长按图片    -> 保存到相册
 *   长按文字    -> 复制文本
 */
class MessageAdapter(
    private val messages: MutableList<Message>,
    private val onImageClick: (Message) -> Unit,
    private val onImageLongClick: (Message) -> Unit,
    private val onTextLongClick: (Message) -> Unit,
    /** 点击文件卡片：保存/打开文件 */
    private val onFileClick: (Message) -> Unit = {}
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: LinearLayout = view.findViewById(R.id.bubble)
        val ivImage: ImageView = view.findViewById(R.id.ivImage)
        val tvText: TextView = view.findViewById(R.id.tvText)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        // 文件消息
        val fileCard: LinearLayout = view.findViewById(R.id.fileCard)
        val tvFileIcon: TextView = view.findViewById(R.id.tvFileIcon)
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvFileSize: TextView = view.findViewById(R.id.tvFileSize)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = messages[position]

        // 气泡对齐与配色：自己发靠右、他人发靠左
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = if (msg.isMe) Gravity.END else Gravity.START }
        holder.bubble.layoutParams = lp
        holder.bubble.background = ContextCompat.getDrawable(
            holder.itemView.context,
            if (msg.isMe) R.drawable.bg_bubble_me else R.drawable.bg_bubble_other
        )

        // 文字颜色随气泡背景切换：自己的气泡是亮蓝底，需用深色文字才可读
        val textColor = ContextCompat.getColor(
            holder.itemView.context,
            if (msg.isMe) R.color.bubble_me_text else R.color.bubble_other_text
        )
        holder.tvText.setTextColor(textColor)
        holder.tvTime.setTextColor(textColor)
        holder.tvTime.alpha = 0.55f
        // 文件卡片：自己发的气泡是亮底，文字同步切换为深色以保证可读
        holder.tvFileName.setTextColor(textColor)
        holder.tvFileSize.setTextColor(textColor)
        holder.tvFileSize.alpha = 0.7f
        holder.tvSender.setTextColor(
            ContextCompat.getColor(holder.itemView.context,
                if (msg.isMe) R.color.bubble_me_text else R.color.primary)
        )

        // 加密但本机无法解密：不展示内容，避免误导
        if (msg.locked) {
            holder.tvText.visibility = View.VISIBLE
            holder.ivImage.visibility = View.GONE
            holder.fileCard.visibility = View.GONE
            holder.tvText.text = "🔒 加密消息：本机口令不匹配，无法解密"
            holder.tvText.setOnLongClickListener(null)
            holder.ivImage.setOnClickListener(null)
            holder.ivImage.setOnLongClickListener(null)
            holder.fileCard.setOnClickListener(null)
        } else if (msg.isImage) {
            holder.ivImage.visibility = View.VISIBLE
            holder.tvText.visibility = View.GONE
            holder.fileCard.visibility = View.GONE
            val bmp = msg.bitmap ?: decodeBase64(msg.content).also { msg.bitmap = it }
            if (bmp != null) holder.ivImage.setImageBitmap(bmp)

            holder.ivImage.setOnClickListener { onImageClick(msg) }
            holder.ivImage.setOnLongClickListener { onImageLongClick(msg); true }
            holder.tvText.setOnLongClickListener(null)
            holder.fileCard.setOnClickListener(null)
        } else if (msg.isFile) {
            // 文件消息：图标 + 文件名 + 大小
            holder.fileCard.visibility = View.VISIBLE
            holder.ivImage.visibility = View.GONE
            holder.tvText.visibility = View.GONE

            holder.tvFileIcon.text = msg.icon()
            holder.tvFileName.text = msg.fileName.ifBlank { "文件" }
            holder.tvFileSize.text =
                if (msg.omitted || msg.content.isBlank()) "文件（内容已不可回补）"
                else msg.sizeText()

            // 正文缺失的历史条目不可点击保存
            if (msg.content.isBlank()) {
                holder.fileCard.alpha = 0.55f
                holder.fileCard.setOnClickListener(null)
                holder.fileCard.setOnLongClickListener(null)
            } else {
                holder.fileCard.alpha = 1f
                holder.fileCard.setOnClickListener { onFileClick(msg) }
                holder.fileCard.setOnLongClickListener { onFileClick(msg); true }
            }
            holder.ivImage.setOnClickListener(null)
            holder.ivImage.setOnLongClickListener(null)
            holder.tvText.setOnLongClickListener(null)
        } else {
            holder.tvText.visibility = View.VISIBLE
            holder.ivImage.visibility = View.GONE
            holder.fileCard.visibility = View.GONE
            holder.tvText.text = msg.content

            holder.tvText.setOnLongClickListener { onTextLongClick(msg); true }
            holder.ivImage.setOnClickListener(null)
            holder.ivImage.setOnLongClickListener(null)
            holder.fileCard.setOnClickListener(null)
        }

        // 发送者与加密标记（自己发的消息不显示，避免冗余）
        if (!msg.isMe && (msg.senderName.isNotBlank() || msg.encrypted)) {
            holder.tvSender.visibility = View.VISIBLE
            holder.tvSender.text =
                msg.senderName.ifBlank { "未知设备" } + (if (msg.encrypted) " 🔒" else "")
        } else {
            holder.tvSender.visibility = View.GONE
        }

        holder.tvTime.text = msg.time
    }

    override fun getItemCount(): Int = messages.size

    private fun decodeBase64(dataUrl: String): Bitmap? {
        return try {
            val base64 = dataUrl.substringAfter("base64,", "")
            if (base64.isEmpty()) return null
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }
}
