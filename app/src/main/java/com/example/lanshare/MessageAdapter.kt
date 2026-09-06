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
import kotlin.math.abs

/**
 * 消息列表适配器
 *
 * 交互：
 *   点图片      -> 全屏查看
 *   长按图片    -> 选项框（保存到相册 / 全屏查看）
 *   点文件      -> 仅提示（不落盘）
 *   长按文件    -> 选项框（下载）
 *   长按文字    -> 复制
 *
 * 图片分组（v3.9 起在接收端自动判定，不再依赖发送端写入批次号）：
 *   连续若干条都是图片、相邻两条间隔不超过 [GROUP_WINDOW] 毫秒、且来自同一方向/设备，
 *   就折成一组渲染：组内图片自上而下依次排列、不带任何消息气泡边框；
 *   折叠时露出前两张完整图 + 第三张的上半截（共 2.5 张），
 *   结尾一个半透明「展开其余 N 张 / 收起」按钮。
 */
class MessageAdapter(
    private val messages: MutableList<Message>,
    private val onImageClick: (Message) -> Unit,
    private val onImageLongClick: (Message) -> Unit,
    private val onTextLongClick: (Message) -> Unit,
    /** 长按文件：选项框里的「下载」走这里 */
    private val onFileClick: (Message) -> Unit = {},
    /** 点击文件：不落盘，仅提示可长按下载 */
    private val onFileTap: (Message) -> Unit = {}
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    /** 自动折叠的时间窗（毫秒） */
    private val groupWindow = 20_000L

    /** 折叠时完整露出的张数（第三张再露半截，合计 2.5 张） */
    private val collapsedShow = 2

    // ==================== 展示层 ====================
    // 关键：RecyclerView 看到的是 rows，不是原始 messages。
    // 之前直接把 messages 交给列表、再用 GONE 隐藏「组内非首条」，
    // 会有两个绕不开的毛病：
    //   1) 新图插入时只 notifyItemInserted，组首条不会重新 onBind，
    //      于是永远停在「只有第一张」的状态；
    //   2) 被 GONE 的 item 由 ViewHolder 复用，复用前若绑定过多图（高度很大），
    //      RecyclerView 未必重新测量，残留一大块空白。
    // 现在改为在数据层就把连续图片合成一个 Row.Group，
    // 组内非首条根本不产生 item，空白占位从源头消失。
    private sealed class Row {
        data class Single(val msg: Message) : Row()
        data class Group(val items: List<Message>) : Row()
    }

    private var rows: List<Row> = emptyList()

    /** 已展开的组的首条消息 id；不在集合中默认折叠 */
    private val expanded = mutableSetOf<Long>()

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
        // 图片组
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
        // 展示层已保证每个 item 都有内容，不再需要 GONE 隐藏
        holder.itemView.visibility = View.VISIBLE

        val row = rows[position]
        if (row is Row.Group) {
            bindRowGroup(holder, row.items)
            return
        }
        val msg = (row as Row.Single).msg

        // 气泡对齐与配色：自己发靠右、他人发靠左
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = if (msg.isMe) Gravity.END else Gravity.START }
        holder.bubble.layoutParams = lp

        val ctx = holder.itemView.context
        val textColor = ContextCompat.getColor(
            ctx,
            if (msg.isMe) R.color.bubble_me_text else R.color.bubble_other_text
        )

        // 走到这里必是单条消息（图片组已在 rows 层合并，由 bindRowGroup 处理）
        holder.bubble.background = ContextCompat.getDrawable(
                ctx,
                if (msg.isMe) R.drawable.bg_bubble_me else R.drawable.bg_bubble_other
            )
            when {
                msg.locked -> {
                    holder.tvText.visibility = View.VISIBLE
                    holder.ivImage.visibility = View.GONE
                    holder.fileCard.visibility = View.GONE
                    holder.batchBox.visibility = View.GONE
                    holder.tvText.text = "🔒 加密消息：本机口令不匹配，无法解密"
                    clearListeners(holder)
                }
                msg.isImage -> {
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
                }
                msg.isFile -> {
                    holder.fileCard.visibility = View.VISIBLE
                    holder.ivImage.visibility = View.GONE
                    holder.tvText.visibility = View.GONE
                    holder.batchBox.visibility = View.GONE

                    holder.tvFileIcon.text = msg.icon()
                    holder.tvFileName.text = msg.fileName.ifBlank { "文件" }
                    holder.tvFileSize.text =
                        if (msg.omitted || msg.content.isBlank()) "文件（内容已不可回补）"
                        else msg.sizeText()

                    if (msg.content.isBlank()) {
                        holder.fileCard.alpha = 0.55f
                        holder.fileCard.setOnClickListener(null)
                        holder.fileCard.setOnLongClickListener(null)
                    } else {
                        holder.fileCard.alpha = 1f
                        // 点击不落盘，仅提示；长按才弹下载选项框
                        holder.fileCard.setOnClickListener { onFileTap(msg) }
                        holder.fileCard.setOnLongClickListener { onFileClick(msg); true }
                    }
                    holder.ivImage.setOnClickListener(null)
                    holder.ivImage.setOnLongClickListener(null)
                    holder.tvText.setOnLongClickListener(null)
                }
                else -> {
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
            }

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

    override fun getItemCount(): Int = rows.size

    /**
     * 外部数据变更后调用：重算展示行并刷新。
     * 用 notifyDataSetChanged 是有意为之——连续图片逐条到达时，
     * 组的构成时刻在变，精确通知（inserted/changed）极易漏掉组首条的重绘，
     * 那正是「只显示第一张、后面跟着空白」的成因。全量刷新最稳，
     * 且图片有 bitmap 缓存，不会重复解码。
     */
    fun refresh() {
        rebuildRows()
        notifyDataSetChanged()
    }

    /** 把原始消息流压成展示行：连续同组图片合并为一个 Group */
    private fun rebuildRows() {
        val out = mutableListOf<Row>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            if (m.isImage && !m.locked) {
                val grp = mutableListOf<Message>()
                var j = i
                // 逐个累积：只要求「相邻两条」间隔在窗口内，
                // 因此一条长串里途中的间隔都是小步长，不会因首尾跨度大而被误判。
                while (j < messages.size &&
                    messages[j].isImage && !messages[j].locked &&
                    (j == i || inSameGroup(messages[j - 1], messages[j]))
                ) {
                    grp.add(messages[j])
                    j++
                }
                if (grp.size >= 2) {
                    out.add(Row.Group(grp))
                    i = j
                    continue
                }
            }
            out.add(Row.Single(m))
            i++
        }
        rows = out
    }

    /** 图片组：整组不带气泡边框，图片一张张往下排 */
    private fun bindRowGroup(holder: VH, group: List<Message>) {
        holder.bubble.background = null
        bindBatch(holder, group)
    }

    // ==================== 图片自动分组 ====================

    /** 两条消息能否归入同一组：都是图片、同方向、同发送者、间隔在窗口内 */
    private fun inSameGroup(a: Message, b: Message): Boolean {
        if (!a.isImage || !b.isImage) return false
        if (a.isMe != b.isMe) return false
        if (a.senderName != b.senderName) return false
        return abs(a.ts - b.ts) <= groupWindow
    }

    // groupStartAt / groupAt 已移除：分组现由 rebuildRows() 在数据层完成，
    // 不再需要在绑定阶段反查「组从哪里开始」。

    private fun bindBatch(holder: VH, group: List<Message>) {
        holder.ivImage.visibility = View.GONE
        holder.tvText.visibility = View.GONE
        holder.fileCard.visibility = View.GONE
        holder.batchBox.visibility = View.VISIBLE

        val ctx = holder.itemView.context
        val head = group.first()
        val isExpanded = expanded.contains(head.id)

        holder.batchGrid.removeAllViews()
        val show = if (isExpanded) group else group.take(collapsedShow)
        show.forEach { m -> holder.batchGrid.addView(buildImageCell(ctx, m, true)) }
        // 折叠时第三张只露上半截
        if (!isExpanded && group.size > collapsedShow) {
            holder.batchGrid.addView(buildImageCell(ctx, group[collapsedShow], false))
        }

        val rest = group.size - collapsedShow
        holder.batchToggle.text = when {
            group.size <= collapsedShow -> ""
            isExpanded -> "△ 收起"
            else -> "▽ 展开其余 " + rest + " 张"
        }
        holder.batchToggle.visibility =
            if (group.size > collapsedShow) View.VISIBLE else View.GONE
        holder.batchToggle.alpha = 0.85f          // 半透明，弱化存在感
        holder.batchToggle.setOnClickListener {
            if (isExpanded) expanded.remove(head.id) else expanded.add(head.id)
            notifyDataSetChanged()
        }
    }

    /** 组内一张图：定高铺满宽度；full=false 时只画上半截 */
    private fun buildImageCell(ctx: Context, m: Message, full: Boolean): View {
        val h = dp(ctx, if (full) 140 else 68)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
        lp.bottomMargin = dp(ctx, 4)
        return ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            background = roundedBg(ctx, "#313244")
            // 缩略图用小尺寸缓存，与查看大图用的 bitmap 分开，互不降质
            val bmp = m.thumbBitmap ?: ImageUtils.decodeForDisplay(m.content, 480)
                .also { m.thumbBitmap = it }
            setImageBitmap(bmp)
            setOnClickListener { onImageClick(m) }
            setOnLongClickListener { onImageLongClick(m); true }
            layoutParams = lp
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
