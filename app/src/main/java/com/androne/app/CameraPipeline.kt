package com.androne.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CameraPipelineConfig(
    val enableVideoStreaming: Boolean = false,
    val videoHost: String = "192.168.137.1",
    val videoPort: Int = 5600,
    val videoJpegQuality: Int = 32,
    val videoMinFrameIntervalMs: Long = 80L,
    val videoRotateClockwise90: Boolean = true,
    val videoOsdProvider: (() -> List<String>)? = null,
    val videoFrameSentListener: ((Float) -> Unit)? = null,
    val enableVisionAnalysis: Boolean = true,
    val analysisWidth: Int = 48,
    val analysisHeight: Int = 36,
    val horizontalFovRad: Float = 1.1868f,
    val verticalFovRad: Float = 0.8727f
)

data class CameraFrameReport(
    val analysisFrameRate: Float,
    val deltaSeconds: Float,
    val meanLuma: Float,
    val targetDetected: Boolean,
    val angleXRad: Float,
    val angleYRad: Float,
    val sizeXRad: Float,
    val sizeYRad: Float,
    val shiftXPixels: Int,
    val shiftYPixels: Int,
    val poseXMeters: Float,
    val poseYMeters: Float,
    val quality: Int,
    val hasPoseEstimate: Boolean
)

