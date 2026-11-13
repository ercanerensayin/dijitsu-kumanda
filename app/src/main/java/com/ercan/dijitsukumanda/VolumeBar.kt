package com.ercan.dijitsukumanda

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * "▮▮▮▮▯▯▯▯" biçiminde bölmeli ses çubuğu. Dokunulan / sürüklenen noktaya göre sesi ayarlar.
 */
@SuppressLint("ViewConstructor")
class VolumeBar(ctx: Context, private val accent: Int, private val track: Int, private val textColor: Int) : View(ctx) {

    var level = -1
        private set
    var max = 15
        private set

    private val density = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = track }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 15f * density
        isFakeBoldText = true
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9A9DA6")
        textSize = 12f * density
    }
    private val rect = RectF()
    private val labelH = 24f * density
    private var dragging = false

    fun set(v: Int, m: Int) {
        if (dragging) return
        max = m.coerceAtLeast(1)
        level = v
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (labelH + 44f * density).toInt())
    }

    override fun onDraw(c: Canvas) {
        val lbl = if (level < 0) "Ses: ?" else "Ses: $level / $max"
        c.drawText(lbl, 4 * density, 17 * density, text)
        val hint = "dokun veya kaydır"
        c.drawText(hint, width - sub.measureText(hint) - 4 * density, 17 * density, sub)

        // Çok bölmeli (ör. 0-100) TV'lerde bölme sayısını sınırla
        val segments = if (max <= 30) max else 25
        val gap = 3 * density
        val left = 4 * density
        val right = width - 4 * density
        val top = labelH + 8 * density
        val bottom = top + 30 * density
        val segW = (right - left - gap * (segments - 1)) / segments
        val filled = if (level < 0) 0 else (level.toFloat() / max * segments).roundToInt()
        for (i in 0 until segments) {
            val x = left + i * (segW + gap)
            // Hafifçe yükselen çubuklar
            val h = (bottom - top) * (0.45f + 0.55f * (i + 1) / segments)
            rect.set(x, bottom - h, x + segW, bottom)
            c.drawRoundRect(rect, 3 * density, 3 * density, if (i < filled) fill else empty)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                val frac = ((e.x - 4 * density) / (width - 8 * density)).coerceIn(0f, 1f)
                val v = (frac * max).roundToInt()
                if (v != level) {
                    level = v
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                    Tv.setVolume(v)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
