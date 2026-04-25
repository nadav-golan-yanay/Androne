package com.androne.app

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

data class GpsData(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val altM: Float = 0f,
    val courseDeg: Float = Float.NaN,
    val hdop: Float = 1f,
    val vdop: Float = 1f,
    val speedN: Float = 0f,
    val speedE: Float = 0f,
    val speedD: Float = 0f,
    val satellites: Int = 0,
    val fixType: Int = 0   // 0=no fix, 3=3D
)

data class GyroData(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
)

data class AccData(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
)

class SensorCollector(context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    var onGps: ((GpsData) -> Unit)? = null
    var onGyro: ((GyroData) -> Unit)? = null
    var onAcc: ((AccData) -> Unit)? = null

    var latestGps = GpsData()
        private set
    var latestGyro = GyroData()
        private set
    var latestAcc = AccData()
        private set
    var latestRawAcc = AccData()
        private set
    var latestHeadingDeg = Float.NaN
        private set
    var latestPitchDeg = 0f
        private set
    var latestRollDeg = 0f
        private set

    private var latestMag = floatArrayOf(0f, 0f, 0f)
    private var hasMag = false
    private var latestGyroUncal = GyroData()
    private var hasUncalibratedGyroSensor = false
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var hasLinearAccelerationSensor = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            latestGps = mapLocationToGps(loc)
            onGps?.invoke(latestGps)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    val candidate = GyroData(event.values[0], event.values[1], event.values[2])
                    latestGyro = selectBestGyro(candidate)
                    onGyro?.invoke(latestGyro)
                }
                Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                    latestGyroUncal = GyroData(event.values[0], event.values[1], event.values[2])
                    if (isLikelyAccelMasqueradingAsGyro(latestGyro)) {
                        latestGyro = latestGyroUncal
                        onGyro?.invoke(latestGyro)
                    }
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    latestRawAcc = AccData(event.values[0], event.values[1], event.values[2])
                    if (!hasLinearAccelerationSensor) {
                        latestAcc = latestRawAcc
                    }
                    updateHeadingFromAccMag()
                    onAcc?.invoke(latestAcc)
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    latestAcc = AccData(event.values[0], event.values[1], event.values[2])
                    onAcc?.invoke(latestAcc)
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    latestMag = event.values.copyOf()
                    hasMag = true
                    updateHeadingFromAccMag()
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    latestHeadingDeg = normalizeHeading(Math.toDegrees(orientation[0].toDouble()).toFloat())
                    latestPitchDeg = Math.toDegrees(orientation[1].toDouble()).toFloat()
                    latestRollDeg = Math.toDegrees(orientation[2].toDouble()).toFloat()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        // Request both GPS and network provider to improve first-fix behavior.
        requestLocationUpdatesForProvider(LocationManager.GPS_PROVIDER)
        requestLocationUpdatesForProvider(LocationManager.NETWORK_PROVIDER)
        emitLastKnownLocation(LocationManager.GPS_PROVIDER)
        emitLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        // Gyroscope
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        hasUncalibratedGyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED) != null
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        // Accelerometer
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        hasLinearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        locationManager.removeUpdates(locationListener)
        sensorManager.unregisterListener(sensorListener)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdatesForProvider(provider: String) {
        if (locationManager.isProviderEnabled(provider)) {
            locationManager.requestLocationUpdates(provider, 500L, 0.5f, locationListener)
        }
    }

    @SuppressLint("MissingPermission")
    private fun emitLastKnownLocation(provider: String) {
        if (!locationManager.isProviderEnabled(provider)) {
            return
        }
        val last = locationManager.getLastKnownLocation(provider) ?: return
        latestGps = mapLocationToGps(last)
        onGps?.invoke(latestGps)
    }

    private fun mapLocationToGps(loc: Location): GpsData {
        val bearingRad = Math.toRadians(loc.bearing.toDouble())
        val speed = loc.speed
        val satellites = loc.extras?.getInt("satellites", 0) ?: 0
        val fixType = when {
            loc.provider == LocationManager.GPS_PROVIDER && loc.hasAltitude() -> 3
            loc.provider == LocationManager.GPS_PROVIDER -> 2
            loc.hasAccuracy() -> 2
            else -> 0
        }

        return GpsData(
            lat = loc.latitude,
            lon = loc.longitude,
            altM = loc.altitude.toFloat(),
            courseDeg = if (loc.hasBearing()) loc.bearing else Float.NaN,
            hdop = if (loc.hasAccuracy()) loc.accuracy / 10f else 1f,
            vdop = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters / 10f else 1f,
            speedN = (speed * cos(bearingRad)).toFloat(),
            speedE = (speed * sin(bearingRad)).toFloat(),
            speedD = 0f,
            satellites = satellites,
            fixType = fixType
        )
    }

    private fun updateHeadingFromAccMag() {
        if (!hasMag) {
            return
        }
        val acc = floatArrayOf(latestRawAcc.x, latestRawAcc.y, latestRawAcc.z)
        if (SensorManager.getRotationMatrix(rotationMatrix, null, acc, latestMag)) {
            SensorManager.getOrientation(rotationMatrix, orientation)
            latestHeadingDeg = normalizeHeading(Math.toDegrees(orientation[0].toDouble()).toFloat())
        }
    }

    private fun selectBestGyro(candidate: GyroData): GyroData {
        return if (isLikelyAccelMasqueradingAsGyro(candidate)) {
            if (hasUncalibratedGyroSensor) latestGyroUncal else latestGyro
        } else {
            candidate
        }
    }

    private fun isLikelyAccelMasqueradingAsGyro(gyro: GyroData): Boolean {
        val gyroNorm = norm(gyro.x, gyro.y, gyro.z)
        val rawAccNorm = norm(latestRawAcc.x, latestRawAcc.y, latestRawAcc.z)
        val similarToGravity = rawAccNorm in 7f..13f && gyroNorm in 7f..13f
        val closeMagnitude = kotlin.math.abs(gyroNorm - rawAccNorm) < 1.8f
        return similarToGravity && closeMagnitude
    }

    private fun norm(x: Float, y: Float, z: Float): Float {
        return sqrt(x * x + y * y + z * z)
    }

    private fun normalizeHeading(value: Float): Float {
        var normalized = value % 360f
        if (normalized < 0f) {
            normalized += 360f
        }
        return normalized
    }
}
