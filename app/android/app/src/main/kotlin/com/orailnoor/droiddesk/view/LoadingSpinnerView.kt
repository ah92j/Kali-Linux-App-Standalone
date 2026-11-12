package com.orailnoor.droiddesk.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * Material-style spinner: an arc that rotates while its length grows and shrinks.
 *
 * Driven by frame time rather than ValueAnimator so it keeps moving even when the system
 * "animator duration scale" is off, which froze the stock ProgressBar on some phones.
 */
class LoadingSpinnerView(
    context: Context,
    private val arcColor: Int,
    trackColor: Int,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val stroke = 4.5f * density
    private val startTime = SystemClock.uptimeMillis()
    private val bounds = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = trackColor
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = arcColor
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = stroke / 2 + 1
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(bounds, trackPaint)

        val t = (SystemClock.uptimeMillis() - startTime) / 1000f
        // One full rotation every ~3 s; the arc breathes between 30° and 270° every 4 s.
        val rotation = (t * 120f) % 360f
        val phase = (t % 4f) / 4f
        val sweep = 30f + 240f * ((1 - kotlin.math.cos(phase * 2 * PI)) / 2).toFloat()
        val start = rotation + (phase * 360f) * 0.6f
        canvas.drawArc(bounds, start, sweep, false, arcPaint)

        if (isShown) postInvalidateOnAnimation()
    }
}

/** Gentle float + pulse for the logo, also frame-time driven. */
class FloatingLogoAnimator(private val target: View) : Runnable {
    private val startTime = SystemClock.uptimeMillis()
    private val lift = 6f * target.resources.displayMetrics.density
    private var running = false

    fun start() {
        if (running) return
        running = true
        target.postOnAnimation(this)
    }

    fun stop() {
        running = false
        target.removeCallbacks(this)
    }

    override fun run() {
        if (!running) return
        val t = (SystemClock.uptimeMillis() - startTime) / 1000.0
        val wave = sin(t * 2 * PI / 5.0).toFloat()
        target.translationY = -lift * (wave + 1) / 2
        val scale = 1f + 0.03f * wave
        target.scaleX = scale
        target.scaleY = scale
        target.postOnAnimation(this)
    }
}
