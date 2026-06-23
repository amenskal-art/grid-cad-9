# tools/scanner_pairing_gate.py
# PC-side pairing for the AI Scanner "Wireless" tab.
#
# CORRECT FLOW (PC shows QR, phone reads it with the USB webcam, phone calls back):
#
#   1. PC opens a "pairing gate": a tiny HTTP server on a pairing port (8765).
#   2. PC renders a QR encoding  {ip, port, token}  for the PC itself.
#   3. The phone app points the USB webcam at the PC screen, decodes the QR,
#      and POSTs its OWN stream address to  http://<pc_ip>:8765/pair .
#   4. The gate validates the token, stores the phone's stream URL, fills
#      camera_protocol + camera_ip, and the tool AUTO-CONNECTS the wireless
#      stream to the phone.
#
# The PC is the receiver here (like a server waiting for one message). After
# pairing, the phone is just the MJPEG camera source, exactly as before.

import os
import json
import socket
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def get_pc_lan_ip():
    """Best-effort primary LAN IPv4 of this PC (the address the phone will
    call back on). Uses the classic 'connect to a public IP' trick to pick the
    right interface without actually sending anything."""
    ip = "127.0.0.1"
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
        finally:
            s.close()
    except Exception:
        try:
            ip = socket.gethostbyname(socket.gethostname())
        except Exception:
            pass
    return ip


