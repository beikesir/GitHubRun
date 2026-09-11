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
    /**
     * 界面宽度（像素）：所有显示尺寸都以它为基准换算，
     * 与浏览器扩展端的 UI_W 是同一套比例，两端观感因此一致。
     *   头像边长          界面 1/15
     *   横图（宽 >= 高）  以宽为准，宽 = 界面 1/2
     *   竖图（宽 <  高）  以高为准，高 = 界面 9/40
     *   文件卡片          宽 = 界面 1/2，高 = 界面 1/10
     */
    private val uiWidthPx: Int,
    private val onImageClick: (Message) -> Unit,
    private val onImageLongClick: (Message) -> Unit,
    private val onTextLongClick: (Message) -> Unit,
    /** 长按文件：选项框里的「下载」走这里 */
    private val onFileClick: (Message) -> Unit = {},
    /** 点击文件：不落盘，仅提示可长按下载 */
    private val onFileTap: (Message) -> Unit = {}
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    // ==================== 显示尺寸（全部由界面宽度换算） ====================
    /** 头像边长：界面 1/15（夹在 16~96px，避免超窄/超宽屏上失真） */
    private val avatarPx = (uiWidthPx / 15f).toInt().coerceIn(16, 96)

    /** 横图以宽为准：宽 = 界面 1/2 */
    private val imageWPx = (uiWidthPx / 2f).toInt()

    /** 竖图以高为准：高 = 界面 9/40 */
    private val imageHPx = (uiWidthPx * 9f / 40f).toInt()

    /** 文件卡片：宽 = 界面 1/2、高 = 界面 1/10 */
    private val fileWPx = (uiWidthPx / 2f).toInt()
    private val fileHPx = (uiWidthPx / 10f).toInt()

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

        val item = rows[position]
        if (item is Row.Group) {
            bindRowGroup(holder, item.items)
            return
        }
        val msg = (item as Row.Single).msg
        val ctx = holder.itemView.context

        // 整行贴边：自己靠右、他人靠左。
        // 自己的行额外设为 RTL，让头像排到外侧（右），与扩展端一致；
        // 内容列在 XML 里已强制 LTR，内部文字与对齐不受影响。
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = if (msg.isMe) Gravity.END else Gravity.START }
        holder.row.layoutParams = lp
        holder.row.layoutDirection =
            if (msg.isMe) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        // 头像：方形色块 + 设备名首字符（颜色由设备名派生，与扩展端同算法）
        holder.ivAvatar.layoutParams = LinearLayout.LayoutParams(avatarPx, avatarPx)
        val sender = senderOf(msg, ctx)
        holder.ivAvatar.text =
            sender.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        holder.ivAvatar.background = avatarBg(ctx, sender)
        holder.ivAvatar.visibility = View.VISIBLE

        val textColor = ContextCompat.getColor(
            ctx,
            if (msg.isMe) R.color.bubble_me_text else R.color.bubble_other_text
        )

        // 内容列本身不再套气泡：
        // 气泡背景只给文字，图片直接显示、文件卡自带背景。
        holder.bubble.background = null
        when {
                msg.locked -> {
                    holder.tvText.visibility = View.VISIBLE
                    holder.ivImage.visibility = View.GONE
                    holder.fileCard.visibility = View.GONE
                    holder.batchBox.visibility = View.GONE
                    holder.tvText.text = "🔒 加密消息：本机口令不匹配，无法解密"
                    holder.tvText.background = bubbleBg(ctx, msg.isMe)
                    clearListeners(holder)
                }
                msg.isImage -> {
                    holder.ivImage.visibility = View.VISIBLE
                    holder.tvText.visibility = View.GONE
                    holder.fileCard.visibility = View.GONE
                    holder.batchBox.visibility = View.GONE
                    // 横图以宽为准（宽 = 界面 1/2）、竖图以高为准（高 = 界面 9/40），
                    // 另一边由 adjustViewBounds 按原图比例自适应，不做裁切
                    applyImageSize(holder.ivImage, msg, ctx)
                    // 优先级：本地留底 > 中继直链 > 内联 base64
                    if (!ImageLoader.load(holder.ivImage, msg)) {
                        val bmp = msg.bitmap ?: ImageUtils.decodeForDisplay(msg.content, 1280)
                            .also { msg.bitmap = it }
                        if (bmp != null) holder.ivImage.setImageBitmap(bmp)
                    }

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
                    // 卡片尺寸固定：宽 = 界面 1/2、高 = 界面 1/10
                    holder.fileCard.layoutParams =
                        LinearLayout.LayoutParams(fileWPx, fileHPx)

                    holder.tvFileIcon.text = msg.icon()
                    holder.tvFileName.text = msg.fileName.ifBlank { "文件" }
                    // 外置后正文在 fileUrl 直链上、content 为空，不能据此判为不可回补
                    val hasBody = msg.content.isNotBlank() || msg.fileUrl.isNotBlank()
                    holder.tvFileSize.text =
                        if (msg.omitted || !hasBody) "文件（内容已不可回补）"
                        else msg.sizeText()

                    if (!hasBody) {
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
                    // 文字才带气泡：背景给 TextView 本身，只包住文字
                    holder.tvText.background = bubbleBg(ctx, msg.isMe)

                    holder.tvText.setOnLongClickListener { onTextLongClick(msg); true }
                    holder.ivImage.setOnClickListener(null)
                    holder.ivImage.setOnLongClickListener(null)
                    holder.fileCard.setOnClickListener(null)
                }
            }

        holder.tvText.setTextColor(textColor)
        // 时间在内容列上（深色背景），不能沿用气泡内的文字色
        holder.tvTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
        holder.tvTime.alpha = 0.75f
        holder.tvFileName.setTextColor(textColor)
        holder.tvFileSize.setTextColor(textColor)
        holder.tvFileSize.alpha = 0.7f
        holder.tvSender.setTextColor(
            ContextCompat.getColor(
                ctx,
                // 深色背景上必须用亮色：他人蓝、自己绿，一眼可辨
                if (msg.isMe) R.color.accent else R.color.primary
            )
        )

        // 发送者一律显示（含自己）：多端互发时便于区分来源
        holder.tvSender.visibility = View.VISIBLE
        holder.tvSender.text = sender + (if (msg.encrypted) " 🔒" else "")

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

    /** 图片组：整组不带气泡，图片一张张往下排（头像与对齐与单条消息一致） */
    private fun bindRowGroup(holder: VH, group: List<Message>) {
        val head = group.first()
        val ctx = holder.itemView.context

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = if (head.isMe) Gravity.END else Gravity.START }
        holder.row.layoutParams = lp
        holder.row.layoutDirection =
            if (head.isMe) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        holder.ivAvatar.layoutParams = LinearLayout.LayoutParams(avatarPx, avatarPx)
        val sender = senderOf(head, ctx)
        holder.ivAvatar.text =
            sender.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        holder.ivAvatar.background = avatarBg(ctx, sender)
        holder.ivAvatar.visibility = View.VISIBLE

        holder.bubble.background = null
        holder.tvSender.visibility = View.VISIBLE
        holder.tvSender.text = sender + (if (head.encrypted) " 🔒" else "")
        holder.tvSender.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (head.isMe) R.color.accent else R.color.primary
            )
        )
        holder.tvTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_dim))
        holder.tvTime.alpha = 0.75f

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
        // 组容器取横图宽度（界面 1/2）；竖图更窄，会按自身比例排布
        holder.batchBox.layoutParams = LinearLayout.LayoutParams(
            imageWPx, LinearLayout.LayoutParams.WRAP_CONTENT
        )

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

    /**
     * 组内一张图。
     *
     * full=true  -> 完整显示：与单张图片同一套横竖规则
     *              （横图以宽为准、竖图以高为准），按原图比例自适应、不裁切。
     *              此前是「定高 + CENTER_CROP」，任何比例都会被切成一条，
     *              这正是「组里每张只显示一部分」的原因。
     * full=false -> 折叠态的第三张：仍按完整比例渲染，但外层容器只取其上半，
     *              用来暗示「下面还有」，而不是把图片压扁。
     */
    private fun buildImageCell(ctx: Context, m: Message, full: Boolean): View {
        return if (full) {
            ImageView(ctx).apply {
                // 与单张图片同一套横竖规则：竖图以高为准、横图以宽为准
                val portrait = isPortrait(m, ctx)
                layoutParams = LinearLayout.LayoutParams(
                    if (portrait) LinearLayout.LayoutParams.WRAP_CONTENT else imageWPx,
                    if (portrait) imageHPx else LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 3) }
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                background = roundedBg(ctx, "#313244")
                // 缩略图用小尺寸缓存，与查看大图用的 bitmap 分开，互不降质
                if (!ImageLoader.load(this, m)) {
                    val bmp = m.thumbBitmap
                        ?: ImageUtils.decodeForDisplay(m.content, 480).also { m.thumbBitmap = it }
                    setImageBitmap(bmp)
                }
                setOnClickListener { onImageClick(m) }
                setOnLongClickListener { onImageLongClick(m); true }
            }
        } else buildHalfCell(ctx, m)
    }

    /**
     * 折叠态第三张：只露上半截。
     *
     * 实现要点：容器高度固定为「半张」（宽的一半），
     * 图片仍按原比例完整渲染（adjustViewBounds + WRAP_CONTENT）并顶部对齐，
     * 超出容器的下半部分由 clipChildren 裁掉。
     *
     * 因此**不需要**预先知道图片真实宽高，也就不再依赖 Glide 的加载完成回调
     * （见 ImageLoader 的说明：RequestListener 的 Java 签名跨版本易失配）。
     */
    private fun buildHalfCell(ctx: Context, m: Message): View {
        // 容器高度取「该图完整显示高度」的一半：
        // 竖图直接用 imageHPx；横图按「宽 -> 高」换算，故必须知道原图比例。
        val sz = probeSize(m, ctx)
        val fullH = if (sz != null && sz.first >= sz.second)
            (imageWPx * sz.second.toFloat() / sz.first).toInt()
        else imageHPx
        val container = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                imageWPx, (fullH / 2).coerceAtLeast(dp(ctx, 20))
            ).apply { bottomMargin = dp(ctx, 3) }
            clipChildren = true
        }
        val iv = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            background = roundedBg(ctx, "#313244")
        }
        container.addView(iv)

        if (!ImageLoader.load(iv, m)) {
            val bmp = m.thumbBitmap
                ?: ImageUtils.decodeForDisplay(m.content, 480).also { m.thumbBitmap = it }
            iv.setImageBitmap(bmp)
        }
        iv.setOnClickListener { onImageClick(m) }
        iv.setOnLongClickListener { onImageLongClick(m); true }
        return container
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

    // ==================== 头像、气泡与图片尺寸 ====================

    /** 发送者显示名：自己发的消息回退到本机设备名 */
    private fun senderOf(m: Message, ctx: Context): String {
        if (m.senderName.isNotBlank()) return m.senderName
        return if (m.isMe) DeviceIdentity.name(ctx).ifBlank { DeviceIdentity.defaultName() }
        else "未知设备"
    }

    /**
     * 字符串哈希（djb2）：与扩展端 popup.js 的 hashStr 同一算法，
     * 保证同一设备名在两端得到同一个头像底色。
     */
    private fun hashStr(s: String): Int {
        var h = 5381
        for (c in s) h = ((h shl 5) + h + c.code) and 0x7FFFFFFF
        return h
    }

    /**
     * 头像底色：由设备名稳定派生。
     * 扩展端用 HSL(h, 52%, 46%)，这里换成等价的 HSV(h, 68%, 70%)，视觉一致。
     */
    private fun avatarBg(ctx: Context, seed: String): GradientDrawable {
        val hue = (hashStr(seed) % 360).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, 4).toFloat()
            setColor(Color.HSVToColor(floatArrayOf(hue, 0.68f, 0.70f)))
        }
    }

    /** 文字气泡背景：自己蓝、他人灰（只给 TextView，不套整列） */
    private fun bubbleBg(ctx: Context, isMe: Boolean) =
        ContextCompat.getDrawable(
            ctx,
            if (isMe) R.drawable.bg_bubble_me else R.drawable.bg_bubble_other
        )

    /**
     * 探测图片原始宽高，结果缓存在 Message 上（一张图只探测一次）。
     * 优先用已解码的 bitmap / 本机留底文件（最快），最后才解 base64。
     * 探测不到（例如只有中继直链）时返回 null，调用方按横图处理。
     */
    private fun probeSize(m: Message, ctx: Context): Pair<Int, Int>? {
        if (m.imgW > 0 && m.imgH > 0) return m.imgW to m.imgH
        val sz = when {
            m.bitmap != null -> m.bitmap!!.width to m.bitmap!!.height
            m.thumbBitmap != null -> m.thumbBitmap!!.width to m.thumbBitmap!!.height
            m.localPath.isNotBlank() ->
                LocalStore.mediaFile(ctx, m.localPath)?.let { ImageUtils.probeSize(it) }
            m.content.isNotBlank() -> ImageUtils.probeSize(m.content)
            else -> null
        }
        if (sz != null && sz.first > 0 && sz.second > 0) {
            m.imgW = sz.first
            m.imgH = sz.second
        }
        return sz
    }

    /** 竖图（高 > 宽）以高为准；探测不到时按横图处理 */
    private fun isPortrait(m: Message, ctx: Context): Boolean {
        val sz = probeSize(m, ctx) ?: return false
        return sz.first < sz.second
    }

    /**
     * 按横竖比例给图片定尺寸：
     *   横图（宽 >= 高）以宽为准 -> 宽 = 界面 1/2，高度自适应；
     *   竖图（宽 <  高）以高为准 -> 高 = 界面 9/40，宽度自适应。
     * 配合 adjustViewBounds，两条边都按原图比例，不会拉伸也不会裁切。
     */
    private fun applyImageSize(iv: ImageView, m: Message, ctx: Context) {
        val portrait = isPortrait(m, ctx)
        iv.layoutParams = LinearLayout.LayoutParams(
            if (portrait) LinearLayout.LayoutParams.WRAP_CONTENT else imageWPx,
            if (portrait) imageHPx else LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
