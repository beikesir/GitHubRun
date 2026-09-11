package com.example.lanshare

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

/**
 * 临时持有待查看的图片序列（避免 Intent 传大图触发 TransactionTooLargeException）。
 *
 * 注意这里保存的是消息引用、不是解码后的 Bitmap：
 * 一次传多张图时若把大图都留在内存里，很容易 OOM；
 * 改成按需加载后，内存里始终只有当前这一张。
 */
object ImageHolder {
    /** 当前聊天里的全部图片消息（按时间正序） */
    var images: List<Message> = emptyList()
    /** 起始位置 */
    var index: Int = 0
}

/**
 * 全屏查看：图片按原比例完整显示，单指左右滑动切换本聊天的其他图片，单指点击关闭。
 *
 * 交互取舍：
 *   去掉了原先的「单指拖动平移」——图片现在是 fitCenter 完整显示，
 *   不存在「放大后看不到另一半」的问题，拖动不再必要；
 *   单指手势统一给切换，左右滑即可翻阅同一频道里前后的图片。
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var iv: ImageView
    private lateinit var tvHint: TextView
    private var images: List<Message> = emptyList()
    private var index = 0
    private lateinit var detector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        iv = findViewById(R.id.ivFull)
        tvHint = findViewById(R.id.tvHint)

        images = ImageHolder.images
        if (images.isEmpty()) { finish(); return }
        index = ImageHolder.index.coerceIn(0, images.size - 1)

        detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                val s = e1 ?: return false
                val dx = e2.x - s.x
                val dy = e2.y - s.y
                // 横向位移够大、且明显大于纵向，才判定为切换（避免与竖向滚动误触）
                if (abs(dx) > 60 && abs(dx) > abs(dy) * 1.4f) {
                    show(if (dx < 0) index + 1 else index - 1)
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                finish()
                return true
            }
        })
        iv.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }

        show(index)
    }

    /** 显示第 i 张；越界时夹到边界，滑到头不会空白 */
    private fun show(i: Int) {
        if (images.isEmpty()) return
        index = i.coerceIn(0, images.size - 1)
        val m = images[index]

        iv.setImageDrawable(null)
        // 与列表共用同一套优先级：本机留底 > 中继直链 > 内联 base64
        if (!ImageLoader.load(iv, m)) {
            val bmp = m.bitmap
                ?: ImageUtils.decodeForDisplay(m.content, 2048).also { m.bitmap = it }
            iv.setImageBitmap(bmp)
        }

        tvHint.text = if (images.size > 1)
            "${index + 1} / ${images.size} · 左右滑动切换"
        else "单指点击关闭"
    }

    override fun onDestroy() {
        // 仅在真正退出时释放引用。
        // 旋转屏幕也会走 onDestroy→onCreate，若无条件清空，
        // 重建后取到空列表会直接 finish()，表现为「转个屏就黑屏退出」。
        if (isFinishing) {
            ImageHolder.images = emptyList()
            ImageHolder.index = 0
        }
        super.onDestroy()
    }
}
