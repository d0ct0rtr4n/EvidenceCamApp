package com.evidencecam.app.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.evidencecam.app.util.LocationData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class VideoOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.MONOSPACE
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val backgroundPaint = Paint().apply {
        color = Color.argb(128, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private var currentDateTime: String = ""
    private var currentLocation: LocationData? = null
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private var padding = 16f
    private var lineSpacing = 8f

    fun updateDateTime(timestamp: Long) {
        currentDateTime = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(dateFormatter)
        invalidate()
    }

    fun updateLocation(location: LocationData?) {
        currentLocation = location
        invalidate()
    }

    fun setTextSize(size: Float) {
        textPaint.textSize = size
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (currentDateTime.isEmpty() && currentLocation == null) return

        val lines = mutableListOf<String>()

        if (currentDateTime.isNotEmpty()) {
            lines.add(currentDateTime)
        }

        currentLocation?.let { loc ->
            lines.add(loc.formatForOverlay())
        } ?: run {
            lines.add("GPS: Acquiring...")
        }

        if (lines.isEmpty()) return

        // Calculate text metrics
        val textHeight = textPaint.fontMetrics.let { it.descent - it.ascent }
        val totalTextHeight = (textHeight * lines.size) + (lineSpacing * (lines.size - 1))

        // Position at bottom of view
        val maxWidth = lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f
        val bgWidth = maxWidth + (padding * 2)
        val bgHeight = totalTextHeight + (padding * 2)
        val bgLeft = padding
        val bgTop = height - bgHeight - padding

        // Draw semi-transparent background
        canvas.drawRect(bgLeft, bgTop, bgLeft + bgWidth, bgTop + bgHeight, backgroundPaint)

        // Draw text lines
        var yPos = bgTop + padding - textPaint.fontMetrics.ascent
        for (line in lines) {
            canvas.drawText(line, bgLeft + padding, yPos, textPaint)
            yPos += textHeight + lineSpacing
        }
    }

    companion object {
        private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun formatOverlayText(timestamp: Long, location: LocationData?): String {
            val dateTime = Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(dateFormatter)
            val locationStr = location?.formatForOverlay() ?: "GPS: N/A"
            return "$dateTime\n$locationStr"
        }
    }
}
