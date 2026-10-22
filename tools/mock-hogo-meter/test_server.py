"""server の単体テスト (python3 -m unittest)。テストは空いているポート (0 番) で起動する。本番は 8787/8788 固定。"""

import contextlib
import http.client
import inspect
import io
import json
import os
import re
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from datetime import date
from pathlib import Path

import mock_state as ms
import server
from test_mock_state import SAMPLES, check_api_shape, json_depth

HERE = Path(__file__).resolve().parent
SECRET = "test-secret-" + "x" * 20
NOW = 1792629000  # 2026-10-22 (Asia/Tokyo)
TODAY = date(2026, 10, 22)


def fetch(port, method, path, headers=None, body=None, host=True):
    """(status, headers, body)。Host は http.client が付ける。headers で上書きできる。"""
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    try:
        own_host = any(k.lower() == "host" for k in (headers or {}))
        conn.putrequest(method, path, skip_host=not host or own_host, skip_accept_encoding=True)
        for name, value in (headers or {}).items():
            conn.putheader(name, value)
        if body is not None and not any(k.lower() == "content-length" for k in (headers or {})):
            conn.putheader("Content-Length", str(len(body)))
        conn.endheaders(body)
        resp = conn.getresponse()
        return resp.status, resp, resp.read()
    finally:
        conn.close()


def raw_exchange(port, payload):
    with socket.create_connection(("127.0.0.1", port), timeout=10) as sock:
        sock.sendall(payload)
        chunks = []
        while True:
            data = sock.recv(65536)
            if not data:
                break
            chunks.append(data)
    return b"".join(chunks)


class ServerTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.MockApp(SECRET, clock=lambda: NOW)
        cls.app.slow_seconds = 0.3
        cls.device, cls.ui = server.make_servers(cls.app, 0, 0)
        cls.threads = [threading.Thread(target=s.serve_forever, daemon=True) for s in (cls.device, cls.ui)]
        for thread in cls.threads:
            thread.start()
        cls.dport, cls.uport = cls.device.port, cls.ui.port
        cls.origin = "http://127.0.0.1:%d" % cls.uport

    @classmethod
    def tearDownClass(cls):
        cls.app.stop.set()
        for srv in (cls.device, cls.ui):
            srv.shutdown()
            srv.server_close()

    def setUp(self):
        self.app._state = ms.initial_state(TODAY)
        self.app._requests.clear()

    # --- ヘルパー ---
    def medications(self, secret=SECRET, device_id="mock-panel-1", headers=None):
        hdrs = dict(headers or {})
        if secret is not None:
            hdrs[server.SECRET_HEADER] = secret
        return fetch(self.dport, "GET", "/api/agent/%s/medications" % device_id, hdrs)

    def set_state(self, **patch):
        self.app.update(patch)

    def post_state(self, patch, origin="same", content_type="application/json", raw=None):
        headers = {}
        if origin == "same":
            headers["Origin"] = self.origin
        elif origin is not None:
            headers["Origin"] = origin
        if content_type is not None:
            headers["Content-Type"] = content_type
        body = raw if raw is not None else json.dumps(patch).encode("utf-8")
        return fetch(self.uport, "POST", "/mock/state", headers, body)

    def get_json(self, port, path):
        status, _, body = fetch(port, "GET", path)
        self.assertEqual(status, 200, path)
        return json.loads(body)


