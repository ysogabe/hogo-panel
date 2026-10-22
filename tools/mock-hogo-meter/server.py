#!/usr/bin/env python3
"""投薬予定の Mock API (評価用)。Python 3 の標準ライブラリだけで動く。

127.0.0.1:8787  機器向け API (GET /api/agent/{deviceId}/medications) だけ。adb reverse で端末へ転送する。
127.0.0.1:8788  画面モックの静的ファイルと /mock/* (評価状態・要求の一覧)。端末へは転送しない。

待ち受けのアドレスを変える引数は無い。評価用の秘密は起動のたびに作り、接続設定ファイルにだけ書く。
"""

import atexit
import collections
import hmac
import json
import os
import re
import secrets
import shutil
import signal
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import mock_state

LISTEN_HOST = "127.0.0.1"
DEVICE_PORT = 8787
UI_PORT = 8788
DEVICE_ID = "mock-panel-1"
SECRET_HEADER = "X-Mock-Device-Secret"
STATIC_DIR = Path(__file__).resolve().parent / "static"
# 配るファイルは固定の一覧だけ。要求のパスからファイル名を組み立てない。
STATIC_FILES = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/static/mock.css": ("mock.css", "text/css; charset=utf-8"),
    "/static/mock.js": ("mock.js", "text/javascript; charset=utf-8"),
}
MAX_POST_BYTES = 16 * 1024
_DRAIN_LIMIT = 64 * 1024  # 拒否する POST でも、これ以下の本文は読み捨てて接続の取り消しを避ける
SLOW_SECONDS = 12
REQUEST_LOG_SIZE = 50
CLIENT_TIMEOUT_SECONDS = 30

_DEVICE_PATH_RE = re.compile(r"/api/agent/([A-Za-z0-9_-]{1,64})/medications")
_LENGTH_RE = re.compile(r"[0-9]{1,9}")

ERR_UNAUTHORIZED = {"error": "unauthorized"}
ERR_NOT_FOUND = {"error": "not found"}
ERR_DEVICE_NOT_FOUND = {"error": "device not found"}
ERR_MISDIRECTED = {"error": "misdirected request"}
ERR_INTERNAL = {"error": "internal error"}


class MockApp:
    """評価状態・要求の一覧・秘密。サーバー2つとスレッドで共有する。"""

    def __init__(self, secret, device_id=DEVICE_ID, clock=time.time):
        self.secret = secret
        self.device_id = device_id
        self.clock = clock
        self.slow_seconds = SLOW_SECONDS
        self.stop = threading.Event()
        self._lock = threading.Lock()
        self._requests = collections.deque(maxlen=REQUEST_LOG_SIZE)
        self._state = mock_state.initial_state(mock_state.today_in_zone("Asia/Tokyo", clock()))

    def now(self):
        return self.clock()

    def _today_for_zone(self, time_zone):
        return mock_state.today_in_zone(time_zone, self.now())

    def snapshot(self):
        with self._lock:
            return dict(self._state)

    def update(self, patch):
        with self._lock:
            self._state = mock_state.apply_update(self._state, patch, self._today_for_zone)
            return dict(self._state)

    def state_document(self):
        state = self.snapshot()
        now = self.now()
        return {
            "state": mock_state.public_state(state),
            "choices": {
                "preset": list(mock_state.PRESETS),
                "status": list(mock_state.STATUSES),
                "calendarId": list(mock_state.CALENDARS),
                "fault": list(mock_state.FAULTS),
            },
            "today": mock_state.today_in_zone(state["timeZone"], now).isoformat(),
            "serverTime": int(now),
        }

    def preview(self):
        """いまパネルが 200 で受ける本文 (fault は含めない)。画面モックの表示用。"""
        state = self.snapshot()
        now = self.now()
        return mock_state.build_medications(state, mock_state.today_in_zone(state["timeZone"], now), now)

    def log_request(self, method, path, status, size, fault):
        clean = "".join(ch if " " <= ch <= "~" else "?" for ch in path)[:128]
        entry = {"time": int(self.now()), "method": method, "path": clean,
                 "status": status, "bytes": size, "fault": fault}
        with self._lock:
            self._requests.append(entry)

    def requests_document(self):
        with self._lock:
            return {"requests": list(self._requests)}


class MockServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, port, handler, app, role):
        super().__init__((LISTEN_HOST, port), handler)
        self.app = app
        self.role = role

    @property
    def port(self):
        return self.server_address[1]

    def handle_error(self, request, client_address):
        # 既定の実装は例外とクライアントのアドレスを stderr に出す。何も出さない。
        pass


class Handler(BaseHTTPRequestHandler):
    server_version = "MockHogoMeter"
    sys_version = ""
    timeout = CLIENT_TIMEOUT_SECONDS
    error_message_format = '{"error":"%(explain)s"}'
    error_content_type = "application/json; charset=utf-8"

    # --- ログ: 秘密・ヘッダー・本文・例外を出さない ---
    def log_message(self, format, *args):
        pass

    def log_error(self, format, *args):
        pass

    # --- 応答の書き出し ---
    def _send(self, status, body, content_type="application/json; charset=utf-8"):
        self._sent = (status, len(body))
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _send_json(self, status, obj):
        self._send(status, mock_state.encode_json(obj))

    def _path(self):
        return self.path.split("?", 1)[0].split("#", 1)[0]

    def _host_ok(self):
        host = self.headers.get("Host")
        if host is None:
            return False
        return host.lower() in ("127.0.0.1:%d" % self.server.port, "localhost:%d" % self.server.port)

    # --- 入口 ---
    def _dispatch(self, method):
        self._sent = (0, 0)
        self._fault = "none"
        try:
            if method == "POST":
                # 先に読み、拒否の応答のあとに未読の本文が残らないようにする
                self._body, self._length = self._read_body()
            if not self._host_ok():
                self._send_json(421, ERR_MISDIRECTED)
            elif method not in ("GET", "POST"):
                self._send_json(405, {"error": "method not allowed"})
            elif self.server.role == "device":
                self._handle_device(method)
            else:
                self._handle_ui(method)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            return
        except Exception:
            try:
                self._send_json(500, ERR_INTERNAL)
            except Exception:
                pass
        finally:
            if self.server.role == "device":
                self.server.app.log_request(method, self._path(), self._sent[0], self._sent[1], self._fault)

    def do_GET(self):
        self._dispatch("GET")

    def do_POST(self):
        self._dispatch("POST")

    do_HEAD = do_PUT = do_DELETE = do_PATCH = do_OPTIONS = lambda self: self._dispatch(self.command)

    # --- 8787: 機器向け API ---
    def _handle_device(self, method):
        app = self.server.app
        match = _DEVICE_PATH_RE.fullmatch(self._path())
        if method != "GET" or match is None:
            self._send_json(404, ERR_NOT_FOUND)
            return
        given = (self.headers.get(SECRET_HEADER) or "").encode("latin-1", "replace")
        if not hmac.compare_digest(given, app.secret.encode("ascii")):
            self._send_json(401, ERR_UNAUTHORIZED)
            return
        if not hmac.compare_digest(match.group(1).encode("ascii"), app.device_id.encode("ascii")):
            self._send_json(404, ERR_DEVICE_NOT_FOUND)
            return
        state = app.snapshot()
        self._fault = fault = state["fault"]
        if fault == "unauthorized":
            self._send_json(401, ERR_UNAUTHORIZED)
            return
        if fault == "serverError":
            self._send_json(500, ERR_INTERNAL)
            return
        if fault == "slow":
            app.stop.wait(app.slow_seconds)
        now = app.now()
        body = mock_state.build_medications(state, mock_state.today_in_zone(state["timeZone"], now), now)
        if fault == "malformed":
            self._send(200, mock_state.malformed_body())
        elif fault == "deepNesting":
            self._send(200, mock_state.deep_nesting_body(body))
        else:
            self._send(200, mock_state.encode_json(body))

    # --- 8788: 画面モックと評価用 ---
    def _handle_ui(self, method):
        app = self.server.app
        path = self._path()
        if method == "POST":
            self._handle_post(path)
        elif path in STATIC_FILES:
            self._send_static(*STATIC_FILES[path])
        elif path == "/mock/state":
            self._send_json(200, app.state_document())
        elif path == "/mock/requests":
            self._send_json(200, app.requests_document())
        elif path == "/mock/preview":
            self._send_json(200, app.preview())
        else:
            self._send_json(404, ERR_NOT_FOUND)

    def _send_static(self, filename, content_type):
        try:
            body = (STATIC_DIR / filename).read_bytes()
        except OSError:
            self._send_json(404, ERR_NOT_FOUND)
            return
        self._sent = (200, len(body))
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self):
        """(本文, 宣言された長さ)。長さが無い・形が違うなら (None, None)、大きすぎれば (None, 長さ)。"""
        raw = self.headers.get("Content-Length")
        if raw is None or "Transfer-Encoding" in self.headers or not _LENGTH_RE.fullmatch(raw):
            return None, None
        length = int(raw)
        if length > _DRAIN_LIMIT:
            return None, length
        return self.rfile.read(length), length

    def _handle_post(self, path):
        app = self.server.app
        body, length = self._body, self._length
        origin = self.headers.get("Origin")
        port = self.server.port
        if origin not in ("http://127.0.0.1:%d" % port, "http://localhost:%d" % port):
            self._send_json(403, {"error": "forbidden"})
            return
        if path != "/mock/state":
            self._send_json(404, ERR_NOT_FOUND)
            return
        if self.headers.get_content_type() != "application/json":
            self._send_json(415, {"error": "content type must be application/json"})
            return
        if length is None:
            self._send_json(411, {"error": "content-length required"})
            return
        if length > MAX_POST_BYTES:
            self._send_json(413, {"error": "payload too large"})
            return
        if body is None or len(body) != length:
            self._send_json(400, {"error": "bad request"})
            return
        try:
            update = mock_state.parse_update_json(body.decode("utf-8"))
            app.update(update)
        except (mock_state.StateError, UnicodeDecodeError) as exc:
            message = str(exc) if isinstance(exc, mock_state.StateError) else "invalid utf-8"
            self._send_json(400, {"error": message})
            return
        self._send_json(200, app.state_document())