class CameraPipeline(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeConfig = CameraPipelineConfig()
    private var streamer: JpegUdpStreamer? = null
    private var onFrameReport: ((CameraFrameReport) -> Unit)? = null
    private val analyzer = VisionFrameAnalyzer()
    private var lastNoVisionReportAtMs = 0L

    fun start(config: CameraPipelineConfig, onFrameReport: (CameraFrameReport) -> Unit) {
        activeConfig = config
        this.onFrameReport = onFrameReport
        analyzer.reset()
        lastNoVisionReportAtMs = 0L
        streamer?.close()
        streamer = if (config.enableVideoStreaming) {
            JpegUdpStreamer(
                host = config.videoHost,
                port = config.videoPort,
                jpegQuality = config.videoJpegQuality,
                minFrameIntervalMs = config.videoMinFrameIntervalMs,
                rotateClockwise90 = config.videoRotateClockwise90,
                osdProvider = config.videoOsdProvider,
                frameSentListener = config.videoFrameSentListener
            )
        } else {
            null
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                cameraProvider = providerFuture.get()
                bindUseCases()
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun stop() {
        cameraProvider?.unbindAll()
        analyzer.reset()
        streamer?.close()
        streamer = null
        onFrameReport = null
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(resolutionSelector)
            .build()

        analysis.setAnalyzer(cameraExecutor) { image ->
            processFrame(image)
        }

        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis
        )
    }

    private fun processFrame(image: ImageProxy) {
        try {
            streamer?.sendFrame(image)
            val report = if (activeConfig.enableVisionAnalysis) {
                analyzer.analyze(image, activeConfig)
            } else {
                val now = SystemClock.elapsedRealtime()
                val deltaSeconds = if (lastNoVisionReportAtMs == 0L) 0f
                else ((now - lastNoVisionReportAtMs).coerceAtLeast(1L) / 1000f)
                lastNoVisionReportAtMs = now
                CameraFrameReport(
                    analysisFrameRate = 0f,
                    deltaSeconds = deltaSeconds,
                    meanLuma = 0f,
                    targetDetected = false,
                    angleXRad = 0f,
                    angleYRad = 0f,
                    sizeXRad = 0f,
                    sizeYRad = 0f,
                    shiftXPixels = 0,
                    shiftYPixels = 0,
                    poseXMeters = 0f,
                    poseYMeters = 0f,
                    quality = 0,
                    hasPoseEstimate = false
                )
            }
            onFrameReport?.invoke(report)
        } finally {
            image.close()
        }
    }
}

private class VisionFrameAnalyzer {
    private var previousFrame: IntArray? = null
    private var previousAtMs = 0L
    private var poseX = 0f
    private var poseY = 0f

    fun reset() {
        previousFrame = null
        previousAtMs = 0L
        poseX = 0f
        poseY = 0f
    }

    fun analyze(image: ImageProxy, config: CameraPipelineConfig): CameraFrameReport {
        val now = SystemClock.elapsedRealtime()
        val grayscale = sampleLuma(image, config.analysisWidth, config.analysisHeight)
        val deltaSeconds = if (previousAtMs == 0L) 0f else ((now - previousAtMs).coerceAtLeast(1L) / 1000f)
        val frameRate = if (deltaSeconds > 0f) 1f / deltaSeconds else 0f
        val mean = grayscale.average().toFloat()

        val target = detectTarget(grayscale, config.analysisWidth, config.analysisHeight, mean, config)
        val shift = estimateShift(previousFrame, grayscale, config.analysisWidth, config.analysisHeight)
        val hasPoseEstimate = previousFrame != null && deltaSeconds > 0f

        if (hasPoseEstimate) {
            poseX += shift.first * 0.015f
            poseY += shift.second * 0.015f
        }

        previousFrame = grayscale
        previousAtMs = now

        return CameraFrameReport(
            analysisFrameRate = frameRate,
            deltaSeconds = deltaSeconds,
            meanLuma = mean,
            targetDetected = target.detected,
            angleXRad = target.angleXRad,
            angleYRad = target.angleYRad,
            sizeXRad = target.sizeXRad,
            sizeYRad = target.sizeYRad,
            shiftXPixels = shift.first,
            shiftYPixels = shift.second,
            poseXMeters = poseX,
            poseYMeters = poseY,
            quality = shift.third,
            hasPoseEstimate = hasPoseEstimate
        )
    }

    private fun detectTarget(
        grayscale: IntArray,
        width: Int,
        height: Int,
        meanLuma: Float,
        config: CameraPipelineConfig
    ): TargetDetection {
        val threshold = max(160, meanLuma.toInt() + 30)
        var count = 0
        var sumX = 0f
        var sumY = 0f
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val value = grayscale[row + x]
                if (value >= threshold) {
                    count++
                    sumX += x
                    sumY += y
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                }
            }
        }

        if (count < 24) {
            return TargetDetection(false, 0f, 0f, 0f, 0f)
        }

        val centerX = sumX / count
        val centerY = sumY / count
        val normX = centerX / (width - 1).coerceAtLeast(1)
        val normY = centerY / (height - 1).coerceAtLeast(1)
        val sizeXNorm = (maxX - minX + 1).toFloat() / width
        val sizeYNorm = (maxY - minY + 1).toFloat() / height

        return TargetDetection(
            detected = true,
            angleXRad = (normX - 0.5f) * config.horizontalFovRad,
            angleYRad = (normY - 0.5f) * config.verticalFovRad,
            sizeXRad = sizeXNorm * config.horizontalFovRad,
            sizeYRad = sizeYNorm * config.verticalFovRad
        )
    }

    private fun estimateShift(
        previous: IntArray?,
        current: IntArray,
        width: Int,
        height: Int
    ): Triple<Int, Int, Int> {
        if (previous == null) {
            return Triple(0, 0, 0)
        }

        var bestDx = 0
        var bestDy = 0
        var bestScore = Long.MAX_VALUE
        val maxShift = 4

        for (dy in -maxShift..maxShift) {
            for (dx in -maxShift..maxShift) {
                val xStart = max(0, dx)
                val xEnd = min(width, width + dx)
                val yStart = max(0, dy)
                val yEnd = min(height, height + dy)
                if (xStart >= xEnd || yStart >= yEnd) {
                    continue
                }

                var score = 0L
                for (y in yStart until yEnd) {
                    val currentRow = y * width
                    val previousRow = (y - dy) * width
                    for (x in xStart until xEnd) {
                        score += abs(current[currentRow + x] - previous[previousRow + (x - dx)])
                    }
                }

                if (score < bestScore) {
                    bestScore = score
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        val normalizedError = bestScore.toFloat() / (width * height * 255f)
        val quality = ((255f * (1f - normalizedError)).coerceIn(0f, 255f)).toInt()
        return Triple(bestDx, bestDy, quality)
    }

    private fun sampleLuma(image: ImageProxy, targetWidth: Int, targetHeight: Int): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val output = IntArray(targetWidth * targetHeight)

        for (y in 0 until targetHeight) {
            val sourceY = y * height / targetHeight
            for (x in 0 until targetWidth) {
                val sourceX = x * width / targetWidth
                val index = sourceY * rowStride + sourceX
                output[y * targetWidth + x] = buffer.get(index).toInt() and 0xFF
            }
        }

        return output
    }

    private data class TargetDetection(
        val detected: Boolean,
        val angleXRad: Float,
        val angleYRad: Float,
        val sizeXRad: Float,
        val sizeYRad: Float
    )
}

private class JpegUdpStreamer(
    host: String,
    private val port: Int,
    private val jpegQuality: Int,
    private val minFrameIntervalMs: Long,
    private val rotateClockwise90: Boolean,
    private val osdProvider: (() -> List<String>)?,
    private val frameSentListener: ((Float) -> Unit)?
) {
    private val socket = DatagramSocket()
    private val address = InetAddress.getByName(host)
    private var frameId = 0
    private var lastSentAtMs = 0L
    private var previousSentAtMs = 0L

    fun sendFrame(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSentAtMs < minFrameIntervalMs) {
            return
        }
        previousSentAtMs = lastSentAtMs
        lastSentAtMs = now

        val jpeg = image.toJpeg(
            quality = jpegQuality,
            rotateClockwise90 = rotateClockwise90,
            osdLines = osdProvider?.invoke().orEmpty()
        )
        if (jpeg.isEmpty()) {
            return
        }

        frameId += 1
        val maxPayload = 1400
        val totalChunks = ((jpeg.size + maxPayload - 1) / maxPayload).coerceAtLeast(1)
        var offset = 0

        for (chunkIndex in 0 until totalChunks) {
            val remaining = jpeg.size - offset
            val chunkSize = min(maxPayload, remaining)
            val packet = ByteArray(16 + chunkSize)
            val header = ByteBuffer.wrap(packet)
            header.put('A'.code.toByte())
            header.put('D'.code.toByte())
            header.put('R'.code.toByte())
            header.put('N'.code.toByte())
            header.putInt(frameId)
            header.putShort(totalChunks.toShort())
            header.putShort(chunkIndex.toShort())
            header.putShort(image.width.toShort())
            header.putShort(image.height.toShort())
            System.arraycopy(jpeg, offset, packet, 16, chunkSize)
            socket.send(DatagramPacket(packet, packet.size, address, port))
            offset += chunkSize
        }

        val deltaSeconds = if (previousSentAtMs == 0L) 0f else ((lastSentAtMs - previousSentAtMs).coerceAtLeast(1L) / 1000f)
        frameSentListener?.invoke(if (deltaSeconds > 0f) 1f / deltaSeconds else 0f)
    }

    fun close() {
        socket.close()
    }

    private fun ImageProxy.toJpeg(
        quality: Int,
        rotateClockwise90: Boolean,
        osdLines: List<String>
    ): ByteArray {
        val nv21 = yuv420888ToNv21(this)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val rawStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, rawStream)
        val rawJpeg = rawStream.toByteArray()
        if (!rotateClockwise90 && osdLines.isEmpty()) {
            return rawJpeg
        }

        val decoded = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size) ?: return rawJpeg
        val rotated = if (rotateClockwise90) {
            val matrix = Matrix().apply { postRotate(90f) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        } else {
            decoded
        }
        if (rotated !== decoded) {
            decoded.recycle()
        }

        val mutableBitmap = rotated.copy(Bitmap.Config.ARGB_8888, true)
        if (mutableBitmap !== rotated) {
            rotated.recycle()
        }

        if (osdLines.isNotEmpty()) {
            drawOsd(mutableBitmap, osdLines)
        }

        val finalStream = ByteArrayOutputStream()
        mutableBitmap.compress(Bitmap.CompressFormat.JPEG, quality, finalStream)
        mutableBitmap.recycle()
        return finalStream.toByteArray()
    }

    private fun drawOsd(bitmap: Bitmap, lines: List<String>) {
        val canvas = Canvas(bitmap)
        val textSize = (bitmap.height / 28f).coerceIn(18f, 34f)
        val padding = (textSize * 0.45f).toInt()
        val lineGap = (textSize * 1.3f)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            this.textSize = textSize
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(130, 0, 0, 0)
            style = Paint.Style.FILL
        }

        val maxTextWidth = lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f
        val panelWidth = (maxTextWidth + padding * 2).toInt()
        val panelHeight = (lineGap * lines.size + padding * 1.2f).toInt()
        val left = padding
        val top = padding
        val right = min(bitmap.width - padding, left + panelWidth)
        val bottom = min(bitmap.height - padding, top + panelHeight)
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), bgPaint)

        var baselineY = top + padding + textSize
        for (line in lines) {
            canvas.drawText(line, (left + padding).toFloat(), baselineY, textPaint)
            baselineY += lineGap
        }
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val out = ByteArray(width * height + (width * height / 2))
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        var outputOffset = 0
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (col in 0 until width) {
                out[outputOffset++] = yBuffer.get(rowStart + col)
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            val uRowStart = row * uPlane.rowStride
            val vRowStart = row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                out[outputOffset++] = vBuffer.get(vRowStart + col * vPlane.pixelStride)
                out[outputOffset++] = uBuffer.get(uRowStart + col * uPlane.pixelStride)
            }
        }

        return out
    }
}