package com.androne.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal MAVLink v1 packet encoder.
 * Supports: HEARTBEAT (0), GPS_INPUT (232), RAW_IMU (27),
 * LANDING_TARGET (149), VISION_POSITION_ESTIMATE (102)
 */
object MAVLinkPacket {

    private const val MAVLINK_STX: Byte = 0xFE.toByte()
    private const val SYSTEM_ID: Byte = 255.toByte()   // Typical GCS sysid
    private const val COMPONENT_ID: Byte = 190.toByte() // MAV_COMP_ID_MISSIONPLANNER / GCS-like sender id
    private var seq: Byte = 0

    // -------------------------------------------------------------------------
    // HEARTBEAT – msg id 0, length 9
    // -------------------------------------------------------------------------
    fun heartbeat(): ByteArray {
        val payload = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0)          // custom_mode
            put(6)             // type: MAV_TYPE_GCS
            put(8)             // autopilot: MAV_AUTOPILOT_INVALID
            put(0)             // base_mode
            put(4)             // system_status: MAV_STATE_ACTIVE
            put(3)             // mavlink_version
        }.array()
        return buildPacket(0, payload)
    }

    // -------------------------------------------------------------------------
    // GPS_INPUT – msg id 232, length 63
    // Fields we fill: time_usec, gps_id, fix_type, lat, lon, alt,
    //                 hdop, vdop, vn, ve, vd, speed_accuracy,
    //                 horiz_accuracy, vert_accuracy, ignore_flags, satellites_visible
    // -------------------------------------------------------------------------
    fun gpsInput(
        timeUsec: Long,
        lat: Double,        // degrees
        lon: Double,        // degrees
        altM: Float,        // metres MSL
        hdop: Float,
        vdop: Float,
        speedN: Float,      // m/s north
        speedE: Float,      // m/s east
        speedD: Float,      // m/s down
        satellitesVisible: Int,
        fixType: Int        // 0=no fix, 3=3D
    ): ByteArray {
        val gpsTime = gpsWeekTime(timeUsec)
        val payload = ByteBuffer.allocate(63).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(timeUsec)           // time_usec  [us]
            putInt(gpsTime.first)       // time_week_ms
            putInt((lat * 1e7).toInt()) // lat        [degE7]
            putInt((lon * 1e7).toInt()) // lon        [degE7]
            putFloat(altM)              // alt        [m]
            putFloat(hdop)              // hdop
            putFloat(vdop)              // vdop
            putFloat(speedN)            // vn
            putFloat(speedE)            // ve
            putFloat(speedD)            // vd
            putFloat(0f)                // speed_accuracy
            putFloat(hdop)              // horiz_accuracy
            putFloat(vdop)              // vert_accuracy
            putShort(0)                 // ignore_flags (use all fields)
            putShort(gpsTime.second.toShort())
            put(0)                      // gps_id
            put(fixType.toByte())       // fix_type
            put(satellitesVisible.toByte())
        }.array()
        return buildPacket(232, payload)
    }

    // -------------------------------------------------------------------------
    // RAW_IMU – msg id 27, length 26
    // Gyro values in mrad/s (scaled x1000 from rad/s)
    // -------------------------------------------------------------------------
    fun rawImu(
        timeUsec: Long,
        xgyro: Float,   // rad/s
        ygyro: Float,
        zgyro: Float,
        xacc: Float,    // m/s² (we send 0)
        yacc: Float,
        zacc: Float
    ): ByteArray {
        val payload = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(timeUsec)
            // acc in mG (×1000/9.81 → raw), we approximate
            putShort((xacc * 1000f / 9.81f).toInt().toShort())
            putShort((yacc * 1000f / 9.81f).toInt().toShort())
            putShort((zacc * 1000f / 9.81f).toInt().toShort())
            putShort((xgyro * 1000f).toInt().toShort()) // mrad/s
            putShort((ygyro * 1000f).toInt().toShort())
            putShort((zgyro * 1000f).toInt().toShort())
            putShort(0) // xmag
            putShort(0) // ymag
            putShort(0) // zmag
        }.array()
        return buildPacket(27, payload)
    }

    fun landingTarget(
        timeUsec: Long,
        angleXRad: Float,
        angleYRad: Float,
        distanceM: Float,
        sizeXRad: Float,
        sizeYRad: Float
    ): ByteArray {
        val payload = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(timeUsec)
            putFloat(angleXRad)
            putFloat(angleYRad)
            putFloat(distanceM)
            putFloat(sizeXRad)
            putFloat(sizeYRad)
            put(0)   // target_num
            put(12)  // MAV_FRAME_BODY_FRD
        }.array()
        return buildPacket(149, payload)
    }

    fun visionPositionEstimate(
        timeUsec: Long,
        xM: Float,
        yM: Float,
        zM: Float,
        rollRad: Float,
        pitchRad: Float,
        yawRad: Float
    ): ByteArray {
        val payload = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(timeUsec)
            putFloat(xM)
            putFloat(yM)
            putFloat(zM)
            putFloat(rollRad)
            putFloat(pitchRad)
            putFloat(yawRad)
        }.array()
        return buildPacket(102, payload)
    }

    // -------------------------------------------------------------------------
    // Internal frame builder
    // -------------------------------------------------------------------------
    private fun buildPacket(msgId: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        val frame = ByteArray(6 + len + 2)
        frame[0] = MAVLINK_STX
        frame[1] = len.toByte()
        frame[2] = seq++
        frame[3] = SYSTEM_ID
        frame[4] = COMPONENT_ID
        frame[5] = msgId.toByte()
        payload.copyInto(frame, 6)
        val crc = crc16(frame, 1, 5 + len, crcExtra(msgId))
        frame[6 + len] = (crc and 0xFF).toByte()
        frame[7 + len] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }

    /** MAVLink X.25 CRC */
    private fun crc16(data: ByteArray, start: Int, length: Int, extra: Int): Int {
        var crc = 0xFFFF
        for (i in start until start + length) {
            var tmp = (data[i].toInt() and 0xFF) xor (crc and 0xFF)
            tmp = tmp xor (tmp shl 4 and 0xFF)
            crc = (crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)
        }
        var tmp = extra xor (crc and 0xFF)
        tmp = tmp xor (tmp shl 4 and 0xFF)
        crc = (crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)
        return crc and 0xFFFF
    }

    private fun crcExtra(msgId: Int): Int = when (msgId) {
        0 -> 50
        27 -> 144
        102 -> 158
        149 -> 200
        232 -> 151
        else -> 0
    }

    private fun gpsWeekTime(timeUsec: Long): Pair<Int, Int> {
        val gpsEpochMs = 315964800000L
        val leapOffsetMs = 18000L
        val gpsMs = (timeUsec / 1000L) - gpsEpochMs + leapOffsetMs
        if (gpsMs <= 0L) {
            return 0 to 0
        }

        val weekMs = (gpsMs % 604800000L).toInt()
        val week = (gpsMs / 604800000L).toInt()
        return weekMs to week
    }
}
