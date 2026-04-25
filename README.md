# Androne

Android app that streams phone GPS + IMU telemetry to an ArduPilot Pixhawk over MAVLink UDP and now also exposes a live camera pipeline for preview, video forwarding, landing-target output, and basic visual pose estimates.

## Features

- Streams MAVLink `HEARTBEAT`, `GPS_INPUT`, and `RAW_IMU`
- Streams MAVLink `LANDING_TARGET` and `VISION_POSITION_ESTIMATE` from camera analysis
- Configurable Pixhawk IP and UDP port from app UI
- Rear-camera preview on the phone screen via CameraX
- Optional custom UDP JPEG video feed to a PC/GCS listener
- Live telemetry readout on the phone screen:
	- GPS: lat/lon/alt/fix/sats
	- Gyroscope: x/y/z (deg/s)
	- Accelerometer: x/y/z (m/s^2)
	- Camera: FPS, image brightness, target offset, image-shift pose proxy, quality
	- Sample counters and average rates (GPS/IMU/ACC)
- Android Studio Logcat telemetry monitor (tag: `AndroneTelemetry`)

## Requirements

- Android Studio (latest stable)
- Android phone (Android 8.0+)
- USB cable for deployment
- Pixhawk reachable on network (Wi-Fi bridge, telemetry link, etc.)

## Build And Run

1. Open project in Android Studio.
2. Connect phone with USB and enable Developer options + USB debugging.
3. Press Run and select your phone.
4. Grant location and camera permissions when prompted.
5. In app:
	 - Enter Pixhawk IP
	 - Enter UDP port (usually `14550`)
	 - Optionally enable UDP JPEG video and set a PC host/port
	 - Optionally enable `LANDING_TARGET` and `VISION_POSITION_ESTIMATE`
	 - Tap **Connect & Stream**

## Connect Phone To Pixhawk

Use one of these connection patterns first:

- Pixhawk is connected to a Wi-Fi/Ethernet MAVLink bridge (telemetry radio, companion computer, router bridge), and your phone is on the same network.
- Pixhawk is connected to a companion computer (Raspberry Pi/Jetson/laptop), and that companion forwards MAVLink UDP to the network.

Then follow this flow:

1. Power Pixhawk and verify it is sending MAVLink on UDP (commonly port `14550`) from your bridge/companion.
2. Connect the phone to the same Wi-Fi network as the Pixhawk bridge/companion.
3. Find the target IP address:
	- If sending directly to your phone, use the phone IP as the UDP target on the bridge side.
	- If the app is sending to a Pixhawk-side bridge, use that bridge device IP in the app.
4. Open Androne and fill:
	- `Pixhawk IP`: target IP from step 3
	- `UDP Port`: target MAVLink UDP port (usually `14550`)
5. Tap **Connect & Stream** and allow permissions if prompted.
6. Confirm traffic:
	- In app, telemetry values should update continuously.
	- In Logcat with tag `AndroneTelemetry`, you should see `stream_start` and recurring `MEAS`/`CAM` lines.

If data does not appear, check these first:

- Phone and bridge are on the same subnet and can reach each other.
- Firewall on bridge/companion allows inbound UDP on the selected port.
- Correct MAVLink endpoint direction (where packets are being sent) and port number.
- Pixhawk serial/telemetry MAVLink output is enabled on the port connected to the bridge.
- No other app is exclusively binding/consuming the same UDP endpoint.

## Send Video Directly To GCS Or PC

The camera video does not need to go through PX4. You can send video directly from the phone to your computer over UDP.

1. Connect phone and computer to the same network.
2. On the computer, find your local IP address (for example with `ipconfig` on Windows).
3. In Androne:
	- Enable video streaming switch
	- Set `Video Host` to your computer IP
	- Set `Video Port` to a UDP port you will listen on (example: `5600`)
	- Tap **Connect & Stream**
4. Start a UDP receiver on your computer for that port.

Important:

- Current video transport is a custom chunked UDP JPEG stream.
- It is not native RTSP and not a direct QGroundControl video source by default.
- If you want direct QGC-compatible video, add an RTSP/WebRTC or GStreamer bridge on the computer side.

### Built-in Web Stream Bridge (This Repo)

This repository includes a bridge script that converts the app's UDP JPEG feed into a browser-friendly MJPEG stream.

1. On your computer, run:
	- `python tools/udp_jpeg_web_bridge.py --udp-port 5600 --http-port 8080`
2. In Androne app set:
	- `Video Host` = your computer IP (example: `192.168.137.1`)
	- `Video Port` = `5600`
	- Enable video streaming and tap **Connect & Stream**
3. Open in browser:
	- `http://192.168.137.1:8080/`
	- Or direct MJPEG endpoint: `http://192.168.137.1:8080/stream.mjpg`

Optional endpoints:

- `GET /latest.jpg` returns the most recent frame
- `GET /health` returns `ok` once frames are arriving

## How To See Measurements

### On Phone Screen

After streaming starts, you can see:

- `Live Sensor Data` card with raw GPS/GYRO/ACC values
- `Camera Feed` card with live preview and camera-derived metrics
- `Samples:` line showing counters and average sample rate for each sensor, including camera frames

### In Android Studio Monitor (Logcat)

1. Open **Logcat** in Android Studio.
2. Select your device and app process.
3. Filter by tag: `AndroneTelemetry`

You will see logs like:

- `stream_start host=... port=...`
- `MEAS t=... lat=... lon=... alt=... gyro=(...) acc=(...)`
- `CAM fps=... target=... shift=(...) pose=(...) quality=...`
- `stream_stop gps=... imu=... acc=...`

## Notes

- The video path is a custom chunked UDP JPEG feed intended for a desktop listener or bridge. It is not direct RTSP/QGC video integration.
- The landing-target and visual pose outputs are lightweight image-derived estimates, suitable for integration testing rather than flight-critical autonomy.
- This project sends MAVLink v1 frames with message CRC extras for integration/testing workflows.
- For flight-critical use, validate message definitions, EKF fusion parameters, landing-target tuning, rates, camera orientation, and timing against your ArduPilot firmware/version.
