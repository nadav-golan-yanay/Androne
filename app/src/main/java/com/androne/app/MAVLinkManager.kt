package com.androne.app

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MAVLinkManager(
    private var host: String = "192.168.1.1",
    private var port: Int = 14550
) {
    private var socket: DatagramSocket? = null
    private var remoteAddress: InetAddress? = null
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var isConnected = false
        private set

    fun connect(host: String, port: Int) {
        this.host = host
        this.port = port
        try {
            socket?.close()
            remoteAddress = InetAddress.getByName(host)
            socket = DatagramSocket()
            isConnected = true
            startHeartbeat()
        } catch (e: Exception) {
            isConnected = false
            throw e
        }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        socket?.close()
        socket = null
        remoteAddress = null
        isConnected = false
    }

    fun sendGps(
        lat: Double, lon: Double, altM: Float,
        hdop: Float, vdop: Float,
        speedN: Float, speedE: Float, speedD: Float,
        satellites: Int, fixType: Int
    ) {
        val packet = MAVLinkPacket.gpsInput(
            timeUsec = System.currentTimeMillis() * 1000L,
            lat = lat, lon = lon, altM = altM,
            hdop = hdop, vdop = vdop,
            speedN = speedN, speedE = speedE, speedD = speedD,
            satellitesVisible = satellites, fixType = fixType
        )
        send(packet)
    }

    fun sendImu(xg: Float, yg: Float, zg: Float,
                xa: Float, ya: Float, za: Float) {
        val packet = MAVLinkPacket.rawImu(
            timeUsec = System.currentTimeMillis() * 1000L,
            xgyro = xg, ygyro = yg, zgyro = zg,
            xacc = xa, yacc = ya, zacc = za
        )
        send(packet)
    }

    fun sendLandingTarget(
        angleXRad: Float,
        angleYRad: Float,
        sizeXRad: Float,
        sizeYRad: Float,
        distanceM: Float = 0f
    ) {
        val packet = MAVLinkPacket.landingTarget(
            timeUsec = System.currentTimeMillis() * 1000L,
            angleXRad = angleXRad,
            angleYRad = angleYRad,
            distanceM = distanceM,
            sizeXRad = sizeXRad,
            sizeYRad = sizeYRad
        )
        send(packet)
    }

    fun sendVisionPositionEstimate(
        xM: Float,
        yM: Float,
        zM: Float,
        rollRad: Float,
        pitchRad: Float,
        yawRad: Float
    ) {
        val packet = MAVLinkPacket.visionPositionEstimate(
            timeUsec = System.currentTimeMillis() * 1000L,
            xM = xM,
            yM = yM,
            zM = zM,
            rollRad = rollRad,
            pitchRad = pitchRad,
            yawRad = yawRad
        )
        send(packet)
    }

    private fun send(data: ByteArray) {
        val sock = socket ?: return
        val addr = remoteAddress ?: return
        scope.launch {
            try {
                val dp = DatagramPacket(data, data.size, addr, port)
                sock.send(dp)
            } catch (_: Exception) {}
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                send(MAVLinkPacket.heartbeat())
                delay(1000L)
            }
        }
    }
}