class DeviceApiTest(ServerTestCase):
    def test_ok_response_shape_and_headers(self):
        self.set_state(preset="three")
        status, resp, body = self.medications()
        self.assertEqual(status, 200)
        self.assertEqual(resp.getheader("Content-Type"), "application/json; charset=utf-8")
        parsed = json.loads(body.decode("utf-8"))
        check_api_shape(self, parsed)
        self.assertEqual(len(parsed["occurrences"]), 3)

    def test_content_length_is_utf8_byte_length(self):
        self.set_state(preset="three")
        status, resp, body = self.medications()
        text = body.decode("utf-8")
        self.assertEqual(int(resp.getheader("Content-Length")), len(body))
        self.assertGreater(len(body), len(text), "日本語を含むのでバイト長のほうが長い")
        self.assertIn("フィラリア予防薬", text)  # \u エスケープでない
        self.assertNotIn("\\u", text)

    def test_every_preset_status_and_enabled_via_http(self):
        for preset in ms.PRESETS:
            for status in ms.STATUSES:
                self.set_state(preset=preset, status=status)
                _, _, body = self.medications()
                check_api_shape(self, json.loads(body))
        self.set_state(enabled=False)
        _, _, body = self.medications()
        self.assertEqual(json.loads(body), {"enabled": False, "pollSeconds": 15})

    def test_state_changes_are_reflected(self):
        self.set_state(leadDays=3, pollSeconds=60, timeZone="UTC", calendarId="mock-cal-2", preset="soon")
        parsed = json.loads(self.medications()[2])
        self.assertEqual((parsed["leadDays"], parsed["pollSeconds"], parsed["timeZone"]), (3, 60, "UTC"))
        self.assertTrue(all("cal2" in o["key"] for o in parsed["occurrences"]))

    def test_localhost_host_is_accepted(self):
        status, _, _ = self.medications(headers={"Host": "localhost:%d" % self.dport})
        self.assertEqual(status, 200)

    def test_wrong_host_is_421(self):
        for host in ("evil.example", "evil.example:%d" % self.dport, "127.0.0.1", "127.0.0.1:1",
                     "127.0.0.1:%d.evil.example" % self.dport, "localhost", "[::1]:%d" % self.dport):
            with self.subTest(host=host):
                status, _, body = self.medications(headers={"Host": host})
                self.assertEqual(status, 421)
                self.assertEqual(json.loads(body), {"error": "misdirected request"})

    def test_missing_host_is_421(self):
        reply = raw_exchange(self.dport, ("GET /api/agent/mock-panel-1/medications HTTP/1.0\r\n"
                                          "%s: %s\r\n\r\n" % (server.SECRET_HEADER, SECRET)).encode())
        self.assertTrue(reply.startswith(b"HTTP/1.0 421"), reply[:40])

    def test_host_is_checked_before_everything_else(self):
        # 秘密も deviceId もパスも間違っていても、Host が違えば 421
        for path in ("/nope", "/api/agent/other/medications", "/api/agent/mock-panel-1/medications"):
            status, _, _ = fetch(self.dport, "GET", path, {"Host": "evil.example"})
            self.assertEqual(status, 421, path)

    def test_missing_or_wrong_secret_is_401(self):
        for secret in (None, "", "wrong", SECRET + "x", SECRET[:-1], SECRET.upper()):
            with self.subTest(secret=secret):
                status, _, body = self.medications(secret=secret)
                self.assertEqual(status, 401)
                self.assertEqual(json.loads(body), {"error": "unauthorized"})

    def test_non_ascii_secret_header_is_401_not_an_error(self):
        status, _, _ = self.medications(secret="é".encode("latin-1").decode("latin-1"))
        self.assertEqual(status, 401)

    def test_wrong_secret_hides_device_existence(self):
        status, _, body = self.medications(secret="wrong", device_id="no-such-device")
        self.assertEqual(status, 401)
        self.assertEqual(json.loads(body), {"error": "unauthorized"})

    def test_right_secret_wrong_device_is_404(self):
        status, _, body = self.medications(device_id="no-such-device")
        self.assertEqual(status, 404)
        self.assertEqual(json.loads(body), {"error": "device not found"})

    def test_path_shape_is_checked_before_secret(self):
        for path in ("/", "/api", "/api/agent", "/api/agent/mock-panel-1", "/api/agent/mock-panel-1/medications/",
                     "/api/agent/mock-panel-1/medications/x", "/api/agent//medications", "/api/agent/a/b/medications",
                     "/api/agent/%s/medications" % ("a" * 65), "/api/agent/bad%20id/medications",
                     "/api/agent/%2e%2e/medications", "/api/agent/../medications", "/../etc/passwd",
                     "/api/agent/mock-panel-1/Medications", "/mock/state", "/mock/requests", "/static/mock.js",
                     "/index.html"):
            for secret in (SECRET, "wrong"):
                with self.subTest(path=path, secret=secret):
                    status, _, _ = fetch(self.dport, "GET", path, {server.SECRET_HEADER: secret})
                    self.assertEqual(status, 404)

    def test_absolute_form_request_target_is_404(self):
        reply = raw_exchange(self.dport, ("GET http://127.0.0.1:%d/api/agent/mock-panel-1/medications HTTP/1.0\r\n"
                                          "Host: 127.0.0.1:%d\r\n%s: %s\r\n\r\n"
                                          % (self.dport, self.dport, server.SECRET_HEADER, SECRET)).encode())
        self.assertTrue(reply.startswith(b"HTTP/1.0 404"), reply[:40])

    def test_query_string_is_ignored(self):
        status, _, _ = fetch(self.dport, "GET", "/api/agent/mock-panel-1/medications?x=1",
                             {server.SECRET_HEADER: SECRET})
        self.assertEqual(status, 200)

    def test_device_port_has_no_mock_endpoints_or_pages(self):
        for path in ("/mock/state", "/mock/requests", "/mock/preview", "/", "/static/mock.css", "/static/mock.js"):
            status, _, _ = fetch(self.dport, "GET", path, {server.SECRET_HEADER: SECRET})
            self.assertEqual(status, 404, path)
        status, _, _ = fetch(self.dport, "POST", "/mock/state", {"Origin": "http://127.0.0.1:%d" % self.dport,
                                                                  "Content-Type": "application/json"}, b"{}")
        self.assertEqual(status, 404)

    def test_other_methods_are_rejected(self):
        for method in ("POST", "PUT", "DELETE", "PATCH"):
            status, _, _ = fetch(self.dport, method, "/api/agent/mock-panel-1/medications",
                                 {server.SECRET_HEADER: SECRET}, b"")
            self.assertIn(status, (404, 405), method)
        status, _, _ = fetch(self.dport, "HEAD", "/api/agent/mock-panel-1/medications", {server.SECRET_HEADER: SECRET})
        self.assertEqual(status, 405)

    def test_unknown_methods_get_the_host_check_then_405(self):
        for port in (self.dport, self.uport):
            for method in ("TRACE", "CONNECT", "PROPFIND", "FOO", "LINK"):
                with self.subTest(port=port, method=method, host="wrong"):
                    status, _, body = fetch(port, method, "/api/agent/mock-panel-1/medications",
                                            {"Host": "evil.example"})
                    self.assertEqual(status, 421)
                    self.assertEqual(json.loads(body), {"error": "misdirected request"})
                with self.subTest(port=port, method=method, host="missing"):
                    self.assertEqual(fetch(port, method, "/", host=False)[0], 421)
                with self.subTest(port=port, method=method, host="right"):
                    status, _, body = fetch(port, method, "/api/agent/mock-panel-1/medications",
                                            {server.SECRET_HEADER: SECRET})
                    self.assertEqual(status, 405)
                    self.assertEqual(json.loads(body), {"error": "method not allowed"})

    def test_error_bodies_match_the_samples(self):
        def sample(name):
            return json.loads((SAMPLES / name).read_text(encoding="utf-8"))
        self.assertEqual(json.loads(self.medications(secret="x")[2]), sample("error-401.json"))
        self.assertEqual(json.loads(self.medications(device_id="zzz")[2]), sample("error-404.json"))
        for path in ("/", "/api/agent/mock-panel-1", "/api/agent/mock-panel-1/medications/x", "/mock/state"):
            with self.subTest(path=path):
                status, _, body = fetch(self.dport, "GET", path, {server.SECRET_HEADER: SECRET})
                self.assertEqual(status, 404)
                self.assertEqual(json.loads(body), sample("error-404.json"))
        status, _, body = fetch(self.dport, "GET", "/nope", {server.SECRET_HEADER: "wrong"})
        self.assertEqual(json.loads(body), sample("error-404.json"))
        self.set_state(fault="serverError")
        status, _, body = self.medications()
        self.assertEqual(status, 500)
        self.assertEqual(json.loads(body), sample("error-500.json"))

    def test_error_content_length_is_byte_length(self):
        _, resp, body = self.medications(secret="x")
        self.assertEqual(int(resp.getheader("Content-Length")), len(body))


