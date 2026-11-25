package com.vladimir.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import kotlin.math.sin

/** Lightweight animated local fireplace screensaver. No video service or internet stream is required. */
internal class FireplaceView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0L
    private val frame = object : Runnable {
        override fun run() {
            tick += 1
            invalidate()
            if (isAttachedToWindow) handler.postDelayed(this, 70L)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { (context as? MainActivity)?.onBackPressed() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        handler.post(frame)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            (context as? MainActivity)?.onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Color.rgb(18, 22, 34))
        val brick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(49, 40, 42); style = Paint.Style.STROKE; strokeWidth = h * .003f }
        val brickStep = h * .075f
        var y = 0f
        while (y < h * .84f) {
            canvas.drawLine(0f, y, w, y, brick)
            val offset = if ((y / brickStep).toInt() % 2 == 0) 0f else w * .09f
            var x = offset
            while (x < w) { canvas.drawLine(x, y, x, y + brickStep, brick); x += w * .18f }
            y += brickStep
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, h * .1f, 0f, h * .86f,
            intArrayOf(Color.rgb(94, 62, 51), Color.rgb(52, 38, 39), Color.rgb(24, 26, 36)), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(w * .12f, h * .17f, w * .88f, h * .81f), h * .025f, h * .025f, paint)
        paint.shader = null
        paint.color = Color.rgb(106, 68, 48)
        canvas.drawRoundRect(RectF(w * .07f, h * .77f, w * .93f, h * .83f), h * .018f, h * .018f, paint)
        paint.color = Color.rgb(33, 24, 27)
        canvas.drawRoundRect(RectF(w * .21f, h * .36f, w * .79f, h * .78f), h * .04f, h * .04f, paint)
        paint.color = Color.rgb(16, 16, 20)
        canvas.drawRoundRect(RectF(w * .25f, h * .42f, w * .75f, h * .78f), h * .03f, h * .03f, paint)
        val fireLeft = w * .3f
        val fireRight = w * .7f
        val floor = h * .75f
        for (i in 0 until 7) {
            val phase = tick * .13f + i * 1.91f
            val center = fireLeft + (fireRight - fireLeft) * (i / 6f)
            val flicker = .8f + .2f * sin(phase)
            val flameHeight = h * (.23f + .045f * sin(phase * 1.17f))
            val half = w * (.035f + .016f * flicker)
            val path = Path().apply {
                moveTo(center - half, floor)
                cubicTo(center - half * 1.35f, floor - flameHeight * .42f,
                    center + half * .5f, floor - flameHeight * .64f,
                    center + half * .15f, floor - flameHeight)
                cubicTo(center + half * .75f, floor - flameHeight * .58f,
                    center + half * 1.25f, floor - flameHeight * .26f,
                    center + half, floor)
                close()
            }
            paint.shader = LinearGradient(center, floor, center, floor - flameHeight,
                intArrayOf(Color.rgb(255, 57, 11), Color.rgb(255, 133, 13), Color.rgb(255, 223, 87)), null, Shader.TileMode.CLAMP)
            canvas.drawPath(path, paint)
        }
        paint.shader = null
        paint.color = Color.rgb(102, 56, 34)
        canvas.save(); canvas.rotate(-8f, w * .5f, floor)
        canvas.drawRoundRect(RectF(w * .34f, floor - h * .045f, w * .64f, floor + h * .012f), h * .025f, h * .025f, paint)
        canvas.restore()
        canvas.save(); canvas.rotate(8f, w * .5f, floor)
        canvas.drawRoundRect(RectF(w * .36f, floor - h * .065f, w * .66f, floor - h * .01f), h * .025f, h * .025f, paint)
        canvas.restore()
        paint.color = 0xCCFFFFFF.toInt()
        paint.textSize = h * .028f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("ВладиМир TV Онлайн · Нажми Назад, чтобы выйти", w * .5f, h * .95f, paint)
    }
}
