package com.nxd1frnt.blackoutclockdeskplugin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.time.LocalTime

class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val powerOnColor = Color.parseColor("#2E7D32")   // Темно-зелений (світло є)
    private val outageColor = Color.parseColor("#C62828")    // Червоний (відключення)
    private val hourTextColor = Color.parseColor("#9E9E9E")  // Сірий для міток
    private val tickColor = Color.parseColor("#757575")
    private val cursorColor = Color.parseColor("#FFD54F")    // Жовтий маркер поточного часу

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dpToPx(10f)
        color = hourTextColor
        textAlign = Paint.Align.CENTER
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tickColor
        strokeWidth = dpToPx(1f)
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cursorColor
        strokeWidth = dpToPx(2.5f)
        style = Paint.Style.FILL_AND_STROKE
    }

    private val barRect = RectF()
    private val segmentRect = RectF()
    private val cornerRadius = dpToPx(8f)

    private var intervals: List<Pair<LocalTime, LocalTime>> = emptyList()
    private var isToday: Boolean = true

    fun setData(intervals: List<Pair<LocalTime, LocalTime>>, isToday: Boolean) {
        this.intervals = intervals
        this.isToday = isToday
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dpToPx(44f).toInt()
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(desiredHeight, heightSize)
            else -> desiredHeight
        }

        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val barTop = dpToPx(4f)
        val barBottom = h - dpToPx(18f)
        val barHeight = barBottom - barTop

        // 1. Малюємо базову смугу (світло є - зелена)
        barRect.set(0f, barTop, w, barBottom)
        barPaint.color = powerOnColor
        canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)

        // 2. Малюємо сегменти відключень (червоні)
        barPaint.color = outageColor
        for ((start, end) in intervals) {
            val startSec = start.toSecondOfDay()
            val endSec = if (end == LocalTime.MAX || end == LocalTime.MIN) 86400 else end.toSecondOfDay()

            val startX = (startSec / 86400f) * w
            val endX = (endSec / 86400f) * w

            segmentRect.set(startX, barTop, endX, barBottom)
            // Обмежуємо малювання в межах базової смуги
            canvas.save()
            canvas.clipRect(barRect)
            canvas.drawRect(segmentRect, barPaint)
            canvas.restore()
        }

        // 3. Малюємо мітки годин: 00, 06, 12, 18, 24
        val hours = intArrayOf(0, 6, 12, 18, 24)
        val labelY = h - dpToPx(3f)

        for (hour in hours) {
            val hourX = (hour / 24f) * w
            val clampedX = when (hour) {
                0 -> dpToPx(8f)
                24 -> w - dpToPx(8f)
                else -> hourX
            }

            val text = String.format("%02d", hour)
            canvas.drawText(text, clampedX, labelY, textPaint)

            // Маленька риска на самій смузі
            if (hour in 1..23) {
                canvas.drawLine(hourX, barBottom - dpToPx(4f), hourX, barBottom, tickPaint)
            }
        }

        // 4. Якщо режим "Сьогодні" — малюємо маркер поточного часу
        if (isToday) {
            val now = LocalTime.now()
            val nowSec = now.toSecondOfDay()
            val cursorX = (nowSec / 86400f) * w

            // Вертикальна лінія курсору
            canvas.drawLine(cursorX, barTop - dpToPx(2f), cursorX, barBottom + dpToPx(2f), cursorPaint)

            // Верхня стрілочка/крапка
            canvas.drawCircle(cursorX, barTop, dpToPx(3.5f), cursorPaint)
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
