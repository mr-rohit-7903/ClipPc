#!/usr/bin/env python3
"""
ClipBridge Linux Client
Syncs clipboard between Linux and Android devices via a relay server.

Requirements:
    pip install websockets pyperclip cryptography pystray Pillow
    
    On Ubuntu/Debian also:
    sudo apt install xclip   (for X11)
    # or xsel, wl-clipboard for Wayland
"""

import asyncio
import hashlib
import json
import os
import platform
import socket
import sys
import threading
import time
import base64
import logging
import configparser
from pathlib import Path
from getpass import getpass

# --- Optional GUI tray support ---
try:
    import pystray
    from PIL import Image, ImageDraw
    HAS_TRAY = True
except ImportError:
    HAS_TRAY = False

try:
    import pyperclip
    HAS_PYPERCLIP = True
except ImportError:
    HAS_PYPERCLIP = False

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    HAS_CRYPTO = True
except ImportError:
    HAS_CRYPTO = False

try:
    import websockets
    HAS_WS = True
except ImportError:
    HAS_WS = False

# ─────────────────────────────────────────────
CONFIG_DIR = Path.home() / ".config" / "clipbridge"
CONFIG_FILE = CONFIG_DIR / "config.ini"
LOG_FILE = CONFIG_DIR / "clipbridge.log"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler(LOG_FILE, encoding="utf-8") if CONFIG_DIR.exists() else logging.NullHandler()
    ]
)
log = logging.getLogger("clipbridge")

# ─────────────────────────────────────────────
DEFAULT_SERVER = "ws://YOUR_SERVER_IP:8765"  # Change this after deploying relay


def derive_key(secret: str) -> bytes:
    """Derive 32-byte AES key from shared secret."""
    return hashlib.sha256(secret.encode()).digest()


def derive_room(secret: str) -> str:
    """Derive room ID from shared secret (double hash so key stays secret)."""
    return hashlib.sha256(hashlib.sha256(secret.encode()).digest()).hexdigest()


def encrypt(plaintext: str, key: bytes) -> dict:
    if not HAS_CRYPTO:
        return {"data": base64.b64encode(plaintext.encode()).decode(), "iv": ""}
    aesgcm = AESGCM(key)
    iv = os.urandom(12)
    ct = aesgcm.encrypt(iv, plaintext.encode("utf-8"), None)
    return {
        "data": base64.b64encode(ct).decode(),
        "iv": base64.b64encode(iv).decode()
    }


def decrypt(payload: dict, key: bytes) -> str:
    if not HAS_CRYPTO or not payload.get("iv"):
        return base64.b64decode(payload["data"]).decode()
    aesgcm = AESGCM(key)
    iv = base64.b64decode(payload["iv"])
    ct = base64.b64decode(payload["data"])
    return aesgcm.decrypt(iv, ct, None).decode("utf-8")


# ─────────────────────────────────────────────
class ClipboardWatcher:
    """Polls clipboard for changes."""

    def __init__(self):
        self._last = self._read()

    def _read(self) -> str:
        if HAS_PYPERCLIP:
            try:
                return pyperclip.paste() or ""
            except Exception:
                return ""
        return ""

    def _write(self, text: str):
        if HAS_PYPERCLIP:
            try:
                pyperclip.copy(text)
            except Exception as e:
                log.warning(f"Could not write to clipboard: {e}")

    def get_if_changed(self) -> str | None:
        current = self._read()
        if current != self._last and current:
            self._last = current
            return current
        return None

    def set(self, text: str):
        self._last = text  # Prevent echo-back
        self._write(text)


