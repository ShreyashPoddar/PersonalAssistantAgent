"""PAA hub (P4-T1): a small HTTPS service on the owner's laptop that the phone talks to over the home network.

    python hub/server.py [--port 8765] [--state ~/.paa-hub]

First run creates a self-signed certificate and a random token in --state (outside the repo) and prints the pairing
payload: {"host", "port", "token", "cert_sha256"}. The phone stores it, pins the certificate and sends the token as
"Authorization: Bearer <token>" on every call. Endpoints: GET /health, POST /pair (token check). Later tasks add the
call bridge and voice agent. Data stays on the owner's LAN; nothing is sent to any third party.
"""
import argparse
import hashlib
import hmac
import json
import os
import secrets
import socket
import ssl
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

VERSION = "0.1"


def ensure_state(state: Path) -> tuple[Path, Path, str]:
    """Cert, key and token in [state]; created once (openssl), reused after."""
    state.mkdir(parents=True, exist_ok=True)
    cert, key, tok = state / "hub.crt", state / "hub.key", state / "token"
    if not (cert.exists() and key.exists()):
        subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
                        "-nodes", "-keyout", str(key), "-out", str(cert), "-days", "3650", "-subj", "/CN=paa-hub"],
                       check=True, capture_output=True)
    if not tok.exists():
        tok.write_text(secrets.token_urlsafe(32))
    try:
        os.chmod(key, 0o600); os.chmod(tok, 0o600)
    except OSError:
        pass
    return cert, key, tok.read_text().strip()


def cert_sha256(cert: Path) -> str:
    der = ssl.PEM_cert_to_DER_cert(cert.read_text())
    return hashlib.sha256(der).hexdigest()


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))  # no packet is sent; picks the outgoing interface
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def pairing_payload(host: str, port: int, token: str, fp: str) -> dict:
    return {"v": 1, "host": host, "port": port, "token": token, "cert_sha256": fp}


def authorized(header: str | None, token: str) -> bool:
    if not header or not header.startswith("Bearer "):
        return False
    return hmac.compare_digest(header[len("Bearer "):].strip(), token)


def make_handler(token: str, started: float):
    class Handler(BaseHTTPRequestHandler):
        server_version = f"paa-hub/{VERSION}"

        def _send(self, code: int, body: dict):
            data = json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if not authorized(self.headers.get("Authorization"), token):
                return self._send(401, {"error": "unauthorized"})
            if self.path == "/health":
                return self._send(200, {"ok": True, "version": VERSION, "uptime_s": int(time.time() - started)})
            self._send(404, {"error": "not found"})

        def do_POST(self):
            if not authorized(self.headers.get("Authorization"), token):
                return self._send(401, {"error": "unauthorized"})
            if self.path == "/pair":
                n = int(self.headers.get("Content-Length") or 0)
                device = json.loads(self.rfile.read(n) or b"{}").get("device", "phone") if n else "phone"
                return self._send(200, {"paired": True, "device": str(device)[:60]})
            self._send(404, {"error": "not found"})

        def log_message(self, format, *args):  # noqa: A002 (base class name)  # paths only, never bodies
            sys.stderr.write(f"{self.address_string()} {self.command} {self.path}\n")

    return Handler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--state", default=str(Path.home() / ".paa-hub"))
    a = ap.parse_args()
    cert, key, token = ensure_state(Path(a.state))
    payload = pairing_payload(lan_ip(), a.port, token, cert_sha256(cert))
    print("Pairing payload (paste into PAA → Hub, or scan it as a QR):")
    print(json.dumps(payload))
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(cert, key)
    srv = ThreadingHTTPServer(("0.0.0.0", a.port), make_handler(token, time.time()))
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    print(f"hub listening on https://{payload['host']}:{a.port}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
