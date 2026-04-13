#!/bin/bash
# ClipBridge Linux Installer
set -e

echo ""
echo "╔════════════════════════════════════╗"
echo "║   ClipBridge Linux Installer       ║"
echo "╚════════════════════════════════════╝"
echo ""

# Check Python
if ! command -v python3 &>/dev/null; then
    echo "❌ Python3 not found. Install it first."
    exit 1
fi

PYTHON=python3

# Install system deps for clipboard on Ubuntu
if command -v apt &>/dev/null; then
    echo "→ Installing system clipboard tools..."
    sudo apt install -y xclip xsel 2>/dev/null || true
fi

# Install Python deps
echo "→ Installing Python dependencies..."
$PYTHON -m pip install --upgrade pip --quiet
$PYTHON -m pip install websockets pyperclip cryptography pystray Pillow --quiet

# Install the script
INSTALL_DIR="$HOME/.local/bin"
mkdir -p "$INSTALL_DIR"
cp clipbridge.py "$INSTALL_DIR/clipbridge"
chmod +x "$INSTALL_DIR/clipbridge"

echo "✓ Installed to $INSTALL_DIR/clipbridge"

# Add to PATH if needed
if [[ ":$PATH:" != *":$INSTALL_DIR:"* ]]; then
    echo ""
    echo "⚠ Add this to your ~/.bashrc or ~/.zshrc:"
    echo "   export PATH=\"\$HOME/.local/bin:\$PATH\""
fi

# Create systemd user service for autostart
SERVICE_DIR="$HOME/.config/systemd/user"
mkdir -p "$SERVICE_DIR"

cat > "$SERVICE_DIR/clipbridge.service" << EOF
[Unit]
Description=ClipBridge Clipboard Sync
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$INSTALL_DIR/clipbridge --no-tray
Restart=always
RestartSec=5
Environment=DISPLAY=:0

[Install]
WantedBy=default.target
EOF

echo ""
echo "✓ Systemd service created."
echo ""
echo "To enable autostart on login:"
echo "   systemctl --user enable clipbridge"
echo "   systemctl --user start clipbridge"
echo ""
echo "To run now:"
echo "   clipbridge"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  First run will ask for your config"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
