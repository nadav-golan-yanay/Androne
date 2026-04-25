package com.androne.app

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.androne.app.BuildConfig
import com.androne.app.databinding.ActivityMainBinding
import kotlin.math.atan2
import kotlin.math.sqrt
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var collector: SensorCollector
    private lateinit var cameraPipeline: CameraPipeline
    private val mavlink = MAVLinkManager()
    private var streaming = false
    private val handler = Handler(Looper.getMainLooper())
    private var sessionStartMs = 0L
    private var gpsCount = 0L
    private var imuCount = 0L
    private var accCount = 0L
    private var camCount = 0L
    private var landingTargetCount = 0L
    private var visionPoseCount = 0L
    private var lastUiMetricsMs = 0L
    private var lastStudioLogMs = 0L
    private var lastGpsUiMs = 0L
    private var lastGyroUiMs = 0L
    private var lastAccUiMs = 0L
    private var lastCameraUiMs = 0L
    private var visionYawRad = 0f
    private var latestCamFps = 0f
    private var filteredGyro = GyroData()
    private var filteredAcc = AccData()
    private var sensorFilterInitialized = false

    companion object {
        private const val PERM_REQUEST = 1001
        private const val LOG_TAG = "AndroneTelemetry"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.tvAppTitle.text = resolveAppVersionLabel()

        collector = SensorCollector(this)
        cameraPipeline = CameraPipeline(this, this, binding.previewCamera)

        // Live UI updates from sensors
        collector.onGps = { gps ->
            gpsCount++
            val now = SystemClock.elapsedRealtime()
            if (now - lastGpsUiMs >= 250L) {
                lastGpsUiMs = now
                handler.post {
                    binding.tvGps.text =
                        "GPS  lat=%.6f  lon=%.6f  alt=%.1fm  fix=%d  sats=%d"
                            .format(gps.lat, gps.lon, gps.altM, gps.fixType, gps.satellites)
                }
            }
            if (streaming) {
                mavlink.sendGps(
                    gps.lat, gps.lon, gps.altM,
                    gps.hdop, gps.vdop,
                    gps.speedN, gps.speedE, gps.speedD,
                    gps.satellites, gps.fixType
                )
            }
            updateMetricsAndLog(gps, collector.latestGyro, collector.latestAcc)
        }

        collector.onGyro = {
            imuCount++
            val (displayGyro, displayAcc) = sensorDisplayValues()
            val now = SystemClock.elapsedRealtime()
            if (now - lastGyroUiMs >= 100L) {
                lastGyroUiMs = now
                handler.post {
                    binding.tvGyro.text =
                        "ORI  yaw=%.1f  pitch=%.1f  roll=%.1f  deg"
                            .format(
                                collector.latestHeadingDeg,
                                collector.latestPitchDeg,
                                collector.latestRollDeg
                            )
                }
            }
            if (streaming) {
                val rawAcc = collector.latestRawAcc
                mavlink.sendImu(
                    displayGyro.x, displayGyro.y, displayGyro.z,
                    rawAcc.x,
                    rawAcc.y,
                    rawAcc.z
                )
            }
            updateMetricsAndLog(collector.latestGps, displayGyro, displayAcc)
        }

        collector.onAcc = {
            accCount++
            val (displayGyro, displayAcc) = sensorDisplayValues()
            val now = SystemClock.elapsedRealtime()
            if (now - lastAccUiMs >= 100L) {
                lastAccUiMs = now
                handler.post {
                    binding.tvAcc.text =
                        "ACC   x=%.2f  y=%.2f  z=%.2f  m/s²"
                            .format(displayAcc.x, displayAcc.y, displayAcc.z)
                }
            }
            updateMetricsAndLog(collector.latestGps, displayGyro, displayAcc)
        }

        binding.btnConnect.setOnClickListener {
            if (!streaming) startStreaming() else stopStreaming()
        }
    }

    private fun startStreaming() {
        val host = binding.etHost.text.toString().trim().ifEmpty { "192.168.137.1" }
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 14550

        if (!hasPermissions()) {
            requestPermissions()
            return
        }

        try {
            mavlink.connect(host, port)
        } catch (e: Exception) {
            Toast.makeText(this, "Connection error: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        if (!isLocationServiceEnabled()) {
            Toast.makeText(this, "Enable phone location services for GPS updates", Toast.LENGTH_LONG).show()
        }

        collector.start()
        cameraPipeline.start(
            CameraPipelineConfig(
                enableVideoStreaming = binding.swVideoStream.isChecked,
                videoHost = binding.etVideoHost.text.toString().trim().ifEmpty { host },
                videoPort = binding.etVideoPort.text.toString().trim().toIntOrNull() ?: 5600,
                videoRotateClockwise90 = true,
                videoOsdProvider = ::buildVideoOsdLines,
                videoFrameSentListener = { fps ->
                    latestCamFps = if (latestCamFps == 0f) fps else (latestCamFps * 0.7f) + (fps * 0.3f)
                },
                enableVisionAnalysis = binding.swLandingTarget.isChecked || binding.swVisionPose.isChecked
            ),
            ::handleCameraReport
        )
        sessionStartMs = SystemClock.elapsedRealtime()
        lastUiMetricsMs = 0L
        lastStudioLogMs = 0L
        lastGpsUiMs = 0L
        lastGyroUiMs = 0L
        lastAccUiMs = 0L
        lastCameraUiMs = 0L
        gpsCount = 0L
        imuCount = 0L
        accCount = 0L
        camCount = 0L
        landingTargetCount = 0L
        visionPoseCount = 0L
        visionYawRad = 0f
        streaming = true
        binding.btnConnect.text = "Stop"
        binding.tvStatus.text = "Streaming → $host:$port"
        binding.tvMetrics.text = "Waiting for measurements..."
        binding.tvCamera.text = "Camera warming up..."
        binding.cardConnection.visibility = View.GONE
        binding.cardData.visibility = View.GONE
        Log.i(LOG_TAG, "stream_start host=$host port=$port")
    }

    private fun stopStreaming() {
        collector.stop()
        cameraPipeline.stop()
        mavlink.disconnect()
        if (streaming) {
            Log.i(
                LOG_TAG,
                "stream_stop gps=$gpsCount imu=$imuCount acc=$accCount cam=$camCount landing=$landingTargetCount vision=$visionPoseCount"
            )
        }
        streaming = false
        binding.btnConnect.text = "Connect & Stream"
        binding.tvStatus.text = "Stopped"
        binding.tvMetrics.text = "No active stream"
        binding.tvCamera.text = "Camera idle"
        binding.cardConnection.visibility = View.VISIBLE
        binding.cardData.visibility = View.VISIBLE
    }

    private fun buildVideoOsdLines(): List<String> {
        val gps = collector.latestGps
        val (gyro, acc) = sensorDisplayValues()
        val heading = resolveHeadingDeg(gps.courseDeg)
        return listOf(
            String.format(Locale.US, "FPS %.1f  CMP %03d %s", latestCamFps, heading.toInt(), headingCardinal(heading)),
            String.format(Locale.US, "GPS %.6f %.6f %.1fm", gps.lat, gps.lon, gps.altM),
            String.format(Locale.US, "FIX %d SAT %d HDOP %.1f", gps.fixType, gps.satellites, gps.hdop),
            String.format(
                Locale.US,
                "ORI Y=%.1f P=%.1f R=%.1f",
                collector.latestHeadingDeg,
                collector.latestPitchDeg,
                collector.latestRollDeg
            ),
            String.format(Locale.US, "ACC %.2f %.2f %.2f m/s^2", acc.x, acc.y, acc.z)
        )
    }

    private fun updateMetricsAndLog(gps: GpsData, gyro: GyroData, acc: AccData) {
        if (!streaming || sessionStartMs == 0L) return
        val now = SystemClock.elapsedRealtime()
        val elapsedSec = ((now - sessionStartMs).coerceAtLeast(1L)) / 1000.0

        if (now - lastUiMetricsMs >= 500L) {
            lastUiMetricsMs = now
            val gpsRate = gpsCount / elapsedSec
            val imuRate = imuCount / elapsedSec
            val accRate = accCount / elapsedSec
            val camRate = camCount / elapsedSec
            handler.post {
                binding.tvMetrics.text = String.format(
                    Locale.US,
                    "Samples: GPS=%d (%.1f/s)  IMU=%d (%.1f/s)  ACC=%d (%.1f/s)  CAM=%d (%.1f/s)",
                    gpsCount, gpsRate, imuCount, imuRate, accCount, accRate, camCount, camRate
                )
            }
        }

        if (now - lastStudioLogMs >= 1000L) {
            lastStudioLogMs = now
            Log.i(
                LOG_TAG,
                String.format(
                    Locale.US,
                    "MEAS t=%.1fs lat=%.6f lon=%.6f alt=%.1f gyroDeg=(%.1f,%.1f,%.1f) acc=(%.2f,%.2f,%.2f)",
                    elapsedSec,
                    gps.lat,
                    gps.lon,
                    gps.altM,
                    radToDeg(gyro.x),
                    radToDeg(gyro.y),
                    radToDeg(gyro.z),
                    acc.x,
                    acc.y,
                    acc.z
                )
            )
        }
    }

    private fun handleCameraReport(report: CameraFrameReport) {
        if (!streaming) return

        camCount++
        val (gyro, acc) = sensorDisplayValues()
        visionYawRad += gyro.z * report.deltaSeconds

        if (binding.swLandingTarget.isChecked && report.targetDetected) {
            mavlink.sendLandingTarget(
                angleXRad = report.angleXRad,
                angleYRad = report.angleYRad,
                sizeXRad = report.sizeXRad,
                sizeYRad = report.sizeYRad
            )
            landingTargetCount++
        }

        if (binding.swVisionPose.isChecked && report.hasPoseEstimate) {
            val rollRad = atan2(acc.y.toDouble(), acc.z.toDouble()).toFloat()
            val pitchRad = atan2(
                (-acc.x).toDouble(),
                sqrt((acc.y * acc.y + acc.z * acc.z).toDouble())
            ).toFloat()

            mavlink.sendVisionPositionEstimate(
                xM = report.poseXMeters,
                yM = report.poseYMeters,
                zM = 0f,
                rollRad = rollRad,
                pitchRad = pitchRad,
                yawRad = visionYawRad
            )
            visionPoseCount++
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastCameraUiMs >= 250L) {
            lastCameraUiMs = now
            handler.post {
                binding.tvCamera.text = String.format(
                    Locale.US,
                    "CAM fps=%.1f mean=%.0f target=%s axDeg=%.1f ayDeg=%.1f pose=(%.2f,%.2f)m q=%d",
                    latestCamFps,
                    report.meanLuma,
                    if (report.targetDetected) "yes" else "no",
                    radToDeg(report.angleXRad),
                    radToDeg(report.angleYRad),
                    report.poseXMeters,
                    report.poseYMeters,
                    report.quality
                )
            }
        }

        if (now - lastStudioLogMs >= 1000L) {
            lastStudioLogMs = now
            Log.i(
                LOG_TAG,
                String.format(
                    Locale.US,
                    "CAM fps=%.1f target=%s shift=(%d,%d) pose=(%.2f,%.2f) quality=%d",
                    latestCamFps,
                    report.targetDetected,
                    report.shiftXPixels,
                    report.shiftYPixels,
                    report.poseXMeters,
                    report.poseYMeters,
                    report.quality
                )
            )
        }
    }

    private fun hasPermissions(): Boolean {
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return hasCamera && (hasFine || hasCoarse)
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CAMERA
            ),
            PERM_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST && hasPermissions()) {
            startStreaming()
        } else {
            Toast.makeText(this, "Camera and location permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    private fun isLocationServiceEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun resolveAppVersionLabel(): String {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = packageInfo.versionName ?: BuildConfig.VERSION_NAME
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toString()
        }
        return getString(R.string.app_name_with_version, versionName, versionCode)
    }

    private fun resolveHeadingDeg(courseDeg: Float): Float {
        val absoluteHeading = collector.latestHeadingDeg
        if (!absoluteHeading.isNaN()) {
            return normalizeHeading(absoluteHeading)
        }
        if (!courseDeg.isNaN()) {
            return normalizeHeading(courseDeg)
        }
        return normalizeHeading(Math.toDegrees(visionYawRad.toDouble()).toFloat())
    }

    private fun sensorDisplayValues(): Pair<GyroData, AccData> {
        val gyro = collector.latestGyro
        val acc = collector.latestAcc
        if (!sensorFilterInitialized) {
            filteredGyro = gyro
            filteredAcc = acc
            sensorFilterInitialized = true
            return filteredGyro to filteredAcc
        }

        filteredGyro = GyroData(
            x = lowPass(filteredGyro.x, gyro.x, 0.45f),
            y = lowPass(filteredGyro.y, gyro.y, 0.45f),
            z = lowPass(filteredGyro.z, gyro.z, 0.45f)
        )
        filteredAcc = AccData(
            x = lowPass(filteredAcc.x, acc.x, 0.20f),
            y = lowPass(filteredAcc.y, acc.y, 0.20f),
            z = lowPass(filteredAcc.z, acc.z, 0.20f)
        )
        return filteredGyro to filteredAcc
    }

    private fun lowPass(previous: Float, current: Float, alpha: Float): Float {
        return previous + alpha * (current - previous)
    }

    private fun normalizeHeading(deg: Float): Float {
        var value = deg % 360f
        if (value < 0f) {
            value += 360f
        }
        return value
    }

    private fun radToDeg(valueRad: Float): Float {
        return Math.toDegrees(valueRad.toDouble()).toFloat()
    }

    private fun headingCardinal(heading: Float): String {
        val labels = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = (((heading + 22.5f) / 45f).toInt()) and 7
        return labels[index]
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }
}
