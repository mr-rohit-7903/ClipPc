/**
 * ClipBridge Relay Server
 * Relays clipboard data between devices using WebSocket
 * Deploy on any VPS / cloud server with a public IP
 */

const WebSocket = require("ws");
const http = require("http");

const PORT = process.env.PORT || 8765;

// In-memory rooms: roomId -> Set<ws>
const rooms = new Map();

const server = http.createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ status: "ok", rooms: rooms.size }));
  } else {
    res.writeHead(404);
    res.end("ClipBridge Relay Server");
  }
});

const wss = new WebSocket.Server({ server });

function log(msg) {
  console.log(`[${new Date().toISOString()}] ${msg}`);
}

wss.on("connection", (ws, req) => {
  const ip = req.headers["x-forwarded-for"] || req.socket.remoteAddress;
  log(`New connection from ${ip}`);

  ws.roomId = null;
  ws.deviceId = null;
  ws.isAlive = true;

  ws.on("pong", () => {
    ws.isAlive = true;
  });

  ws.on("message", (data) => {
    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch (e) {
      ws.send(JSON.stringify({ type: "error", message: "Invalid JSON" }));
      return;
    }

    switch (msg.type) {
      case "join": {
        // msg.room = SHA256(secret) used as room identifier
        // msg.deviceId = unique device name
        if (!msg.room || !msg.deviceId) {
          ws.send(JSON.stringify({ type: "error", message: "Missing room or deviceId" }));
          return;
        }

        // Leave previous room if any
        if (ws.roomId && rooms.has(ws.roomId)) {
          rooms.get(ws.roomId).delete(ws);
        }

        ws.roomId = msg.room;
        ws.deviceId = msg.deviceId;

        if (!rooms.has(ws.roomId)) {
          rooms.set(ws.roomId, new Set());
        }
        rooms.get(ws.roomId).add(ws);

        const peerCount = rooms.get(ws.roomId).size - 1;
        ws.send(JSON.stringify({
          type: "joined",
          room: ws.roomId,
          deviceId: ws.deviceId,
          peers: peerCount
        }));

        log(`Device "${msg.deviceId}" joined room (${peerCount} peers online)`);
        break;
      }

      case "clip": {
        // msg.data = encrypted clipboard content (base64)
        // msg.iv   = AES-GCM IV (base64)
        // msg.tag  = AES-GCM tag (base64, optional if combined)
        if (!ws.roomId) {
          ws.send(JSON.stringify({ type: "error", message: "Not in a room" }));
          return;
        }
        if (!msg.data) {
          ws.send(JSON.stringify({ type: "error", message: "No data" }));
          return;
        }

        const room = rooms.get(ws.roomId);
        if (!room) return;

        let relayed = 0;
        room.forEach((peer) => {
          if (peer !== ws && peer.readyState === WebSocket.OPEN) {
            peer.send(JSON.stringify({
              type: "clip",
              from: ws.deviceId,
              data: msg.data,
              iv: msg.iv,
              ts: Date.now()
            }));
            relayed++;
          }
        });

        log(`Clip from "${ws.deviceId}" relayed to ${relayed} peer(s)`);
        ws.send(JSON.stringify({ type: "ack", relayed }));
        break;
      }

      case "ping": {
        ws.send(JSON.stringify({ type: "pong" }));
        break;
      }

      default:
        ws.send(JSON.stringify({ type: "error", message: `Unknown type: ${msg.type}` }));
    }
  });

  ws.on("close", () => {
    if (ws.roomId && rooms.has(ws.roomId)) {
      rooms.get(ws.roomId).delete(ws);
      if (rooms.get(ws.roomId).size === 0) {
        rooms.delete(ws.roomId);
      }
    }
    log(`Device "${ws.deviceId || "unknown"}" disconnected`);
  });

  ws.on("error", (err) => {
    log(`WebSocket error for "${ws.deviceId}": ${err.message}`);
  });
});

// Heartbeat to detect dead connections
const heartbeat = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) {
      ws.terminate();
      return;
    }
    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

wss.on("close", () => clearInterval(heartbeat));

server.listen(PORT, () => {
  log(`ClipBridge relay server running on port ${PORT}`);
});
