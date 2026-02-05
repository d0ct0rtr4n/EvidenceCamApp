package com.evidencecam.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
@UnstableApi
class VideoOverlayProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "VideoOverlayProcessor"
    }

    data class OverlayInfo(
        val startTimestamp: Long,
        val latitude: Double?,
        val longitude: Double?
    )

    suspend fun processVideoWithOverlay(
        inputFile: File,
        overlayInfo: OverlayInfo
    ): Result<File> {
        val outputFile = File(
            inputFile.parentFile,
            inputFile.nameWithoutExtension + "_overlay.mp4"
        )

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val dateTimeText = Instant.ofEpochMilli(overlayInfo.startTimestamp)
            .atZone(ZoneId.systemDefault())
            .format(formatter)

        val locationText = if (overlayInfo.latitude != null && overlayInfo.longitude != null) {
            val latDir = if (overlayInfo.latitude >= 0) "N" else "S"
            val lonDir = if (overlayInfo.longitude >= 0) "E" else "W"
            String.format(
                Locale.US,
                "%.6f%s %.6f%s",
                kotlin.math.abs(overlayInfo.latitude), latDir,
                kotlin.math.abs(overlayInfo.longitude), lonDir
            )
        } else {
            "GPS: N/A"
        }

        val overlayText = "$dateTimeText\n$locationText"
        Log.d(TAG, "Starting overlay processing for: ${inputFile.absolutePath}")
        Log.d(TAG, "Overlay text: $overlayText")

        return try {
            // Transformer must run on main thread
            val result = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val transformer = Transformer.Builder(context)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                                Log.d(TAG, "Video overlay processing completed: ${outputFile.absolutePath}")
                                Log.d(TAG, "Output file size: ${outputFile.length()} bytes")
                                // Delete original and rename on IO dispatcher
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        if (inputFile.delete()) {
                                            if (outputFile.renameTo(inputFile)) {
                                                continuation.resume(Result.success(inputFile))
                                            } else {
                                                continuation.resume(Result.success(outputFile))
                                            }
                                        } else {
                                            continuation.resume(Result.success(outputFile))
                                        }
                                    } catch (e: Exception) {
                                        continuation.resume(Result.success(outputFile))
                                    }
                                }
                            }

                            override fun onError(
                                composition: androidx.media3.transformer.Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                Log.e(TAG, "Video overlay processing failed", exportException)
                                outputFile.delete()
                                continuation.resume(Result.failure(exportException))
                            }
                        })
                        .build()

                    val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))
                    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                        .setEffects(createEffects(overlayText))
                        .build()

                    transformer.start(editedMediaItem, outputFile.absolutePath)

                    continuation.invokeOnCancellation {
                        transformer.cancel()
                        outputFile.delete()
                    }
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error processing video overlay", e)
            Result.failure(e)
        }
    }

    private fun createEffects(overlayText: String): Effects {
        // Create a bitmap with the text overlay for better control
        val textBitmap = createTextBitmap(overlayText)

        // Position overlay at bottom-left corner to match preview location
        // Anchor values: -1 = left/bottom, 0 = center, 1 = right/top
        // Use -1f for left edge of overlay and 1f for bottom edge
        // Background anchor: -1f for left edge, 1f for bottom edge of video frame
        // Small offset (0.98) keeps padding from edge similar to preview
        val overlaySettings = OverlaySettings.Builder()
            .setOverlayFrameAnchor(-1f, 1f)  // Anchor from bottom-left of overlay
            .setBackgroundFrameAnchor(-0.98f, 0.98f)  // Position at bottom-left with small padding
            .build()

        val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(textBitmap, overlaySettings)
        val overlayEffect = OverlayEffect(listOf(bitmapOverlay))
        return Effects(listOf(), listOf(overlayEffect))
    }

    private fun createTextBitmap(text: String): Bitmap {
        // Match preview overlay style from VideoOverlayView
        val textPaint = TextPaint().apply {
            color = Color.WHITE
            textSize = 36f  // Match preview text size
            typeface = Typeface.MONOSPACE  // Match preview (regular, not bold)
            isAntiAlias = true
            setShadowLayer(4f, 2f, 2f, Color.BLACK)  // Match preview shadow
        }

        val padding = 16f  // Match preview padding
        val lineSpacing = 8f  // Match preview line spacing

        // Measure text dimensions
        val lines = text.split("\n")
        val maxWidth = lines.maxOf { textPaint.measureText(it) }.toInt()
        val lineHeight = (textPaint.descent() - textPaint.ascent())
        val totalTextHeight = (lineHeight * lines.size) + (lineSpacing * (lines.size - 1))

        val bitmapWidth = (maxWidth + padding * 2).toInt()
        val bitmapHeight = (totalTextHeight + padding * 2).toInt()

        // Create bitmap with transparent background
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw semi-transparent background matching preview (50% alpha)
        val bgPaint = Paint().apply {
            color = Color.argb(128, 0, 0, 0)  // 50% black background - match preview
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat(), bgPaint)

        // Draw each line of text with matching padding
        var y = padding - textPaint.ascent()
        for (line in lines) {
            canvas.drawText(line, padding, y, textPaint)
            y += lineHeight + lineSpacing
        }

        return bitmap
    }

    suspend fun processVideoWithTimestampOverlay(
        inputFile: File,
        startTimestamp: Long,
        location: LocationData?
    ): Result<File> {
        return processVideoWithOverlay(
            inputFile,
            OverlayInfo(
                startTimestamp = startTimestamp,
                latitude = location?.latitude,
                longitude = location?.longitude
            )
        )
    }
}