class PairingGateMixin:
    """Mix into AIScannerTool. Provides the pairing gate + QR display.

    Public surface used by the tool:
        self.show_pair_window           bool flag for the dialog
        self._init_pairing_gate()       call once in __init__
        self._start_pairing_gate()      open the HTTP gate + make QR payload
        self._stop_pairing_gate()       close the gate
        self._draw_pair_window()        draw the QR dialog (call near other windows)
        self._pair_qr_payload           the string encoded into the QR
        self._pair_state                'idle'|'listening'|'paired'|'error'

    Relies on existing fields: camera_protocols, camera_protocol, camera_ip,
    show_stream_window, _reconnect_stream(), log(), _ui(), _safe_window_size(),
    _logical_window_size().
    """

    PAIR_PORT = 8765

    def _init_pairing_gate(self):
        self.show_pair_window = False
        self._pair_server = None
        self._pair_thread = None
        self._pair_token = ""
        self._pair_qr_payload = ""
        self._pair_state = "idle"
        self._pair_detail = ""
        self._pair_phone_name = ""
        self._pair_qr_tex_id = None
        self._pair_qr_rgb = None          # numpy RGB image of the QR
        self._pair_pc_ip = ""
        # A pending paired URL set by the gate thread; consumed on the UI
        # thread so the actual stream (re)connect happens in the main loop.
        self._pair_pending_url = None
        self._pair_lock = threading.Lock()

    # ---------------- gate lifecycle ----------------
    def _start_pairing_gate(self):
        if self._pair_server is not None:
            return
        self._pair_token = secrets.token_urlsafe(8)
        self._pair_pc_ip = get_pc_lan_ip()
        payload = {
            "v": 1,
            "ip": self._pair_pc_ip,
            "port": self.PAIR_PORT,
            "token": self._pair_token,
        }
        self._pair_qr_payload = json.dumps(payload, separators=(",", ":"))
        self._build_pair_qr_image(self._pair_qr_payload)

        tool = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass  # silence default stderr logging

            def _send(self, code, body, ctype="application/json"):
                data = body.encode() if isinstance(body, str) else body
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                # Lets the phone confirm the gate is alive (and echo the token).
                if self.path.startswith("/ping"):
                    self._send(200, json.dumps({"ok": True}))
                else:
                    self._send(404, json.dumps({"ok": False}))

            def do_POST(self):
                if not self.path.startswith("/pair"):
                    self._send(404, json.dumps({"ok": False}))
                    return
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    raw = self.rfile.read(length) if length else b"{}"
                    msg = json.loads(raw.decode("utf-8"))
                except Exception:
                    self._send(400, json.dumps({"ok": False, "error": "bad json"}))
                    return

                token = str(msg.get("token", ""))
                if token != tool._pair_token:
                    self._send(403, json.dumps({"ok": False, "error": "bad token"}))
                    tool._pair_detail = "Rejected a pairing attempt (bad token)."
                    return

                phone_ip = str(msg.get("ip", "")).strip()
                phone_port = int(msg.get("port", 8080) or 8080)
                phone_path = str(msg.get("path", "/video") or "/video")
                phone_name = str(msg.get("name", "phone"))
                if not phone_ip:
                    self._send(400, json.dumps({"ok": False, "error": "no ip"}))
                    return

                if not phone_path.startswith("/"):
                    phone_path = "/" + phone_path
                address = f"{phone_ip}:{phone_port}{phone_path}"

                # Hand the result to the UI thread.
                with tool._pair_lock:
                    tool._pair_pending_url = f"http://{address}"
                    tool._pair_phone_name = phone_name
                    tool._pair_state = "paired"
                    tool._pair_detail = f"Paired with {phone_name} ({phone_ip})"

                tool.log(f"Pairing gate: received {phone_name} -> {address}")
                self._send(200, json.dumps({"ok": True, "stream": address}))

        try:
            srv = ThreadingHTTPServer(("0.0.0.0", self.PAIR_PORT), Handler)
        except Exception as e:
            self._pair_state = "error"
            self._pair_detail = f"Could not open pairing gate: {e}"
            self.log(f"Error: pairing gate failed to start: {e}")
            return

        self._pair_server = srv
        self._pair_state = "listening"
        self._pair_detail = "Waiting for the phone to read the code..."

        def _serve():
            try:
                srv.serve_forever(poll_interval=0.3)
            except Exception:
                pass

        self._pair_thread = threading.Thread(target=_serve, daemon=True)
        self._pair_thread.start()
        self.log(f"Pairing gate open on {self._pair_pc_ip}:{self.PAIR_PORT}")

    def _stop_pairing_gate(self):
        srv = self._pair_server
        self._pair_server = None
        if srv is not None:
            try:
                srv.shutdown()
                srv.server_close()
            except Exception:
                pass
        if self._pair_state == "listening":
            self._pair_state = "idle"

    # ---------------- consumed on the UI thread ----------------
    def _poll_pairing_result(self):
        """Call once per frame from draw_options. If the gate received a phone
        address, apply it and auto-connect the wireless stream."""
        url = None
        with self._pair_lock:
            if self._pair_pending_url is not None:
                url = self._pair_pending_url
                self._pair_pending_url = None
        if url is None:
            return

        self._apply_paired_url(url)
        # Auto-connect immediately, as requested.
        self.show_stream_window = True
        try:
            self._reconnect_stream(source=0)   # 0 = wireless
        except Exception:
            pass
        # Close the pairing dialog + gate; we're connected now.
        self.show_pair_window = False
        self._stop_pairing_gate()

    def _apply_paired_url(self, url):
        import re
        url = url.strip()
        m = re.match(r'^(https?://|rtsp://)(.*)$', url, re.I)
        if not m:
            proto, addr = "http://", url
        else:
            proto, addr = m.group(1).lower(), m.group(2)
        try:
            self.camera_protocol = self.camera_protocols.index(proto)
        except (ValueError, AttributeError):
            pass
        self.camera_ip = addr
        self.log(f"Wireless source set to phone bridge: {proto}{addr}")

    # ---------------- QR image ----------------
    def _build_pair_qr_image(self, text):
        """Render the QR to an RGB numpy image for display in the dialog.
        Tries the 'qrcode' package, then falls back to a minimal builtin."""
        try:
            import numpy as np
            img = None
            try:
                import qrcode
                qr = qrcode.QRCode(border=2, box_size=10,
                                   error_correction=qrcode.constants.ERROR_CORRECT_M)
                qr.add_data(text)
                qr.make(fit=True)
                pil = qr.make_image(fill_color="white", back_color="black").convert("RGB")
                img = np.array(pil)
            except Exception:
                img = self._fallback_qr_rgb(text)
            self._pair_qr_rgb = img
        except Exception:
            self._pair_qr_rgb = None

    def _fallback_qr_rgb(self, text):
        """Very small dependency-free QR via OpenCV if available."""
        import numpy as np
        try:
            import cv2
            enc = cv2.QRCodeEncoder_create()
            m = enc.encode(text)              # 1-channel (0/255)
            rgb = cv2.cvtColor(m, cv2.COLOR_GRAY2RGB)
            rgb = cv2.resize(rgb, (480, 480), interpolation=cv2.INTER_NEAREST)
            return rgb
        except Exception:
            # last resort: a gray placeholder
            return (np.ones((300, 300, 3), dtype=np.uint8) * 60)

    def _update_pair_qr_texture(self):
        if self._pair_qr_rgb is None:
            return None
        try:
            import OpenGL.GL as gl
            import numpy as np
            if self._pair_qr_tex_id is None:
                self._pair_qr_tex_id = gl.glGenTextures(1)
            gl.glBindTexture(gl.GL_TEXTURE_2D, self._pair_qr_tex_id)
            gl.glPixelStorei(gl.GL_UNPACK_ALIGNMENT, 1)
            h, w = self._pair_qr_rgb.shape[:2]
            gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGB, w, h, 0,
                            gl.GL_RGB, gl.GL_UNSIGNED_BYTE,
                            np.ascontiguousarray(self._pair_qr_rgb))
            gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_NEAREST)
            gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_NEAREST)
            return self._pair_qr_tex_id
        except Exception:
            return None

    # ---------------- the QR dialog ----------------
    def _draw_pair_window(self):
        import imgui
        w, h = self._safe_window_size(self._ui(520), self._ui(620))
        win_w, win_h = self._logical_window_size()
        imgui.set_next_window_size(w, h, condition=imgui.ALWAYS)
        imgui.set_next_window_position((win_w - w) * 0.5, (win_h - h) * 0.5,
                                       condition=imgui.FIRST_USE_EVER)

        expanded, self.show_pair_window = imgui.begin(
            "Pair with Phone", closable=True)
        if not expanded:
            self._stop_pairing_gate()
            imgui.end()
            return

        imgui.text_colored("SCAN THIS CODE WITH THE PHONE'S WEBCAM",
                           0.10, 0.78, 0.88, 1.0)
        imgui.text_wrapped(
            "On the phone, plug in the USB-C webcam and open Scanner Bridge. "
            "Point the webcam at this code. The phone will read it and connect "
            "back automatically \u2014 the live stream then opens here.")
        imgui.separator()

        tex = self._update_pair_qr_texture()
        if tex is not None:
            avail_w = imgui.get_content_region_available()[0]
            side = min(avail_w, self._ui(340))
            cur_x = imgui.get_cursor_pos_x()
            if avail_w > side:
                imgui.set_cursor_pos_x(cur_x + (avail_w - side) * 0.5)
            imgui.image(tex, side, side)
        else:
            imgui.text_disabled("Generating code...")

        imgui.dummy(0, self._ui(6))

        state = getattr(self, "_pair_state", "idle")
        if state == "listening":
            imgui.text_colored("\u25cf Waiting for phone...", 1.0, 0.72, 0.20, 1.0)
        elif state == "paired":
            imgui.text_colored("\u25cf Paired!", 0.30, 0.90, 0.45, 1.0)
        elif state == "error":
            imgui.text_colored("\u25cf Error", 0.90, 0.40, 0.40, 1.0)
        else:
            imgui.text_colored("\u25cf Idle", 0.75, 0.78, 0.82, 1.0)
        if getattr(self, "_pair_detail", ""):
            imgui.same_line(0, self._ui(10))
            imgui.text_disabled(self._pair_detail)

        imgui.dummy(0, self._ui(4))
        imgui.text_disabled(f"This PC: {self._pair_pc_ip}:{self.PAIR_PORT}")

        imgui.end()
