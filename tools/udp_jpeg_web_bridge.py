#!/usr/bin/env python3
"""Bridge Androne UDP JPEG chunks into an HTTP MJPEG web stream.

The Android app sends chunked UDP packets in one of these formats:
- current magic: 4 bytes: ADRN
- legacy magic: 5 bytes: ADRRN
- frame_id: uint32 (big-endian)
- total_chunks: uint16 (big-endian)
- chunk_index: uint16 (big-endian)
- width: uint16 (big-endian)
- height: uint16 (big-endian)
- payload: JPEG bytes for this chunk

This script reassembles frames and serves them as MJPEG over HTTP.
"""

from __future__ import annotations

import argparse
import socket
import struct
import threading
import time
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Dict, Optional, Tuple

MAGIC_CURRENT = b"ADRN"
MAGIC_LEGACY = b"ADRRN"
HEADER_LEN_CURRENT = 16
HEADER_LEN_LEGACY = 17
BOUNDARY = b"frame"


@dataclass
class PartialFrame:
    total_chunks: int
    width: int
    height: int
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    chunks: Dict[int, bytes] = field(default_factory=dict)


class FrameAssembler:
    def __init__(self, max_age_seconds: float = 2.0) -> None:
        self.max_age_seconds = max_age_seconds
        self._lock = threading.Lock()
        self._condition = threading.Condition(self._lock)
        self._partials: Dict[int, PartialFrame] = {}
        self._latest_serial = 0
        self._latest_jpeg: Optional[bytes] = None
        self._latest_meta: Tuple[int, int, int] = (0, 0, 0)  # frame_id, width, height

    def ingest_packet(self, packet: bytes) -> None:
        parsed = self._parse_header(packet)
        if parsed is None:
            return

        frame_id, total_chunks, chunk_index, width, height, header_len = parsed

        if total_chunks <= 0 or chunk_index >= total_chunks:
            return

        payload = packet[header_len:]
        if not payload:
            return

        now = time.time()
        with self._condition:
            self._evict_expired_locked(now)

            frame = self._partials.get(frame_id)
            if frame is None:
                frame = PartialFrame(total_chunks=total_chunks, width=width, height=height)
                self._partials[frame_id] = frame

            if frame.total_chunks != total_chunks:
                return

            frame.updated_at = now
            frame.chunks[chunk_index] = payload

            if len(frame.chunks) == frame.total_chunks:
                jpeg = b"".join(frame.chunks[i] for i in range(frame.total_chunks) if i in frame.chunks)
                if len(jpeg) > 4 and jpeg[:2] == b"\xff\xd8" and jpeg[-2:] == b"\xff\xd9":
                    self._latest_serial += 1
                    self._latest_jpeg = jpeg
                    self._latest_meta = (frame_id, frame.width, frame.height)
                    self._condition.notify_all()
                self._partials.pop(frame_id, None)

    def _parse_header(self, packet: bytes) -> Optional[Tuple[int, int, int, int, int, int]]:
        if len(packet) >= HEADER_LEN_CURRENT and packet[:4] == MAGIC_CURRENT:
            try:
                frame_id = struct.unpack(">I", packet[4:8])[0]
                total_chunks = struct.unpack(">H", packet[8:10])[0]
                chunk_index = struct.unpack(">H", packet[10:12])[0]
                width = struct.unpack(">H", packet[12:14])[0]
                height = struct.unpack(">H", packet[14:16])[0]
                return frame_id, total_chunks, chunk_index, width, height, HEADER_LEN_CURRENT
            except struct.error:
                return None

        if len(packet) >= HEADER_LEN_LEGACY and packet[:5] == MAGIC_LEGACY:
            try:
                frame_id = struct.unpack(">I", packet[5:9])[0]
                total_chunks = struct.unpack(">H", packet[9:11])[0]
                chunk_index = struct.unpack(">H", packet[11:13])[0]
                width = struct.unpack(">H", packet[13:15])[0]
                height = struct.unpack(">H", packet[15:17])[0]
                return frame_id, total_chunks, chunk_index, width, height, HEADER_LEN_LEGACY
            except struct.error:
                return None

        return None

    def wait_for_new_frame(
        self, after_serial: int, timeout_seconds: float
    ) -> Optional[Tuple[int, bytes, int, int, int]]:
        end_time = time.time() + timeout_seconds
        with self._condition:
            while self._latest_serial <= after_serial:
                remaining = end_time - time.time()
                if remaining <= 0:
                    return None
                self._condition.wait(timeout=remaining)

            if self._latest_jpeg is None:
                return None

            frame_id, width, height = self._latest_meta
            return self._latest_serial, self._latest_jpeg, frame_id, width, height

    def get_latest_snapshot(self) -> Optional[Tuple[int, bytes, int, int, int]]:
        with self._lock:
            if self._latest_jpeg is None:
                return None
            frame_id, width, height = self._latest_meta
            return self._latest_serial, self._latest_jpeg, frame_id, width, height

    def _evict_expired_locked(self, now: float) -> None:
        stale_ids = [
            frame_id
            for frame_id, partial in self._partials.items()
            if now - partial.updated_at > self.max_age_seconds
        ]
        for frame_id in stale_ids:
            self._partials.pop(frame_id, None)