# ─────────────────────────────────────────────
class ClipBridgeClient:
    def __init__(self, server_url: str, secret: str, device_id: str):
        self.server_url = server_url
        self.key = derive_key(secret)
        self.room = derive_room(secret)
        self.device_id = device_id
        self.watcher = ClipboardWatcher()
        self._ws = None
        self._running = False
        self._send_queue = asyncio.Queue()
        self.status = "disconnected"

    async def connect_and_run(self):
        self._running = True
        backoff = 1
        while self._running:
            try:
                log.info(f"Connecting to {self.server_url} ...")
                async with websockets.connect(
                    self.server_url,
                    ping_interval=20,
                    ping_timeout=10,
                    close_timeout=5
                ) as ws:
                    self._ws = ws
                    self.status = "connected"
                    backoff = 1
                    log.info("Connected!")

                    # Join room
                    await ws.send(json.dumps({
                        "type": "join",
                        "room": self.room,
                        "deviceId": self.device_id
                    }))

                    await asyncio.gather(
                        self._recv_loop(ws),
                        self._send_loop(ws),
                        self._watch_loop()
                    )
            except Exception as e:
                self.status = "disconnected"
                self._ws = None
                log.warning(f"Connection lost: {e}. Reconnecting in {backoff}s...")
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 30)

    async def _recv_loop(self, ws):
        async for raw in ws:
            try:
                msg = json.loads(raw)
                if msg.get("type") == "clip":
                    text = decrypt(msg, self.key)
                    if text:
                        self.watcher.set(text)
                        preview = text[:60].replace("\n", "↵")
                        log.info(f"← Received clip from {msg.get('from', '?')}: {preview!r}")
                elif msg.get("type") == "joined":
                    log.info(f"Joined room. {msg.get('peers', 0)} other device(s) online.")
                elif msg.get("type") == "ack":
                    pass  # silent
                elif msg.get("type") == "error":
                    log.error(f"Server error: {msg.get('message')}")
            except Exception as e:
                log.warning(f"Error processing message: {e}")

    async def _send_loop(self, ws):
        while True:
            text = await self._send_queue.get()
            try:
                payload = encrypt(text, self.key)
                await ws.send(json.dumps({"type": "clip", **payload}))
                preview = text[:60].replace("\n", "↵")
                log.info(f"→ Sent clip: {preview!r}")
            except Exception as e:
                log.warning(f"Send failed: {e}")

    async def _watch_loop(self):
        while True:
            changed = self.watcher.get_if_changed()
            if changed:
                await self._send_queue.put(changed)
            await asyncio.sleep(0.3)

    def stop(self):
        self._running = False


# ─────────────────────────────────────────────
def load_or_create_config():
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    config = configparser.ConfigParser()

    if CONFIG_FILE.exists():
        config.read(CONFIG_FILE)
        return config

    print("\n╔══════════════════════════════════╗")
    print("║   ClipBridge - First Time Setup  ║")
    print("╚══════════════════════════════════╝\n")

    server = input(f"Relay server URL [{DEFAULT_SERVER}]: ").strip() or DEFAULT_SERVER
    secret = getpass("Shared secret (same on all devices): ")
    device_id = input(f"Device name [{socket.gethostname()}]: ").strip() or socket.gethostname()

    config["clipbridge"] = {
        "server": server,
        "secret": secret,
        "device_id": device_id
    }

    with open(CONFIG_FILE, "w") as f:
        config.write(f)

    os.chmod(CONFIG_FILE, 0o600)
    print(f"\n✓ Config saved to {CONFIG_FILE}\n")
    return config


def make_tray_icon(client: ClipBridgeClient):
    """Create system tray icon with status."""
    def create_image(connected: bool):
        img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        color = "#00CC66" if connected else "#FF4444"
        d.ellipse([8, 8, 56, 56], fill=color)
        d.text((20, 20), "CB", fill="white")
        return img

    def on_quit(icon, item):
        client.stop()
        icon.stop()

    def on_status(icon, item):
        pass  # Just a label

    menu = pystray.Menu(
        pystray.MenuItem(lambda _: f"Status: {client.status}", on_status, enabled=False),
        pystray.MenuItem(f"Device: {client.device_id}", on_status, enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Quit ClipBridge", on_quit)
    )

    icon = pystray.Icon("ClipBridge", create_image(False), "ClipBridge", menu)

    def update_icon():
        while client._running:
            icon.icon = create_image(client.status == "connected")
            time.sleep(2)

    threading.Thread(target=update_icon, daemon=True).start()
    return icon


def main():
    # Dependency check
    missing = []
    if not HAS_WS:
        missing.append("websockets")
    if not HAS_PYPERCLIP:
        missing.append("pyperclip")
    if not HAS_CRYPTO:
        missing.append("cryptography")

    if missing:
        print(f"Missing dependencies: {', '.join(missing)}")
        print(f"Install with: pip install {' '.join(missing)}")
        sys.exit(1)

    config = load_or_create_config()
    cfg = config["clipbridge"]

    client = ClipBridgeClient(
        server_url=cfg["server"],
        secret=cfg["secret"],
        device_id=cfg["device_id"]
    )

    log.info(f"ClipBridge starting | device={cfg['device_id']}")

    loop = asyncio.new_event_loop()

    if HAS_TRAY and "--no-tray" not in sys.argv:
        icon = make_tray_icon(client)

        def run_async():
            asyncio.set_event_loop(loop)
            loop.run_until_complete(client.connect_and_run())

        t = threading.Thread(target=run_async, daemon=True)
        t.start()
        icon.run()  # Blocking, runs tray on main thread
    else:
        log.info("Running in terminal mode (no tray). Ctrl+C to stop.")
        try:
            loop.run_until_complete(client.connect_and_run())
        except KeyboardInterrupt:
            log.info("Stopped.")
            client.stop()


if __name__ == "__main__":
    main()
