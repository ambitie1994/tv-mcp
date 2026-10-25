package com.ambitie.tvmcp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class PlayerProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(64, 255, 255, 255)
    }
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC4F8CFF")
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val trackRect = RectF()

    private var fraction = 0f
    private var buffered = 0f
    private var showThumb = true

    fun setValues(fraction: Float, bufferedFraction: Float = 0f, showThumb: Boolean = true) {
        this.fraction = fraction.coerceIn(0f, 1f)
        this.buffered = bufferedFraction.coerceIn(0f, 1f)
        this.showThumb = showThumb
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dp(5f)
        val trackHeight = dp(4f)
        val left = radius
        val right = width - radius
        if (right <= left) return
        val centerY = height / 2f
        val top = centerY - trackHeight / 2f
        val bottom = centerY + trackHeight / 2f

        trackRect.set(left, top, right, bottom)
        canvas.drawRoundRect(trackRect, trackHeight / 2f, trackHeight / 2f, trackPaint)

        if (buffered > fraction) {
            trackRect.set(left, top, left + (right - left) * buffered, bottom)
            canvas.drawRoundRect(trackRect, trackHeight / 2f, trackHeight / 2f, bufferedPaint)
        }
        if (fraction > 0f) {
            trackRect.set(left, top, left + (right - left) * fraction, bottom)
            canvas.drawRoundRect(trackRect, trackHeight / 2f, trackHeight / 2f, progressPaint)
        }
        if (showThumb) {
            val thumbX = left + (right - left) * fraction
            canvas.drawCircle(thumbX, centerY, radius, thumbPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
