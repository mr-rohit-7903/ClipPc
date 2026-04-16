# ClipBridge 📋

> Instant clipboard sync between Android and Linux/Windows — over any network, end-to-end encrypted.

Copy on your phone → paste on your PC. Copy on your PC → paste on your phone. Works over mobile data, different Wi-Fi networks, VPNs — anything with internet.

---

## How It Works

```
┌─────────────┐         ┌──────────────────┐         ┌─────────────────┐
│  Your Phone │◄───────►│  Relay Server    │◄───────►│  Your Linux PC  │
│ (Android)   │  wss:// │  (VPS/Cloud)     │  wss:// │  (Python daemon)│
└─────────────┘         └──────────────────┘         └─────────────────┘
        AES-256-GCM encrypted — server never sees plaintext
```

1. **Relay Server** — a tiny Node.js WebSocket server you deploy once on any VPS (free tier works fine).
2. **Linux Client** — a Python daemon that watches your clipboard and syncs via WebSocket.
3. **Android App** — a Kotlin app with a foreground service that does the same on your phone.

All clipboard data is **AES-256-GCM encrypted** with your shared secret before leaving your device. The relay server only sees ciphertext.

---

## Quick Start

### Step 1 — Deploy the Relay Server

You need a server with a public IP. Options:
- **Oracle Cloud Free Tier** (always free, recommended)
- **Railway.app** (free tier, easiest)
- **DigitalOcean** ($4/mo droplet)
- **Any VPS** with Node.js 16+

#### Option A: Railway (Easiest — no server needed)
1. Go to [railway.app](https://railway.app) → New Project → Deploy from GitHub
2. Fork this repo, point Railway to `relay-server/`
3. Railway gives you a public URL like `wss://clipbridge-production.up.railway.app`

#### Option B: Any VPS / Ubuntu Server
```bash
# On your server:
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

cd relay-server/
npm install
npm start
# Runs on port 8765

# To run as a background service:
sudo npm install -g pm2
pm2 start server.js --name clipbridge
pm2 save
pm2 startup
```

#### Option C: Docker
```bash
cd relay-server/
docker build -t clipbridge-relay .
docker run -d -p 8765:8765 --restart always --name clipbridge clipbridge-relay
```

#### Open Firewall Port
```bash
# On Ubuntu with ufw:
sudo ufw allow 8765/tcp

# On Oracle Cloud: add ingress rule for port 8765 in Security Lists
```

#### Verify it's running:
```
curl http://YOUR_SERVER_IP:8765/health
# → {"status":"ok","rooms":0}
```

> **TLS/WSS**: For production, put nginx in front with a free Let's Encrypt cert.  
> Your server URL becomes `wss://yourdomain.com` instead of `ws://IP:8765`.

---

### Step 2 — Linux Client Setup

#### Install dependencies
```bash
# Ubuntu/Debian
sudo apt install -y python3 python3-pip xclip

pip3 install websockets pyperclip cryptography pystray Pillow
```

#### Run the installer
```bash
cd linux-client/
chmod +x install.sh
./install.sh
```

#### First run (interactive setup)
```bash
clipbridge
```

You'll be prompted for:
- **Relay server URL** — e.g. `ws://YOUR_SERVER_IP:8765`
- **Shared secret** — pick anything, same on all devices (e.g. `mysecret42`)
- **Device name** — e.g. `my-laptop`

Config is saved to `~/.config/clipbridge/config.ini` (permissions: 600).

#### Autostart on login
```bash
systemctl --user enable clipbridge
systemctl --user start clipbridge
```

#### Run without tray (headless/Wayland)
```bash
clipbridge --no-tray
```

---

### Step 3 — Android App Setup

#### Build the APK
1. Open `android-app/` in **Android Studio** (Hedgehog or newer)
2. Let Gradle sync
3. Build → Generate Signed APK (or just Run for debug)

#### Configure the app
1. Open ClipBridge on your phone
2. Enter:
   - **Relay Server URL**: `ws://YOUR_SERVER_IP:8765`
   - **Shared Secret**: same secret as Linux client
   - **Device Name**: e.g. `Pixel 7`
3. Tap **Save Settings**
4. Tap **Start Sync**

The app runs as a foreground service — you'll see a persistent notification. It auto-starts after reboot.

> **Android 10+ clipboard restriction**: Android restricts background clipboard reads for privacy. ClipBridge works around this by running as a foreground service. You may need to copy something while the app is in the foreground the first time.

---

## Configuration Reference

### Linux (`~/.config/clipbridge/config.ini`)
```ini
[clipbridge]
server = ws://YOUR_SERVER_IP:8765
secret = your_shared_secret
device_id = my-laptop
```

Edit manually or delete the file to re-run setup.

### Relay Server Environment Variables
| Variable | Default | Description |
|----------|---------|-------------|
| `PORT`   | `8765`  | Port to listen on |

---

## Security

- **AES-256-GCM** encryption with a random 96-bit IV per message
- Your secret is never sent to the server — it's used locally to derive the AES key
- The room ID is `SHA256(SHA256(secret))` — double-hashed so the key can't be reversed
- The relay server is stateless and stores no clipboard data
- TLS (WSS) is recommended for production deployments

---

## Windows Support

The Linux Python client works on Windows too:

```powershell
pip install websockets pyperclip cryptography pystray Pillow
python clipbridge.py
```

`pyperclip` works natively on Windows. The system tray works via `pystray`.

---

## Troubleshooting

**Clipboard not syncing on Linux:**
```bash
# Check xclip is installed
which xclip
# or for Wayland:
sudo apt install wl-clipboard
```

**Connection refused:**
- Check firewall: `sudo ufw status`
- Check server is running: `curl http://YOUR_IP:8765/health`
- Check URL format: must be `ws://` not `http://`

**Android app not syncing in background:**
- Disable battery optimization for ClipBridge
- Settings → Apps → ClipBridge → Battery → Unrestricted

**Logs:**
```bash
# Linux client logs
tail -f ~/.config/clipbridge/clipbridge.log

# Systemd logs
journalctl --user -u clipbridge -f

# Relay server logs
pm2 logs clipbridge
```

---

## Project Structure

```
clipbridge/
├── relay-server/          # Node.js WebSocket relay
│   ├── server.js
│   ├── package.json
│   └── Dockerfile
├── linux-client/          # Python clipboard daemon
│   ├── clipbridge.py
│   └── install.sh
└── android-app/           # Kotlin Android app
    └── app/src/main/
        ├── java/com/clipbridge/
        │   ├── MainActivity.kt
        │   ├── ClipBridgeService.kt
        │   ├── CryptoHelper.kt
        │   └── BootReceiver.kt
        └── res/
```
