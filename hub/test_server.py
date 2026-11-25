"""Hub tests: state creation, pinning fingerprint, auth, and a real HTTPS round trip on localhost.

    cd hub && python test_server.py
"""
import json
import ssl
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import server


class HubTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.cert, cls.key, cls.token = server.ensure_state(cls.tmp)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cls.cert, cls.key)
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(cls.token, 0))
        cls.srv.socket = ctx.wrap_socket(cls.srv.socket, server_side=True)
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def call(self, path, token=None, body=None):
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE  # the phone pins the fingerprint instead
        req = urllib.request.Request(f"https://127.0.0.1:{self.port}{path}", data=json.dumps(body).encode() if body else None)
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req, context=ctx, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_state_is_reused(self):
        cert2, _, token2 = server.ensure_state(self.tmp)
        self.assertEqual(self.token, token2)
        self.assertEqual(server.cert_sha256(self.cert), server.cert_sha256(cert2))
        self.assertEqual(64, len(server.cert_sha256(self.cert)))

    def test_auth(self):
        self.assertTrue(server.authorized(f"Bearer {self.token}", self.token))
        self.assertFalse(server.authorized("Bearer nope", self.token))
        self.assertFalse(server.authorized(None, self.token))

    def test_health_and_pair(self):
        self.assertEqual(401, self.call("/health")[0])
        code, body = self.call("/health", self.token)
        self.assertEqual(200, code)
        self.assertTrue(body["ok"])
        code, body = self.call("/pair", self.token, {"device": "iQOO"})
        self.assertEqual((200, True, "iQOO"), (code, body["paired"], body["device"]))

    def test_payload(self):
        p = server.pairing_payload("192.168.1.9", 8765, "t", "ab" * 32)
        self.assertEqual({"v", "host", "port", "token", "cert_sha256"}, set(p))


if __name__ == "__main__":
    unittest.main()