class FaultTest(ServerTestCase):
    def test_unauthorized_fault(self):
        self.set_state(fault="unauthorized")
        status, _, body = self.medications()
        self.assertEqual((status, json.loads(body)), (401, {"error": "unauthorized"}))

    def test_server_error_fault(self):
        self.set_state(fault="serverError")
        status, _, body = self.medications()
        self.assertEqual((status, json.loads(body)), (500, {"error": "internal error"}))

    def test_slow_fault_waits_then_answers(self):
        self.set_state(fault="slow")
        started = time.monotonic()
        status, _, body = self.medications()
        self.assertGreaterEqual(time.monotonic() - started, 0.25)
        self.assertEqual(status, 200)
        check_api_shape(self, json.loads(body))

    def test_slow_default_is_twelve_seconds(self):
        self.assertEqual(server.SLOW_SECONDS, 12)
        self.assertEqual(server.MockApp(SECRET).slow_seconds, 12)

    def test_slow_wait_ends_when_the_server_stops(self):
        app = server.MockApp(SECRET)
        app.stop.set()
        started = time.monotonic()
        app.stop.wait(app.slow_seconds)
        self.assertLess(time.monotonic() - started, 1)

    def test_malformed_fault(self):
        self.set_state(fault="malformed")
        status, resp, body = self.medications()
        self.assertEqual(status, 200)
        self.assertEqual(resp.getheader("Content-Type"), "application/json; charset=utf-8")
        self.assertEqual(int(resp.getheader("Content-Length")), len(body))
        with self.assertRaises(ValueError):
            json.loads(body.decode("utf-8"))

    def test_deep_nesting_fault(self):
        self.set_state(fault="deepNesting", preset="soon")
        status, _, body = self.medications()
        self.assertEqual(status, 200)
        parsed = json.loads(body.decode("utf-8"))
        self.assertGreaterEqual(json_depth(parsed), 9)
        self.assertEqual(parsed["status"], "ok")

    def test_faults_only_apply_after_the_checks(self):
        self.set_state(fault="serverError")
        self.assertEqual(self.medications(secret="wrong")[0], 401)
        self.assertEqual(self.medications(device_id="zzz")[0], 404)
        self.assertEqual(self.medications(headers={"Host": "evil.example"})[0], 421)
        self.set_state(fault="slow")
        started = time.monotonic()
        self.assertEqual(self.medications(secret="wrong")[0], 401)
        self.assertLess(time.monotonic() - started, 0.25)


