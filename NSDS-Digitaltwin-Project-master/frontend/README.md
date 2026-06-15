# IoT Network Dashboard

A compact single-page vanilla JS dashboard for visualising an IoT mesh network in real time.
No build step, no npm, no framework — just open `index.html`.

---

## Quick start

### Option A — file open (simplest)
```
open index.html
```
Works in Chrome/Firefox. Edge blocks SSE from `file://`; use option B instead.

### Option B — local HTTP server
```bash
# Python 3
python -m http.server 3000
# then visit http://localhost:3000
```

### Option C — Docker / remote backend
1. Open the dashboard in a browser.
2. In the **API base** field (bottom of the HUD), type the backend URL, e.g.
   `http://host.docker.internal:8080`
3. Click **Apply**.

---

## Demo mode (no backend needed)

Toggle **Demo** in the HUD. The built-in simulator immediately fires realistic
events at ~1.2 s intervals so you can explore the UI without any backend.

---

## Live mode

The dashboard connects to `GET /events` as a Server-Sent Events stream.
Each message must be a single JSON line:

```json
{"type":"TRAFFIC","moteId":1,"timestamp":1716480000000}
{"type":"PARENT_CHANGED","moteId":2,"newParentId":1}
{"type":"PERIOD_UPDATED","moteId":1,"newT":9}
{"type":"CRASH","moteId":3}
```

The SSE endpoint must respond with `Content-Type: text/event-stream` and send
data framed as:
```
data: {"type":"TRAFFIC","moteId":1}\n\n
```

The client reconnects automatically with exponential back-off (1 s → 30 s).

---

## Period update

Clicking a mote opens the inspector panel. Enter a new period value and click
**Send** to `POST /updateT`:

```json
{ "moteId": 2, "newT": 15 }
```

---

## Minimal backend example (Node.js)

```js
const http = require('http');
http.createServer((req, res) => {
  if (req.url === '/events') {
    res.writeHead(200, {
      'Content-Type':  'text/event-stream',
      'Cache-Control': 'no-cache',
      'Access-Control-Allow-Origin': '*',
    });
    setInterval(() => {
      const evt = JSON.stringify({ type: 'TRAFFIC', moteId: Math.ceil(Math.random()*5), timestamp: Date.now() });
      res.write(`data: ${evt}\n\n`);
    }, 1000);
  } else if (req.url === '/updateT' && req.method === 'POST') {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
    res.end('{"ok":true}');
  } else {
    res.writeHead(404); res.end();
  }
}).listen(8080, () => console.log('Backend on :8080'));
```

---

## Files

| File | Purpose |
|------|---------|
| `index.html` | Shell, HUD structure, inspector panel |
| `main.js` | SSE client, demo simulator, canvas renderer, force layout |
| `styles.css` | Dark theme, HUD, inspector, animations |
| `README.md` | This file |
