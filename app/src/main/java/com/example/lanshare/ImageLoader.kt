package com.example.lanshare

import android.widget.ImageView
import com.bumptech.glide.Glide

/**
 * 图片加载统一入口（列表缩略图与全屏大图共用一份逻辑）
 *
 * 优先级：本机留底 > 中继直链 > 内联 base64。
 * 本机留底优先是因为客户端已改为只依赖自身存储：
 * 中继重启后直链会失效，但私有目录 media/ 下的副本仍在。
 *
 * 抽成公共对象的原因：此前加载逻辑写在 MessageAdapter 内部，
 * 全屏查看器想复用只能复制一份，两处很容易改出不一致（例如一边支持本地副本、另一边不支持，
 * 表现就是「列表能显示、点开却提示解码失败」）。
 */
object ImageLoader {

    /**
     * @return 已交给 Glide 加载返回 true；三者都不可用返回 false（调用方自行兜底解码）
     *
     * 注意：这里刻意**不提供**加载完成回调。
     * 此前用 Glide 的 RequestListener 拿图片真实宽高来算「折叠态第三张露一半」的高度，
     * 但该接口的 Java 签名在 Glide 各版本间存在差异，
     * Kotlin 侧手写实现极易出现 overrides nothing / missing abstract member。
     * 现在折叠态改由容器按固定高度裁切（见 MessageAdapter.buildHalfCell），
     * 图片按原比例渲染、顶部对齐，超出部分自然被裁掉——不再需要预知宽高。
     */
    fun load(view: ImageView, m: Message): Boolean {
        val local = LocalStore.mediaFile(view.context, m.localPath)
        if (local != null) {
            Glide.with(view).load(local).into(view)
            return true
        }
        val remote = if (m.thumbUrl.isNotBlank()) m.thumbUrl else m.fileUrl
        if (remote.isNotBlank()) {
            Glide.with(view).load(remote).into(view)
            return true
        }
        return false
    }
}