class UiTest(ServerTestCase):
    def setUp(self):
        super().setUp()
        self.static_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.static_dir.cleanup)
        root = Path(self.static_dir.name)
        (root / "index.html").write_text("<!doctype html><title>モック</title>", encoding="utf-8")
        (root / "mock.css").write_text("body{}", encoding="utf-8")
        (root / "mock.js").write_text("//js", encoding="utf-8")
        (root / "secret.txt").write_text("private", encoding="utf-8")
        original = server.STATIC_DIR
        server.STATIC_DIR = root
        self.addCleanup(setattr, server, "STATIC_DIR", original)

    def test_static_files_from_the_fixed_list(self):
        expected = {"/": "text/html; charset=utf-8", "/static/mock.css": "text/css; charset=utf-8",
                    "/static/mock.js": "text/javascript; charset=utf-8"}
        for path, ctype in expected.items():
            status, resp, body = fetch(self.uport, "GET", path)
            self.assertEqual(status, 200, path)
            self.assertEqual(resp.getheader("Content-Type"), ctype)
            self.assertEqual(int(resp.getheader("Content-Length")), len(body))
            self.assertIn("default-src 'self'", resp.getheader("Content-Security-Policy"))
        self.assertIn("モック", fetch(self.uport, "GET", "/")[2].decode("utf-8"))

    def test_static_files_not_in_the_list_are_404(self):
        for path in ("/static/secret.txt", "/secret.txt", "/index.html", "/static/index.html", "/static/",
                     "/static", "/static/../secret.txt", "/static/../server.py", "/static/%2e%2e/server.py",
                     "/..%2fserver.py", "/../server.py", "//etc/passwd", "/static/mock.css/", "/static/MOCK.css",
                     "/static/mock.css%00", "/server.py", "/mock_state.py", "/mock", "/mock/"):
            with self.subTest(path=path):
                status, _, _ = fetch(self.uport, "GET", path)
                self.assertEqual(status, 404)

    def test_missing_static_file_is_404(self):
        (Path(self.static_dir.name) / "mock.js").unlink()
        self.assertEqual(fetch(self.uport, "GET", "/static/mock.js")[0], 404)
        self.assertEqual(fetch(self.uport, "GET", "/static/mock.css")[0], 200)

    def test_ui_port_has_no_device_api(self):
        status, _, _ = fetch(self.uport, "GET", "/api/agent/mock-panel-1/medications", {server.SECRET_HEADER: SECRET})
        self.assertEqual(status, 404)

    def test_wrong_host_is_421_on_every_ui_request(self):
        for method, path in (("GET", "/"), ("GET", "/static/mock.js"), ("GET", "/mock/state"),
                             ("GET", "/mock/requests"), ("POST", "/mock/state"), ("GET", "/nope")):
            for host in ("evil.example", "127.0.0.1:%d" % self.dport, "127.0.0.1"):
                with self.subTest(method=method, path=path, host=host):
                    headers = {"Host": host, "Origin": self.origin, "Content-Type": "application/json"}
                    status, _, _ = fetch(self.uport, method, path, headers, b"{}" if method == "POST" else None)
                    self.assertEqual(status, 421)

    def test_localhost_host_is_accepted(self):
        status, _, _ = fetch(self.uport, "GET", "/mock/state", {"Host": "localhost:%d" % self.uport})
        self.assertEqual(status, 200)

    def test_get_state_document(self):
        doc = self.get_json(self.uport, "/mock/state")
        self.assertEqual(list(doc["state"]), list(ms.STATE_KEYS))
        self.assertEqual(doc["state"]["preset"], "normal")
        self.assertEqual(doc["today"], "2026-10-22")
        self.assertEqual(doc["choices"]["preset"], list(ms.PRESETS))
        self.assertEqual(doc["choices"]["fault"], list(ms.FAULTS))

    def test_preview_matches_the_device_body_without_fault(self):
        self.set_state(preset="two", fault="serverError")
        preview = self.get_json(self.uport, "/mock/preview")
        self.set_state(fault="none")
        self.assertEqual(preview, json.loads(self.medications()[2]))

    # --- POST ---
    def test_post_updates_state(self):
        status, _, body = self.post_state({"preset": "soon", "leadDays": 3, "status": "stale"})
        self.assertEqual(status, 200)
        doc = json.loads(body)
        self.assertEqual((doc["state"]["preset"], doc["state"]["leadDays"], doc["state"]["status"]),
                         ("soon", 3, "stale"))
        self.assertEqual(doc["state"]["baseDate"], "2026-10-22")
        self.assertEqual(self.get_json(self.uport, "/mock/state")["state"], doc["state"])
        self.assertEqual(json.loads(self.medications()[2])["status"], "stale")

    def test_post_accepts_localhost_origin_and_charset(self):
        status, _, _ = self.post_state({"leadDays": 4}, origin="http://localhost:%d" % self.uport,
                                       content_type="Application/JSON; charset=utf-8")
        self.assertEqual(status, 200)
        self.assertEqual(self.app.snapshot()["leadDays"], 4)

    def test_post_without_or_with_wrong_origin_is_403(self):
        for origin in (None, "", "null", "http://evil.example", "https://127.0.0.1:%d" % self.uport,
                       "http://127.0.0.1:%d/" % self.uport, "http://127.0.0.1:1", "http://127.0.0.1",
                       "http://127.0.0.1:%d.evil.example" % self.uport, "http://localhost:%d" % self.dport,
                       self.origin.upper()):
            with self.subTest(origin=origin):
                status, _, _ = self.post_state({"leadDays": 9}, origin=origin)
                self.assertEqual(status, 403)
                self.assertEqual(self.app.snapshot()["leadDays"], 2, "拒否したら状態は変わらない")

    def test_post_requires_json_content_type(self):
        for ctype in (None, "text/plain", "application/x-www-form-urlencoded", "multipart/form-data",
                      "application/jsonp", "text/json"):
            with self.subTest(ctype=ctype):
                status, _, _ = self.post_state({"leadDays": 9}, content_type=ctype)
                self.assertEqual(status, 415)
        self.assertEqual(self.app.snapshot()["leadDays"], 2)

    def test_post_body_size_limit(self):
        pad = lambda total: b'{"leadDays":3}' + b" " * (total - len(b'{"leadDays":3}'))
        status, _, _ = self.post_state(None, raw=pad(server.MAX_POST_BYTES))
        self.assertEqual(status, 200)
        self.assertEqual(self.app.snapshot()["leadDays"], 3)
        status, _, _ = self.post_state(None, raw=pad(server.MAX_POST_BYTES + 1))
        self.assertEqual(status, 413)
        status, _, _ = self.post_state(None, raw=pad(70000))
        self.assertEqual(status, 413)

    def test_post_requires_a_content_length(self):
        reply = raw_exchange(self.uport, ("POST /mock/state HTTP/1.0\r\nHost: 127.0.0.1:%d\r\nOrigin: %s\r\n"
                                          "Content-Type: application/json\r\n\r\n" % (self.uport, self.origin)).encode())
        self.assertTrue(reply.startswith(b"HTTP/1.0 411"), reply[:40])
        reply = raw_exchange(self.uport, ("POST /mock/state HTTP/1.0\r\nHost: 127.0.0.1:%d\r\nOrigin: %s\r\n"
                                          "Content-Type: application/json\r\nContent-Length: abc\r\n\r\n{}"
                                          % (self.uport, self.origin)).encode())
        self.assertTrue(reply.startswith(b"HTTP/1.0 411"), reply[:40])

    def test_post_rejects_bad_json_and_invalid_values_with_400(self):
        raws = [b"", b"{", b"not json", b"[]", b'"x"', b"3", b"null", b'{"leadDays": NaN}', b'{"leadDays": Infinity}',
                b'{"leadDays": 1, "leadDays": 2}', b'{"nope": 1}', b'{"secret": "x"}', b'\xff\xfe{}', b"[" * 5000]
        bodies = [
            {"leadDays": True}, {"leadDays": 2.0}, {"leadDays": "2"}, {"leadDays": 15}, {"leadDays": None},
            {"pastDays": -1}, {"pastDays": True}, {"pollSeconds": 0}, {"pollSeconds": 1.5},
            {"enabled": 1}, {"enabled": "false"}, {"preset": "bad"}, {"preset": ["soon"]}, {"status": "ok2"},
            {"fault": "boom"}, {"calendarId": "mock-cal-9"}, {"baseDate": "2026-02-30"}, {"baseDate": "10/22"},
            {"timeZone": "+09:00"}, {"timeZone": "UTC+9"}, {"timeZone": "JST"}, {"timeZone": "../x"},
            {"leadDays": 3, "pastDays": 99}, {"unknown": 1}, {"leadDays": 3, "unknown": 1},
        ]
        before = self.app.snapshot()
        for raw in raws:
            with self.subTest(raw=raw[:30]):
                status, _, body = self.post_state(None, raw=raw)
                self.assertEqual(status, 400)
                self.assertIn("error", json.loads(body))
        for patch in bodies:
            with self.subTest(patch=patch):
                self.assertEqual(self.post_state(patch)[0], 400)
        self.assertEqual(self.app.snapshot(), before)

    def test_post_integer_valued_float_is_rejected(self):
        for raw in (b'{"leadDays": 2.0}', b'{"leadDays": 2e0}', b'{"pollSeconds": 1e2}'):
            self.assertEqual(self.post_state(None, raw=raw)[0], 400)

    def test_post_other_paths_and_methods(self):
        for path in ("/mock/requests", "/mock/preview", "/", "/nope", "/static/mock.js"):
            status, _, _ = fetch(self.uport, "POST", path, {"Origin": self.origin, "Content-Type": "application/json"}, b"{}")
            self.assertEqual(status, 404, path)
        for method in ("PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"):
            status, resp, _ = fetch(self.uport, method, "/mock/state", {"Origin": self.origin})
            self.assertEqual(status, 405, method)
            self.assertIsNone(resp.getheader("Access-Control-Allow-Origin"))

    def test_no_cors_headers(self):
        for port, path in ((self.uport, "/mock/state"), (self.dport, "/api/agent/mock-panel-1/medications")):
            _, resp, _ = fetch(port, "GET", path, {"Origin": "http://evil.example", server.SECRET_HEADER: SECRET})
            self.assertIsNone(resp.getheader("Access-Control-Allow-Origin"))


