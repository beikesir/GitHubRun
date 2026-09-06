package com.example.lanshare

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
 *   点文件      -> 保存/打开文件
 *
 * 批量消息：一次选中的多张图片/多个文件共用同一个 batchId，
 * 这里把它们聚合成一个气泡渲染：默认折叠为「两个半」（前两张完整、第三张露一半），
 * 点「展开全部」后按每行三个铺开，再点「收起」还原。
 */
class MessageAdapter(
    private val messages: MutableList<Message>,
    private val onImageClick: (Message) -> Unit,
    private val onImageLongClick: (Message) -> Unit,
    private val onTextLongClick: (Message) -> Unit,
    /** 点击文件卡片：保存/打开文件 */
    private val onFileClick: (Message) -> Unit = {}
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    /** 已展开的批次 ID；不在集合中的批次默认折叠 */
    private val expanded = mutableSetOf<String>()

    /** 折叠态每行显示 3 个单元格（第 3 个半宽，形成「两个半」） */
    private val perRow = 3

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
        // 批量消息
        val batchBox: LinearLayout = view.findViewById(R.id.batchBox)
        val batchGrid: LinearLayout = view.findViewById(R.id.batchGrid)
        val batchToggle: TextView = view.findViewById(R.id.batchToggle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = messages[position]

        // 批次内非首条：整条隐藏，内容由首条统一渲染
        if (msg.batchId.isNotBlank() && position != batchStartAt(position)) {
            holder.itemView.visibility = View.GONE
            return
        }
        holder.itemView.visibility = View.VISIBLE

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
        val ctx = holder.itemView.context
        val textColor = ContextCompat.getColor(
            ctx,
            if (msg.isMe) R.color.bubble_me_text else R.color.bubble_other_text
        )
        holder.tvText.setTextColor(textColor)
        holder.tvTime.setTextColor(textColor)
        holder.tvTime.alpha = 0.55f
        holder.tvFileName.setTextColor(textColor)
        holder.tvFileSize.setTextColor(textColor)
        holder.tvFileSize.alpha = 0.7f
        holder.tvSender.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (msg.isMe) R.color.bubble_me_text else R.color.primary
            )
        )

        // 只有两项以上才值得折叠；单张图/单个文件按普通消息渲染，避免出现空壳气泡
        val batchGroup = if (msg.batchId.isNotBlank()) batchGroupAt(position) else null
        if (batchGroup != null && batchGroup.size > 1) {
            // 批次用更紧凑的气泡：常规气泡四周 12/8dp 的内边距套在网格外会很松
            holder.bubble.background = ContextCompat.getDrawable(
                ctx,
                if (msg.isMe) R.drawable.bg_bubble_batch_me else R.drawable.bg_bubble_batch_other
            )
            bindBatch(holder, batchGroup)
        } else if (msg.locked) {
            // 加密但本机无法解密：不展示内容，避免误导
            holder.tvText.visibility = View.VISIBLE
            holder.ivImage.visibility = View.GONE
            holder.fileCard.visibility = View.GONE
            holder.batchBox.visibility = View.GONE
            holder.tvText.text = "🔒 加密消息：本机口令不匹配，无法解密"
            clearListeners(holder)
        } else if (msg.isImage) {
            holder.ivImage.visibility = View.VISIBLE
            holder.tvText.visibility = View.GONE
            holder.fileCard.visibility = View.GONE
            holder.batchBox.visibility = View.GONE
            val bmp = msg.bitmap ?: ImageUtils.decodeForDisplay(msg.content, 1280)
                .also { msg.bitmap = it }
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
            holder.batchBox.visibility = View.GONE

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
            holder.batchBox.visibility = View.GONE
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

    // ==================== 批量消息 ====================

    /** 该批次连续段的起始下标 */
    private fun batchStartAt(pos: Int): Int {
        val bid = messages[pos].batchId
        var s = pos
        while (s > 0 && messages[s - 1].batchId == bid) s--
        return s
    }

    /** 取该批次连续段内的全部消息 */
    private fun batchGroupAt(pos: Int): List<Message> {
        val bid = messages[pos].batchId
        val out = mutableListOf<Message>()
        var i = batchStartAt(pos)
        while (i < messages.size && messages[i].batchId == bid) {
            out.add(messages[i]); i++
        }
        return out
    }

    private fun bindBatch(holder: VH, group: List<Message>) {
        holder.ivImage.visibility = View.GONE
        holder.tvText.visibility = View.GONE
        holder.fileCard.visibility = View.GONE
        holder.batchBox.visibility = View.VISIBLE

        val ctx = holder.itemView.context
        val bid = group.first().batchId
        val isExpanded = expanded.contains(bid)

        holder.batchGrid.removeAllViews()
        if (isExpanded) {
            // 展开：每行 3 个，全部铺开
            group.chunked(perRow).forEach { row ->
                holder.batchGrid.addView(buildRow(ctx, row, halfLast = false, badge = null))
            }
        } else {
            // 折叠：前两项完整，第三项只露一半，形成「两个半」；
            // 再往后的项数叠在半格上做角标。
            //   size=2 -> 两个完整，无角标
            //   size=3 -> 两个完整 + 半格，无角标
            //   size=5 -> 两个完整 + 半格，角标 +2（即第 4、5 项）
            val rest = group.size - perRow
            val badge = if (rest > 0) "+$rest" else null
            holder.batchGrid.addView(
                buildRow(ctx, group.take(perRow), halfLast = group.size >= perRow, badge = badge)
            )
        }

        holder.batchToggle.text =
            if (isExpanded) "收起" else "展开全部（${group.size} 项）"
        holder.batchToggle.setOnClickListener {
            if (isExpanded) expanded.remove(bid) else expanded.add(bid)
            notifyDataSetChanged()
        }
    }

    /**
     * 构建一行缩略图。
     * @param halfLast true 时第三个单元格只占半格（形成「两个半」的折叠效果）
     * @param badge    叠在半格上的角标文案，例如 "+3"
     */
    private fun buildRow(
        ctx: Context, items: List<Message>,
        halfLast: Boolean, badge: String?
    ): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        // 尺寸需让展开态三列仍落在气泡 maxWidth 内（见 item_message.xml）：
        // 3×82 + 2×5 间隙 + 气泡左右内边距 10 = 266dp < 280dp
        val full = dp(ctx, 82)
        val half = dp(ctx, 41)
        val h = dp(ctx, 84)
        val gap = dp(ctx, 5)

        items.forEachIndexed { i, m ->
            val isHalf = halfLast && i == 2
            val w = if (isHalf) half else full
            val cell = if (m.isImage) buildImageCell(ctx, m) else buildFileCell(ctx, m)

            val wrapped = if (isHalf && !badge.isNullOrEmpty()) {
                FrameLayout(ctx).apply {
                    addView(cell, FrameLayout.LayoutParams(w, h))
                    addView(TextView(ctx).apply {
                        text = badge
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        gravity = Gravity.CENTER
                        background = roundedBg(ctx, "#000000").apply { alpha = 130 }
                    }, FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER })
                }
            } else cell

            row.addView(wrapped, LinearLayout.LayoutParams(w, h).apply { marginEnd = gap })
        }
        return row
    }

    private fun buildImageCell(ctx: Context, m: Message): View {
        return ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedBg(ctx, "#313244")
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            // 缩略图用小尺寸缓存，与查看大图用的 bitmap 分开，互不降质
            val bmp = m.thumbBitmap ?: ImageUtils.decodeForDisplay(m.content, 480)
                .also { m.thumbBitmap = it }
            setImageBitmap(bmp)
            setOnClickListener { onImageClick(m) }
            setOnLongClickListener { onImageLongClick(m); true }
        }
    }

    private fun buildFileCell(ctx: Context, m: Message): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg(ctx, "#45475A")
            val icon = TextView(ctx).apply {
                text = m.icon()
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }
            val name = TextView(ctx).apply {
                text = m.fileName.ifBlank { "文件" }
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#CDD6F4"))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                alpha = 0.85f
            }
            addView(icon)
            addView(name, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(ctx, 4); marginEnd = dp(ctx, 4)
            })
            setOnClickListener { onFileClick(m) }
            setOnLongClickListener { onFileClick(m); true }
        }
    }

    private fun roundedBg(ctx: Context, colorHex: String): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, 8).toFloat()
            setColor(Color.parseColor(colorHex))
        }

    private fun clearListeners(holder: VH) {
        holder.tvText.setOnLongClickListener(null)
        holder.ivImage.setOnClickListener(null)
        holder.ivImage.setOnLongClickListener(null)
        holder.fileCard.setOnClickListener(null)
        holder.fileCard.setOnLongClickListener(null)
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
