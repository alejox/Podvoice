#!/usr/bin/env python3
"""Podcam: usa la camara y el microfono del celular como fuente de video en el PC.

Solo libreria estandar de Python (3.8+). Funciona igual en Windows, macOS y Linux.

  python3 podcam.py            # arranca el servidor
  python3 podcam.py --port 8080 --https-port 8443

Celular (WiFi):  abre https://<IP-del-PC>:8443  (acepta el certificado local)
Celular (USB, Android):  adb reverse tcp:8080 tcp:8080  y abre http://localhost:8080
PC / OBS:  http://localhost:8080/viewer  (en OBS: Fuente de navegador -> Camara Virtual)
"""
import argparse
import json
import os
import shutil
import socket
import ssl
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
STATIC = HERE / "static"
CERT_DIR = Path.home() / ".podcam"

# Estado de senalizacion WebRTC (una sola sala: un celular -> un visor).
# Cada vez que un visor se conecta, sube "gen" y el celular renegocia.
_lock = threading.Lock()
_state = {"gen": 0, "offer": None, "answer": None, "sender_seen": 0.0, "viewer_seen": 0.0}


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def ensure_cert():
    """Certificado autofirmado para HTTPS (el navegador del celular exige
    contexto seguro para usar la camara). Devuelve (cert, key) o None."""
    cert, key = CERT_DIR / "cert.pem", CERT_DIR / "key.pem"
    if cert.exists() and key.exists():
        return cert, key
    openssl = shutil.which("openssl")
    if not openssl:
        return None
    CERT_DIR.mkdir(parents=True, exist_ok=True)
    san = ",".join(["DNS:localhost", "IP:127.0.0.1"] + [f"IP:{ip}" for ip in local_ips()])
    cmd = [openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "825",
           "-keyout", str(key), "-out", str(cert), "-subj", "/CN=Podcam local",
           "-addext", f"subjectAltName={san}"]
    try:
        subprocess.run(cmd, check=True, capture_output=True)
    except (subprocess.CalledProcessError, OSError):
        # LibreSSL antiguo (macOS) no soporta -addext: reintenta sin SAN.
        try:
            subprocess.run(cmd[:-2], check=True, capture_output=True)
        except (subprocess.CalledProcessError, OSError):
            return None
    return cert, key


class Handler(BaseHTTPRequestHandler):
    server_version = "Podcam/1.0"

    def log_message(self, fmt, *args):  # silencia el log de cada sondeo
        if "/api/" not in (args[0] if args else ""):
            sys.stderr.write("[podcam] " + (fmt % args) + "\n")

    def _send(self, code, body=b"", ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj).encode())

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 1_000_000:
            raise ValueError("too large")
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        path, _, query = self.path.partition("?")
        params = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
        now = time.time()
        if path in ("/", "/index.html"):
            return self._file("sender.html")
        if path in ("/viewer", "/viewer.html"):
            return self._file("viewer.html")
        if path == "/api/state":  # cada lado pregunta si hay que renegociar
            with _lock:
                _state["viewer_seen" if params.get("role") == "viewer" else "sender_seen"] = now
                return self._json({"gen": _state["gen"],
                                   "viewer": now - _state["viewer_seen"] < 5})
        if path == "/api/offer":  # el visor espera la oferta del celular
            with _lock:
                _state["viewer_seen"] = now
                o = _state["offer"]
                if o and str(o["gen"]) == params.get("gen"):
                    return self._json(o)
                return self._json({"gen": _state["gen"],
                                   "sender": now - _state["sender_seen"] < 5}, 202)
        if path == "/api/answer":  # el celular espera la respuesta del visor
            with _lock:
                a = _state["answer"]
                if a and str(a["gen"]) == params.get("gen"):
                    return self._json(a)
                return self._json({}, 202)
        self._send(404, b"not found", "text/plain")

    def do_POST(self):
        try:
            data = self._body()
        except ValueError:
            return self._send(400, b"bad request", "text/plain")
        with _lock:
            # visor nuevo o celular reiniciado -> nueva negociacion
            if self.path in ("/api/join", "/api/reset"):
                _state["gen"] += 1
                _state["offer"] = _state["answer"] = None
                return self._json({"gen": _state["gen"]})
            if self.path in ("/api/offer", "/api/answer"):
                if data.get("gen") != _state["gen"] or not data.get("sdp"):
                    return self._json({"error": "stale"}, 409)
                _state[self.path.rsplit("/", 1)[1]] = {"gen": data["gen"], "sdp": data["sdp"],
                                                        "type": data.get("type")}
                return self._json({"ok": True})
        self._send(404, b"not found", "text/plain")

    def _file(self, name):
        self._send(200, (STATIC / name).read_bytes(), "text/html; charset=utf-8")


def serve(server):
    try:
        server.serve_forever()
    except Exception as e:  # noqa: BLE001
        print(f"[podcam] servidor detenido: {e}")


def main():
    ap = argparse.ArgumentParser(description="Podcam: camara del celular para el PC")
    ap.add_argument("--port", type=int, default=8080, help="HTTP (visor/OBS y USB)")
    ap.add_argument("--https-port", type=int, default=8443, help="HTTPS (celular por WiFi)")
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()

    http = ThreadingHTTPServer((args.host, args.port), Handler)
    threading.Thread(target=serve, args=(http,), daemon=True).start()

    print("\n  Podcam listo\n")
    print(f"  Visor / OBS (Fuente de navegador):  http://localhost:{args.port}/viewer")
    print(f"  Celular por USB (Android):          adb reverse tcp:{args.port} tcp:{args.port}")
    print(f"                                      y abre http://localhost:{args.port}")

    cert = ensure_cert()
    if cert:
        https = ThreadingHTTPServer((args.host, args.https_port), Handler)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(str(cert[0]), str(cert[1]))
        https.socket = ctx.wrap_socket(https.socket, server_side=True)
        threading.Thread(target=serve, args=(https,), daemon=True).start()
        for ip in local_ips() or ["<IP-del-PC>"]:
            print(f"  Celular por WiFi:                   https://{ip}:{args.https_port}")
        print("     (el celular avisara que el certificado no es de confianza: es normal, acepta)")
    else:
        print("  [!] Sin openssl no hay HTTPS: por WiFi el celular no permitira usar la camara.")
    print("\n  Ctrl+C para salir\n")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
