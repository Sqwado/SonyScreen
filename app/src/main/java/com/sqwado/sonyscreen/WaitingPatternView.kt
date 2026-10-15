package com.sqwado.sonyscreen

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.hypot

/**
 * Motif d’attente type « no signal » : bandes diagonales qui défilent
 * tant qu’aucun flux live view n’est disponible.
 */
class WaitingPatternView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x1A, 0x1A, 0x1A)
        style = Paint.Style.FILL
    }
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x2E, 0x2E, 0x2E)
        style = Paint.Style.STROKE
        strokeWidth = STRIPE_WIDTH_PX
    }

    private var offset = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, darkPaint)

        val pitch = STRIPE_WIDTH_PX + STRIPE_GAP_PX
        val extent = hypot(w, h)
        canvas.save()
        canvas.rotate(STRIPE_ANGLE, w / 2f, h / 2f)
        var x = -extent + (offset % pitch)
        while (x < extent) {
            canvas.drawLine(x, -extent, x, extent, stripePaint)
            x += pitch
        }
        canvas.restore()
    }

    private fun start() {
        if (animator?.isRunning == true || !isAttachedToWindow) return
        animator = ValueAnimator.ofFloat(0f, STRIPE_WIDTH_PX + STRIPE_GAP_PX).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                offset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    private companion object {
        const val STRIPE_ANGLE = -35f
        const val STRIPE_WIDTH_PX = 28f
        const val STRIPE_GAP_PX = 28f
        const val CYCLE_MS = 900L
    }
}