class RealStaticFilesTest(ServerTestCase):
    """リポジトリの static/ にある画面モック本体 (UiTest は一時ディレクトリの代用ファイルで確かめる)。"""

    EXPECTED = {"/": "text/html; charset=utf-8", "/static/mock.css": "text/css; charset=utf-8",
                "/static/mock.js": "text/javascript; charset=utf-8"}

    def test_served_on_8788_with_content_types(self):
        for path, ctype in self.EXPECTED.items():
            status, resp, body = fetch(self.uport, "GET", path)
            self.assertEqual(status, 200, path)
            self.assertEqual(resp.getheader("Content-Type"), ctype)
            self.assertGreater(len(body), 100, path)

    def test_not_served_on_8787(self):
        for path in self.EXPECTED:
            self.assertEqual(fetch(self.dport, "GET", path)[0], 404, path)

    def test_page_is_self_contained_and_marked_as_mock(self):
        files = {name: (server.STATIC_DIR / name).read_text(encoding="utf-8")
                 for name in ("index.html", "mock.css", "mock.js")}
        self.assertIn("本物ではありません", files["index.html"])
        self.assertIn("評価用（本番には無い）", files["index.html"])
        for name, content in files.items():
            # 外部の CDN・フォント・ライブラリを読まない (CSP は default-src 'self')
            self.assertNotRegex(content, r"https?://|//cdn|@import", name)
        # API の値は textContent だけで入れる。インラインのスクリプト・スタイルも使わない (CSP で止まる)
        self.assertNotIn("innerHTML", files["mock.js"])
        self.assertNotIn("insertAdjacentHTML", files["mock.js"])
        self.assertNotIn("outerHTML", files["mock.js"])
        self.assertNotRegex(files["index.html"], r"<style|style=|\son[a-z]+=|<script(?![^>]*\ssrc=)")


