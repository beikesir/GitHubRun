package com.example.lanshare

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

/** 临时持有待查看的大图（避免 Intent 传大图触发 TransactionTooLargeException） */
object ImageHolder {
    var pending: Bitmap? = null
}

/**
 * 全屏图片查看：双指缩放、单指拖动、双击复位
 * 自行实现手势，不引入第三方库
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var iv: ImageView
    private val matrix = Matrix()
    private val savedMatrix = Matrix()

    private val start = PointF()
    private val mid = PointF()
    private var mode = NONE
    private var oldDist = 1f

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
        private const val MAX_SCALE = 8f
        private const val MIN_SCALE = 0.5f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)

        iv = findViewById(R.id.ivFull)
        val bmp = ImageHolder.pending
        if (bmp == null) { finish(); return }
        iv.setImageBitmap(bmp)
        iv.scaleType = ImageView.ScaleType.MATRIX
        iv.imageMatrix = matrix

        // 初始：居中并适配屏幕
        iv.post { fitCenter() }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                mode = ZOOM; savedMatrix.set(matrix); oldDist = detector.currentSpan; return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                var s = detector.currentSpan / oldDist
                val cur = currentScale()
                // 限制缩放范围
                if (cur * s > MAX_SCALE) s = MAX_SCALE / cur
                if (cur * s < MIN_SCALE) s = MIN_SCALE / cur
                matrix.set(savedMatrix)
                matrix.postScale(s, s, detector.focusX, detector.focusY)
                iv.imageMatrix = matrix
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { fitCenter(); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { finish(); return true }
        })

        iv.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)
            when (ev.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(matrix); start.set(ev.x, ev.y); mode = DRAG
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(ev); if (oldDist > 10f) {
                        savedMatrix.set(matrix); midPoint(mid, ev); mode = ZOOM
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == DRAG) {
                        matrix.set(savedMatrix)
                        matrix.postTranslate(ev.x - start.x, ev.y - start.y)
                        iv.imageMatrix = matrix
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> mode = NONE
            }
            true
        }

        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
    }

    /** 居中适配屏幕 */
    private fun fitCenter() {
        val bmp = ImageHolder.pending ?: return
        val vw = iv.width.toFloat(); val vh = iv.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        val s = minOf(vw / bw, vh / bh)
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate((vw - bw * s) / 2f, (vh - bh * s) / 2f)
        iv.imageMatrix = matrix
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        matrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun spacing(ev: MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        val x = ev.getX(0) - ev.getX(1)
        val y = ev.getY(0) - ev.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }

    private fun midPoint(p: PointF, ev: MotionEvent) {
        if (ev.pointerCount < 2) return
        p.set((ev.getX(0) + ev.getX(1)) / 2f, (ev.getY(0) + ev.getY(1)) / 2f)
    }

    override fun onDestroy() {
        ImageHolder.pending = null     // 及时释放，避免内存占用
        super.onDestroy()
    }
}
