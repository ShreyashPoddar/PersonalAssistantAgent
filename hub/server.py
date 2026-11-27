"""PAA hub (P4-T1): a small HTTPS service on the owner's laptop that the phone talks to over the home network.

    python hub/server.py [--port 8765] [--state ~/.paa-hub]

First run creates a self-signed certificate and a random token in --state (outside the repo) and prints the pairing
payload: {"host", "port", "token", "cert_sha256"}. The phone stores it, pins the certificate and sends the token as
"Authorization: Bearer <token>" on every call. Endpoints: GET /health, POST /pair (token check), POST /extract
(chat → to-dos with the laptop's model, see below). Later tasks add the call bridge and voice agent. Data stays on the
owner's LAN; nothing is sent to any third party, and no message text is logged or stored.

/extract: {"prompt": <the phone's task prompt>} → {"output": <verdict JSON text>, "model": ...}. The hub forwards it to
a llama.cpp server on THIS machine (--llm, localhost only), output forced to the verdict schema. --llm-prompt rules
(an untrained model: the data teacher's rules as system prompt) or student (a model fine-tuned on PAA's prompt).
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

VERSION = "0.2"
MAX_BODY = 64 * 1024
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "train"))


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


def owner_from_prompt(prompt: str) -> tuple:
    """'Owner: Shreyash or Shreyas, batch 2028' line of the phone's prompt -> ('Shreyash or Shreyas', 2028)."""
    for line in prompt.splitlines():
        if line.startswith("Owner: "):
            names, _, batch = line[len("Owner: "):].rpartition(", batch ")
            return (names or "the owner"), int(batch) if batch.strip().isdigit() else 2028
    return "the owner", 2028


def llm_messages(prompt: str, mode: str) -> list:
    if mode == "student":
        return [{"role": "user", "content": prompt}]
    from gen_data import RULES
    names, year = owner_from_prompt(prompt)
    return [{"role": "system", "content": RULES.format(names=names, year=year)}, {"role": "user", "content": prompt + "\nJSON:"}]


def ask_llm(url: str, mode: str, prompt: str, timeout: float = 60) -> str:
    import urllib.request
    from eval_real import VERDICT_SCHEMA
    body = json.dumps({"messages": llm_messages(prompt, mode), "temperature": 0, "max_tokens": 500, "chat_template_kwargs": {"enable_thinking": False},
                       "response_format": {"type": "json_schema", "json_schema": {"name": "verdict", "schema": VERDICT_SCHEMA}}}).encode()
    req = urllib.request.Request(url.rstrip("/") + "/v1/chat/completions", body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())["choices"][0]["message"]["content"]


def make_handler(token: str, started: float, llm: str | None = None, llm_mode: str = "rules", ask=ask_llm, inbox=None):
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
                return self._send(200, {"ok": True, "version": VERSION, "uptime_s": int(time.time() - started),
                                        "extract": bool(llm), "whatsapp": inbox is not None})
            if self.path.startswith("/messages"):   # laptop WhatsApp previews the phone hasn't collected yet
                if inbox is None:
                    return self._send(503, {"error": "no WhatsApp reader on this hub"})
                q = self.path.partition("?")[2]
                after = next((int(v) for k, _, v in (x.partition("=") for x in q.split("&")) if k == "after" and v.isdigit()), 0)
                return self._send(200, inbox.after(after))
            self._send(404, {"error": "not found"})

        def do_POST(self):
            if not authorized(self.headers.get("Authorization"), token):
                return self._send(401, {"error": "unauthorized"})
            if self.path == "/pair":
                n = int(self.headers.get("Content-Length") or 0)
                device = json.loads(self.rfile.read(n) or b"{}").get("device", "phone") if n else "phone"
                return self._send(200, {"paired": True, "device": str(device)[:60]})
            if self.path == "/extract":
                if not llm:
                    return self._send(503, {"error": "no model on this hub"})
                n = int(self.headers.get("Content-Length") or 0)
                if not 0 < n <= MAX_BODY:
                    return self._send(413, {"error": "body size"})
                try:
                    prompt = str(json.loads(self.rfile.read(n)).get("prompt") or "")
                except ValueError:
                    return self._send(400, {"error": "bad json"})
                if not prompt:
                    return self._send(400, {"error": "no prompt"})
                try:
                    return self._send(200, {"output": ask(llm, llm_mode, prompt), "model": llm_mode})
                except Exception as e:   # the model server is down / slow: the phone falls back to its own model
                    return self._send(502, {"error": f"model: {type(e).__name__}"})
            self._send(404, {"error": "not found"})

        def log_message(self, format, *args):  # noqa: A002 (base class name)  # paths only, never bodies
            sys.stderr.write(f"{self.address_string()} {self.command} {self.path}\n")

    return Handler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--state", default=str(Path.home() / ".paa-hub"))
    ap.add_argument("--llm", help="llama.cpp server on this machine for /extract, e.g. http://127.0.0.1:8081")
    ap.add_argument("--llm-prompt", choices=["rules", "student"], default="rules")
    ap.add_argument("--wa", action="store_true", help="read WhatsApp Desktop's chat list (read-only) for the phone")
    a = ap.parse_args()
    if a.llm and not a.llm.startswith(("http://127.0.0.1", "http://localhost")):
        sys.exit("--llm must be on this machine (chat text never leaves the owner's devices)")
    cert, key, token = ensure_state(Path(a.state))
    inbox = None
    if a.wa:
        import threading
        from wa_reader import Inbox, Reader
        inbox = Inbox()
        threading.Thread(target=Reader(inbox).run_forever, daemon=True).start()
        print("WhatsApp Desktop reader on (chat list previews only; never opens a chat)")
    payload = pairing_payload(lan_ip(), a.port, token, cert_sha256(cert))
    print("Pairing payload (paste into PAA → Hub, or scan it as a QR):")
    print(json.dumps(payload))
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(cert, key)
    srv = ThreadingHTTPServer(("0.0.0.0", a.port), make_handler(token, time.time(), a.llm, a.llm_prompt, inbox=inbox))
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    print(f"hub listening on https://{payload['host']}:{a.port}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