class SecretAndLogTest(ServerTestCase):
    def test_secret_is_not_in_state_requests_preview_or_headers(self):
        self.set_state(preset="three")
        self.medications()
        self.medications(secret="wrong-" + SECRET)
        fetch(self.dport, "GET", "/api/agent/mock-panel-1/medications?token=" + SECRET)
        self.post_state({"preset": "soon"})
        for path in ("/mock/state", "/mock/requests", "/mock/preview"):
            status, resp, body = fetch(self.uport, "GET", path)
            self.assertEqual(status, 200)
            self.assertNotIn(SECRET.encode(), body, path)
            self.assertNotIn(SECRET.lower(), str(resp.getheaders()).lower())
        _, resp, body = self.medications()
        self.assertNotIn(SECRET.encode(), body)
        self.assertNotIn(SECRET, str(resp.getheaders()))
        self.assertNotIn("secret", json.dumps(self.get_json(self.uport, "/mock/state")).lower())

    def test_request_log_records_device_requests_only(self):
        self.medications()
        self.medications(secret="x")
        self.medications(headers={"Host": "evil.example"})
        fetch(self.dport, "GET", "/api/agent/mock-panel-1/medications/extra")
        self.get_json(self.uport, "/mock/state")  # 8788 の要求は載せない
        entries = self.get_json(self.uport, "/mock/requests")["requests"]
        self.assertEqual([e["status"] for e in entries], [200, 401, 421, 404])
        for entry in entries:
            self.assertEqual(set(entry), {"time", "method", "path", "status", "bytes", "fault"})
            self.assertEqual(entry["method"], "GET")
        self.assertEqual(entries[0]["path"], "/api/agent/mock-panel-1/medications")
        self.assertEqual(entries[0]["time"], NOW)
        self.assertEqual(entries[0]["bytes"], len(self.medications()[2]))

    def test_request_log_keeps_the_latest_50_and_hides_the_query(self):
        for _ in range(55):
            fetch(self.dport, "GET", "/api/agent/mock-panel-1/medications?token=abc", {server.SECRET_HEADER: SECRET})
        entries = self.get_json(self.uport, "/mock/requests")["requests"]
        self.assertEqual(len(entries), 50)
        self.assertNotIn("token", json.dumps(entries))

    def test_request_log_replaces_odd_paths_with_a_fixed_label(self):
        raw_exchange(self.dport, b"GET /" + b"a" * 120 + "\u00e9\x01".encode("utf-8") + b" HTTP/1.0\r\nHost: 127.0.0.1:%d\r\n\r\n"
                     % self.dport)
        entry = self.get_json(self.uport, "/mock/requests")["requests"][0]
        self.assertEqual(entry["path"], server.LOG_OTHER_PATH)

    def logged(self):
        return self.get_json(self.uport, "/mock/requests")["requests"]

    def test_secret_in_the_path_is_not_logged(self):
        for path in ("/" + SECRET, "/api/" + SECRET, "/api/agent/mock-panel-1/" + SECRET,
                     "/api/agent/mock-panel-1/medications/" + SECRET, "/mock/" + SECRET,
                     "/" + SECRET + "?x=" + SECRET, "/" + SECRET + "#" + SECRET):
            with self.subTest(path=path):
                status, _, _ = fetch(self.dport, "GET", path, {server.SECRET_HEADER: SECRET})
                self.assertEqual(status, 404)
        entries = self.logged()
        self.assertEqual(len(entries), 7)
        self.assertNotIn(SECRET, json.dumps(entries))
        self.assertEqual({e["path"] for e in entries}, {server.LOG_OTHER_PATH})

    def test_secret_in_the_device_id_position_is_not_logged(self):
        # 秘密は deviceId の文字種・長さに合うので、パスの形だけでは弾けない
        self.assertRegex(SECRET, r"[A-Za-z0-9_-]{1,64}")
        for secret_header in (SECRET, "wrong"):
            for path in ("/api/agent/%s/medications" % SECRET, "/api/agent/%s/medications?k=%s" % (SECRET, SECRET)):
                status, _, _ = fetch(self.dport, "GET", path, {server.SECRET_HEADER: secret_header})
                self.assertIn(status, (401, 404))
        entries = self.logged()
        self.assertEqual(len(entries), 4)
        self.assertNotIn(SECRET, json.dumps(entries))
        self.assertEqual({e["path"] for e in entries},
                         {"/api/agent/%s/medications" % server.LOG_OTHER_DEVICE})

    def test_secret_in_the_method_or_a_wrong_host_request_is_not_logged(self):
        raw_exchange(self.dport, ("%s /api/agent/mock-panel-1/medications HTTP/1.0\r\nHost: %s\r\n\r\n"
                                  % (SECRET, SECRET)).encode())
        fetch(self.dport, "GET", "/" + SECRET, {"Host": SECRET})
        fetch(self.dport, "GET", "/api/agent/%s/medications" % SECRET, {"Host": "evil.example"})
        entries = self.logged()
        self.assertEqual(len(entries), 3)
        self.assertEqual([e["status"] for e in entries], [421, 421, 421])
        self.assertNotIn(SECRET, json.dumps(entries))
        self.assertEqual(entries[0]["method"], server.LOG_OTHER_METHOD)

    def test_request_log_keeps_the_real_device_id_and_known_methods(self):
        self.medications()
        fetch(self.dport, "TRACE", "/api/agent/mock-panel-1/medications")
        entries = self.logged()
        self.assertEqual([(e["method"], e["path"]) for e in entries],
                         [("GET", "/api/agent/mock-panel-1/medications"),
                          ("TRACE", "/api/agent/mock-panel-1/medications")])

    def test_server_writes_nothing_to_stderr_or_stdout(self):
        err, out = io.StringIO(), io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(out):
            self.medications()
            self.medications(secret="wrong-secret-value")
            raw_exchange(self.dport, b"GARBAGE\r\n\r\n")
            raw_exchange(self.dport, b"GET /" + b"a" * 70000 + b" HTTP/1.0\r\n\r\n")
            self.post_state(None, raw=b"{not json")
            fetch(self.uport, "GET", "/nope")
        self.assertEqual(err.getvalue(), "")
        self.assertEqual(out.getvalue(), "")

    def test_malformed_request_line_gets_json_error_without_echo(self):
        reply = raw_exchange(self.dport, b"GARBAGE-LINE-123\r\n\r\n")
        self.assertNotIn(b"GARBAGE-LINE-123", reply)
        self.assertNotIn(b"<html", reply.lower())