class BridgeHandler(BaseHTTPRequestHandler):
    server: "BridgeServer"

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"[{self.address_string()}] {fmt % args}")

    def do_GET(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler API)
        if self.path in ("/", "/index.html"):
            self._serve_index()
            return
        if self.path == "/health":
            self._serve_health()
            return
        if self.path == "/latest.jpg":
            self._serve_latest_jpeg()
            return
        if self.path == "/stream.mjpg":
            self._serve_mjpeg_stream()
            return

        self.send_error(HTTPStatus.NOT_FOUND, "Not found")

    def _serve_index(self) -> None:
        body = (
            "<!doctype html>\n"
            "<html><head><meta charset='utf-8'><title>Androne Stream</title>"
            "<style>body{background:#111;color:#eee;font-family:Segoe UI,Arial,sans-serif;margin:0;padding:16px;}"
            "h1{margin:0 0 12px 0;font-size:20px;}"
            "img{max-width:100%;height:auto;border:1px solid #333;background:#000;}"
            "code{background:#222;padding:2px 6px;border-radius:4px;}</style></head><body>"
            "<h1>Androne Web Stream</h1>"
            "<p>MJPEG endpoint: <code>/stream.mjpg</code></p>"
            "<img src='/stream.mjpg' alt='Androne stream'/>"
            "</body></html>"
        ).encode("utf-8")

        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _serve_health(self) -> None:
        snapshot = self.server.assembler.get_latest_snapshot()
        ready = snapshot is not None
        body = ("ok" if ready else "waiting").encode("utf-8")
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _serve_latest_jpeg(self) -> None:
        snapshot = self.server.assembler.get_latest_snapshot()
        if snapshot is None:
            self.send_error(HTTPStatus.SERVICE_UNAVAILABLE, "No frame yet")
            return

        _, jpeg, frame_id, width, height = snapshot
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("X-Frame-Id", str(frame_id))
        self.send_header("X-Frame-Size", f"{width}x{height}")
        self.send_header("Content-Length", str(len(jpeg)))
        self.end_headers()
        self.wfile.write(jpeg)

    def _serve_mjpeg_stream(self) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("X-Accel-Buffering", "no")
        self.send_header("Age", "0")
        self.send_header("Connection", "close")
        self.send_header("Content-Type", f"multipart/x-mixed-replace; boundary={BOUNDARY.decode('ascii')}")
        self.end_headers()

        last_serial = 0
        try:
            while True:
                frame = self.server.assembler.wait_for_new_frame(last_serial, timeout_seconds=5.0)
                if frame is None:
                    continue

                last_serial, jpeg, frame_id, width, height = frame
                headers = (
                    b"--" + BOUNDARY + b"\r\n"
                    + b"Content-Type: image/jpeg\r\n"
                    + f"X-Frame-Id: {frame_id}\r\n".encode("ascii")
                    + f"X-Frame-Size: {width}x{height}\r\n".encode("ascii")
                    + f"Content-Length: {len(jpeg)}\r\n\r\n".encode("ascii")
                )
                self.wfile.write(headers)
                self.wfile.write(jpeg)
                self.wfile.write(b"\r\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            return


class BridgeServer(ThreadingHTTPServer):
    def __init__(self, server_address: Tuple[str, int], assembler: FrameAssembler) -> None:
        super().__init__(server_address, BridgeHandler)
        self.assembler = assembler


def udp_receiver_loop(bind_host: str, bind_port: int, assembler: FrameAssembler, stop_event: threading.Event) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((bind_host, bind_port))
    sock.settimeout(0.5)
    print(f"UDP receiver listening on {bind_host}:{bind_port}")

    try:
        while not stop_event.is_set():
            try:
                data, _ = sock.recvfrom(65535)
            except socket.timeout:
                continue
            assembler.ingest_packet(data)
    finally:
        sock.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Bridge Androne UDP JPEG feed to HTTP MJPEG.")
    parser.add_argument("--udp-bind", default="0.0.0.0", help="UDP bind host for incoming app video.")
    parser.add_argument("--udp-port", type=int, default=5600, help="UDP bind port for incoming app video.")
    parser.add_argument("--http-host", default="0.0.0.0", help="HTTP bind host for web clients.")
    parser.add_argument("--http-port", type=int, default=8080, help="HTTP bind port for web clients.")
    parser.add_argument(
        "--max-frame-age",
        type=float,
        default=2.0,
        help="Seconds to keep incomplete frames before dropping them.",
    )
    parser.add_argument(
        "--public",
        action="store_true",
        help="Expose the HTTP stream publicly via an ngrok tunnel (requires pyngrok).",
    )
    parser.add_argument(
        "--ngrok-token",
        default=None,
        help="ngrok auth token. Set once with 'ngrok config add-authtoken TOKEN' or pass here.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    assembler = FrameAssembler(max_age_seconds=args.max_frame_age)
    stop_event = threading.Event()

    receiver = threading.Thread(
        target=udp_receiver_loop,
        args=(args.udp_bind, args.udp_port, assembler, stop_event),
        daemon=True,
    )
    receiver.start()

    server = BridgeServer((args.http_host, args.http_port), assembler)
    local_url = f"http://127.0.0.1:{args.http_port}"
    print(f"HTTP server running on http://{args.http_host}:{args.http_port}/")
    print(f"MJPEG stream endpoint: {local_url}/stream.mjpg")

    if args.public:
        try:
            from pyngrok import ngrok, conf  # type: ignore[import]

            if args.ngrok_token:
                conf.get_default().auth_token = args.ngrok_token

            tunnel = ngrok.connect(args.http_port, "http")
            public_url = tunnel.public_url.replace("http://", "https://")
            print("")
            print("=" * 60)
            print(f"  PUBLIC URL:  {public_url}")
            print(f"  MJPEG stream: {public_url}/stream.mjpg")
            print("  Share this URL — viewable from any browser worldwide.")
            print("=" * 60)
            print("")
        except ImportError:
            print("ERROR: pyngrok not installed. Run: pip install pyngrok")
            print("       Then sign up at https://ngrok.com and run:")
            print("       ngrok config add-authtoken YOUR_TOKEN")
        except Exception as exc:
            print(f"ERROR: Failed to start ngrok tunnel: {exc}")
            print("       Make sure you have set your ngrok auth token:")
            print("       ngrok config add-authtoken YOUR_TOKEN")

    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        print("Stopping bridge...")
    finally:
        stop_event.set()
        server.shutdown()
        server.server_close()
        if args.public:
            try:
                from pyngrok import ngrok  # type: ignore[import]
                ngrok.kill()
            except Exception:
                pass


if __name__ == "__main__":
    main()
