package com.camera.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class SevenSegmentTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var displayText = "00:00:00"

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    // Normal text के करीब छोटा calculator-style size
    private val digitWidth = 7.5f
    private val digitHeight = 13.5f
    private val segmentThickness = 1.5f
    private val digitGap = 1.5f
    private val colonWidth = 3f

    fun setTextValue(text: String) {
        displayText = text
        requestLayout()
        invalidate()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        val scale = resources.displayMetrics.scaledDensity

        var totalWidth = 0f

        for (char in displayText) {
            totalWidth += if (char == ':') {
                colonWidth * scale + digitGap * scale
            } else {
                digitWidth * scale + digitGap * scale
            }
        }

        val width = totalWidth.toInt()
        val height = (digitHeight * scale).toInt()

        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scale = resources.displayMetrics.scaledDensity

        val width = digitWidth * scale
        val height = digitHeight * scale
        val thickness = segmentThickness * scale
        val gap = digitGap * scale
        val colonW = colonWidth * scale

        var x = 0f

        for (char in displayText) {

            if (char == ':') {

                val centerX = x + colonW / 2f

                canvas.drawCircle(
                    centerX,
                    height * 0.32f,
                    thickness / 2f,
                    paint
                )

                canvas.drawCircle(
                    centerX,
                    height * 0.68f,
                    thickness / 2f,
                    paint
                )

                x += colonW + gap
                continue
            }

            drawDigit(
                canvas,
                char,
                x,
                0f,
                width,
                height,
                thickness
            )

            x += width + gap
        }
    }

    private fun drawDigit(
        canvas: Canvas,
        digit: Char,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        thickness: Float
    ) {

        val segments = when (digit) {

            '0' -> setOf('a', 'b', 'c', 'd', 'e', 'f')

            '1' -> setOf('b', 'c')

            '2' -> setOf('a', 'b', 'g', 'e', 'd')

            '3' -> setOf('a', 'b', 'c', 'd', 'g')

            '4' -> setOf('f', 'g', 'b', 'c')

            '5' -> setOf('a', 'f', 'g', 'c', 'd')

            '6' -> setOf('a', 'f', 'g', 'e', 'c', 'd')

            '7' -> setOf('a', 'b', 'c')

            '8' -> setOf(
                'a', 'b', 'c', 'd',
                'e', 'f', 'g'
            )

            '9' -> setOf(
                'a', 'b', 'c',
                'd', 'f', 'g'
            )

            else -> emptySet()
        }

        val half = thickness / 2f

        // Top
        if ('a' in segments) {
            drawHorizontal(
                canvas,
                left + thickness,
                top + half,
                left + width - thickness,
                thickness
            )
        }

        // Upper right
        if ('b' in segments) {
            drawVertical(
                canvas,
                left + width - half,
                top + thickness,
                top + height / 2f - thickness / 2f,
                thickness
            )
        }

        // Lower right
        if ('c' in segments) {
            drawVertical(
                canvas,
                left + width - half,
                top + height / 2f + thickness / 2f,
                top + height - thickness,
                thickness
            )
        }

        // Bottom
        if ('d' in segments) {
            drawHorizontal(
                canvas,
                left + thickness,
                top + height - half,
                left + width - thickness,
                thickness
            )
        }

        // Lower left
        if ('e' in segments) {
            drawVertical(
                canvas,
                left + half,
                top + height / 2f + thickness / 2f,
                top + height - thickness,
                thickness
            )
        }

        // Upper left
        if ('f' in segments) {
            drawVertical(
                canvas,
                left + half,
                top + thickness,
                top + height / 2f - thickness / 2f,
                thickness
            )
        }

        // Middle
        if ('g' in segments) {
            drawHorizontal(
                canvas,
                left + thickness,
                top + height / 2f,
                left + width - thickness,
                thickness
            )
        }
    }

    private fun drawHorizontal(
        canvas: Canvas,
        x1: Float,
        y: Float,
        x2: Float,
        thickness: Float
    ) {
        val rect = RectF(
            x1,
            y - thickness / 2f,
            x2,
            y + thickness / 2f
        )

        canvas.drawRoundRect(
            rect,
            thickness / 2f,
            thickness / 2f,
            paint
        )
    }

    private fun drawVertical(
        canvas: Canvas,
        x: Float,
        y1: Float,
        y2: Float,
        thickness: Float
    ) {
        val rect = RectF(
            x - thickness / 2f,
            y1,
            x + thickness / 2f,
            y2
        )

        canvas.drawRoundRect(
            rect,
            thickness / 2f,
            thickness / 2f,
            paint
        )
    }
}
