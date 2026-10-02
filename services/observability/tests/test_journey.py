"""Execute the real k6 script against local HTTP/WebSocket fixtures.

No production credentials, Firebase, PDFs on disk or production writes.
"""
import asyncio
import base64
import json
import os
import shutil
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from websockets.asyncio.server import serve

ROOT = Path(__file__).resolve().parents[1]
K6 = os.environ.get("K6_BIN") or shutil.which("k6")


@unittest.skipUnless(K6, "Set K6_BIN to run the real k6 journey integration tests")
class JourneyTests(unittest.TestCase):
    def execute(self, failure=None):
        ready = threading.Event()
        state = {"peers": set(), "tickets": set(), "requests": []}
        loop = asyncio.new_event_loop()

        def process(path):
            state["requests"].append(path)
            status = 200
            if path.endswith("signInWithPassword"):
                status = 401 if failure == "login" else 200
                body = {"idToken": "mock-token", "localId": "mock-monitor-user"}
            elif path.endswith("profile/sync"):
                body = {"status": "synced"}
            elif path.endswith("compile"):
                pdf = b"not a PDF" if failure == "pdf" else b"%PDF-1.4\nmonitor\n%%EOF"
                body = {"status": "compiled", "pdf_base64": base64.b64encode(pdf).decode()}
            elif path.endswith("tickets"):
                status = 201
                ticket = f"ticket-{len(state['tickets'])}"
                state["tickets"].add(ticket)
                body = {"ticket": ticket}
            else:
                status, body = 404, {}
            return status, body

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                status, body = process(urlsplit(self.path).path)
                encoded = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

            def log_message(self, *args):
                pass

        http_server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        http_thread = threading.Thread(target=http_server.serve_forever, daemon=True)
        http_thread.start()

        async def websocket(peer):
            state["peers"].add(peer)
            try:
                async for message in peer:
                    if failure != "broadcast":
                        for other in list(state["peers"]):
                            if other is not peer:
                                await other.send(message)
            finally:
                state["peers"].discard(peer)

        async def start():
            server = await serve(websocket, "127.0.0.1", 0)
            state["server"] = server
            state["port"] = server.sockets[0].getsockname()[1]
            ready.set()

        def run():
            asyncio.set_event_loop(loop)
            loop.run_until_complete(start())
            loop.run_forever()

        thread = threading.Thread(target=run, daemon=True)
        thread.start()
        self.assertTrue(ready.wait(5))
        try:
            with tempfile.TemporaryDirectory() as directory:
                secret_file = Path(directory) / "secrets.txt"
                secret_file.write_text("skolab-firebase-api-key=mock\nskolab-monitor-email=monitor@example.test\nskolab-monitor-password=mock\nskolab-monitor-workspace=mock-workspace\n")
                url = f"http://127.0.0.1:{http_server.server_port}"
                environment = {key: value for key, value in os.environ.items() if key.upper() not in {"HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"}}
                result = subprocess.run([K6, "run", "--quiet", "--secret-source", f"file={secret_file}", str(ROOT / "journey.js")],
                    env={**environment, "NO_PROXY": "127.0.0.1", "SKOLAB_GATEWAY_URL": url, "SKOLAB_FIREBASE_AUTH_URL": url,
                         "SKOLAB_WS_URL": f"ws://127.0.0.1:{state['port']}"},
                    capture_output=True, text=True, timeout=35, check=False)
                return result, state
        finally:
            async def close():
                state["server"].close()
                await state["server"].wait_closed()
            asyncio.run_coroutine_threadsafe(close(), loop).result(timeout=5)
            loop.call_soon_threadsafe(loop.stop)
            thread.join(timeout=5)
            loop.close()
            http_server.shutdown()
            http_server.server_close()
            http_thread.join(timeout=5)

    def test_complete_journey_succeeds(self):
        result, state = self.execute()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(state["tickets"]), 2)
        self.assertTrue(any(path.endswith("compile") for path in state["requests"]))

    def test_invalid_pdf_fails_before_collaboration(self):
        result, state = self.execute("pdf")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(state["tickets"]), 0)

    def test_failed_login_does_not_call_application(self):
        result, state = self.execute("login")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(state["requests"]), 1)

    def test_missing_message_fails_the_journey(self):
        result, _ = self.execute("broadcast")
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