class BindingTest(ServerTestCase):
    def test_listens_on_127_0_0_1_only(self):
        for srv in (self.device, self.ui):
            self.assertEqual(srv.server_address[0], "127.0.0.1")
            self.assertEqual(srv.socket.getsockname()[0], "127.0.0.1")
        self.assertEqual(server.LISTEN_HOST, "127.0.0.1")
        self.assertEqual((server.DEVICE_PORT, server.UI_PORT), (8787, 8788))

    def test_no_way_to_change_the_listen_address(self):
        self.assertEqual(list(inspect.signature(server.make_servers).parameters), ["app", "device_port", "ui_port"])
        self.assertEqual(list(inspect.signature(server.main).parameters), ["device_port", "ui_port"])
        source = (HERE / "server.py").read_text(encoding="utf-8")
        self.assertNotIn("0.0.0.0", source)
        self.assertNotIn("argparse", source)
        self.assertNotIn("sys.argv", source)
        self.assertEqual(len(re.findall(r"super\(\).__init__\(\(", source)), 1)

    def test_roles_are_separate_servers(self):
        self.assertNotEqual(self.dport, self.uport)
        self.assertEqual((self.device.role, self.ui.role), ("device", "ui"))


class ConfigFileTest(unittest.TestCase):
    def test_config_file_is_0600_in_a_0700_directory(self):
        path = server.create_config_file(SECRET)
        self.addCleanup(server.remove_config_file, path)
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(os.stat(os.path.dirname(path)).st_mode), 0o700)
        config = json.loads(Path(path).read_text(encoding="utf-8"))
        self.assertEqual(config, {"baseUrl": "http://127.0.0.1:8787", "deviceId": "mock-panel-1", "secret": SECRET})
        self.assertLessEqual(len(Path(path).read_bytes()), 4096)

    def test_config_file_is_removed_with_its_directory(self):
        path = server.create_config_file(SECRET)
        directory = os.path.dirname(path)
        server.remove_config_file(path)
        self.assertFalse(os.path.exists(path))
        self.assertFalse(os.path.exists(directory))
        server.remove_config_file(path)  # 二度呼んでもよい

    def test_each_call_uses_a_new_unguessable_directory(self):
        first, second = server.create_config_file("a" * 20), server.create_config_file("b" * 20)
        self.addCleanup(server.remove_config_file, first)
        self.addCleanup(server.remove_config_file, second)
        self.assertNotEqual(os.path.dirname(first), os.path.dirname(second))

    def test_never_overwrites_an_existing_file(self):
        directory = tempfile.mkdtemp()
        self.addCleanup(lambda: __import__("shutil").rmtree(directory, ignore_errors=True))
        target = os.path.join(directory, "api-config.json")
        Path(target).write_text("keep", encoding="utf-8")
        original = tempfile.mkdtemp
        tempfile.mkdtemp = lambda prefix=None: directory
        try:
            with self.assertRaises(FileExistsError):
                server.create_config_file(SECRET)
        finally:
            tempfile.mkdtemp = original
        self.assertEqual(Path(target).read_text(encoding="utf-8"), "keep")

    def test_uses_the_bound_port_in_base_url(self):
        path = server.create_config_file(SECRET, device_port=4321)
        self.addCleanup(server.remove_config_file, path)
        self.assertEqual(json.loads(Path(path).read_text())["baseUrl"], "http://127.0.0.1:4321")

    def test_secret_generator_is_token_urlsafe(self):
        source = (HERE / "server.py").read_text(encoding="utf-8")
        self.assertIn("secrets.token_urlsafe(32)", source)
        self.assertIn("hmac.compare_digest", source)