# --- 接続設定ファイル ---
def create_config_file(secret, device_port=DEVICE_PORT, device_id=DEVICE_ID):
    """パネルへ渡す接続設定を、mkdtemp (0700) の中に O_EXCL・0600 で書く。パスを返す。"""
    directory = tempfile.mkdtemp(prefix="mock-hogo-meter-")
    path = os.path.join(directory, "api-config.json")
    config = {"baseUrl": "http://%s:%d" % (LISTEN_HOST, device_port), "deviceId": device_id, "secret": secret}
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(config, handle)
        handle.write("\n")
    return path


def remove_config_file(path):
    """ファイルと一時ディレクトリを消す。何度呼んでもよい。"""
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass
    shutil.rmtree(os.path.dirname(path), ignore_errors=True)


def make_servers(app, device_port=DEVICE_PORT, ui_port=UI_PORT):
    """(機器向け, 画面モック)。ポートはテストが 0 を渡すためだけの引数で、アドレスは変えられない。"""
    return (MockServer(device_port, Handler, app, "device"), MockServer(ui_port, Handler, app, "ui"))


def main(device_port=DEVICE_PORT, ui_port=UI_PORT):
    app = MockApp(secrets.token_urlsafe(32))
    device, ui = make_servers(app, device_port, ui_port)
    config_path = create_config_file(app.secret, device.port, app.device_id)
    atexit.register(remove_config_file, config_path)
    stop = app.stop
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    threads = [threading.Thread(target=srv.serve_forever, daemon=True) for srv in (device, ui)]
    for thread in threads:
        thread.start()
    print("Mock API を起動しました (評価用。架空のデータだけを返します)", flush=True)
    print("  機器向け API : http://%s:%d  (adb reverse で端末へ転送)" % (LISTEN_HOST, device.port), flush=True)
    print("  画面モック   : http://%s:%d  (端末へは転送しない)" % (LISTEN_HOST, ui.port), flush=True)
    print("  接続設定     : %s  (秘密を含む。終了時に消えます)" % config_path, flush=True)
    print("  端末へ書く   : tools/mock-hogo-meter/push-config.sh <serial> %s" % config_path, flush=True)
    print("終了は Ctrl-C", flush=True)
    try:
        while not stop.wait(1):
            pass
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        for srv in (device, ui):
            srv.shutdown()
            srv.server_close()
        remove_config_file(config_path)
    print("終了しました。", flush=True)


if __name__ == "__main__":
    main()