class ProcessLifecycleTest(unittest.TestCase):
    """実際のプロセスを起動し、SIGTERM / SIGINT で設定ファイルが消えることを確かめる (ポートは 0)。"""

    def start(self):
        env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1")
        proc = subprocess.Popen([sys.executable, "-c", "import server; server.main(0, 0)"], cwd=str(HERE), env=env,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8")
        self.addCleanup(lambda: proc.poll() is None and proc.kill())
        lines = []
        while True:
            line = proc.stdout.readline()
            if not line:
                self.fail("起動前に終了した: " + proc.stderr.read())
            lines.append(line)
            if "Ctrl-C" in line:
                break
        text = "".join(lines)
        config_path = re.search(r"接続設定\s*:\s*(\S+)", text).group(1)
        device_port = int(re.search(r"機器向け API\s*:\s*http://127\.0\.0\.1:(\d+)", text).group(1))
        return proc, text, config_path, device_port

    def check_lifecycle(self, sig):
        proc, banner, config_path, device_port = self.start()
        config = json.loads(Path(config_path).read_text(encoding="utf-8"))
        secret = config["secret"]
        self.assertGreaterEqual(len(secret), 40)
        self.assertEqual(stat.S_IMODE(os.stat(config_path).st_mode), 0o600)
        self.assertNotIn(secret, banner, "起動時の表示に秘密を出さない")
        self.assertIn("push-config.sh", banner)
        status, _, body = fetch(device_port, "GET", "/api/agent/%s/medications" % config["deviceId"],
                                {server.SECRET_HEADER: secret})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["status"], "ok")
        proc.send_signal(sig)
        out, err = proc.communicate(timeout=20)
        self.assertEqual(proc.returncode, 0, err)
        self.assertFalse(os.path.exists(config_path))
        self.assertFalse(os.path.exists(os.path.dirname(config_path)))
        self.assertNotIn(secret, banner + out + err)
        self.assertEqual(err, "")

    def test_sigterm_removes_the_config_file(self):
        self.check_lifecycle(signal.SIGTERM)

    def test_sigint_removes_the_config_file(self):
        self.check_lifecycle(signal.SIGINT)

    def test_each_start_makes_a_new_secret(self):
        secrets_seen = set()
        for _ in range(2):
            proc, _, config_path, _ = self.start()
            secrets_seen.add(json.loads(Path(config_path).read_text())["secret"])
            proc.send_signal(signal.SIGTERM)
            proc.communicate(timeout=20)
        self.assertEqual(len(secrets_seen), 2)


if __name__ == "__main__":
    unittest.main()
