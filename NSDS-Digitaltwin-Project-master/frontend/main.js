/**
 * IoT Network Dashboard — main.js
 * Plain vanilla JS / Canvas 2D. No bundler required.
 * Open index.html directly or serve with: python -m http.server
 */

/* ═══════════════════════════════════════════════════════════════
   CONFIG
═══════════════════════════════════════════════════════════════ */
const CFG = {
  apiBase: 'http://localhost:8080',     // overridable via HUD input
  sseReconnectMin: 1000,                // ms — initial reconnect delay
  sseReconnectMax: 30000,               // ms — max reconnect delay
  demoRate: 1200,                       // ms — demo event interval
  moteRadius: 18,                       // canvas px
  pulseLifetime: 900,                   // ms — traffic pulse animation
  hazeLifetime: 600,                    // ms — period-update halo
  labelFont: '500 11px JetBrains Mono, monospace',
};

/* ═══════════════════════════════════════════════════════════════
   STATE
═══════════════════════════════════════════════════════════════ */
// motes: Map<id, { id, parent, T, crashed, x, y, vx, vy }>
const motes = new Map();

// active canvas animations: Array<{ type, ... }>
const anims = [];

// HUD counters
let statTraffic = 0;
let statCrashes = 0;

/* ═══════════════════════════════════════════════════════════════
   CANVAS SETUP
═══════════════════════════════════════════════════════════════ */
let canvas, ctx, W = 0, H = 0;

function initCanvas() {
  canvas = document.getElementById('scene');
  ctx = canvas.getContext('2d');
  resize();
}

function resize() {
  if (!canvas) return;
  W = canvas.width  = window.innerWidth;
  H = canvas.height = window.innerHeight;
}
window.addEventListener('resize', resize);

/* ═══════════════════════════════════════════════════════════════
   LAYOUT — force-directed spring layout (simple Euler)
═══════════════════════════════════════════════════════════════ */
const LAYOUT = {
  repulsion: 3200,   // node–node push
  spring: 0.012,     // edge attraction
  restLen: 160,      // desired edge length
  damping: 0.82,     // velocity damping per frame
  centerPull: 0.003, // weak pull toward canvas centre
};

function layoutTick(dt) {
  const nodes = [...motes.values()];
  if (nodes.length === 0) return;

  // repulsion between all pairs
  for (let i = 0; i < nodes.length; i++) {
    for (let j = i + 1; j < nodes.length; j++) {
      const a = nodes[i], b = nodes[j];
      const dx = b.x - a.x, dy = b.y - a.y;
      const dist = Math.sqrt(dx * dx + dy * dy) || 1;
      const force = LAYOUT.repulsion / (dist * dist);
      const fx = (dx / dist) * force;
      const fy = (dy / dist) * force;
      a.vx -= fx; a.vy -= fy;
      b.vx += fx; b.vy += fy;
    }
  }

  // spring attraction along edges
  for (const m of nodes) {
    if (m.parent != null && motes.has(m.parent)) {
      const p = motes.get(m.parent);
      const dx = p.x - m.x, dy = p.y - m.y;
      const dist = Math.sqrt(dx * dx + dy * dy) || 1;
      const stretch = (dist - LAYOUT.restLen) * LAYOUT.spring;
      m.vx += (dx / dist) * stretch;
      m.vy += (dy / dist) * stretch;
      p.vx -= (dx / dist) * stretch;
      p.vy -= (dy / dist) * stretch;
    }
  }

  // integrate + dampen + boundary + centre pull
  for (const m of nodes) {
    m.vx = (m.vx + (W / 2 - m.x) * LAYOUT.centerPull) * LAYOUT.damping;
    m.vy = (m.vy + (H / 2 - m.y) * LAYOUT.centerPull) * LAYOUT.damping;
    m.x = Math.max(40, Math.min(W - 40, m.x + m.vx * dt * 0.06));
    m.y = Math.max(40, Math.min(H - 40, m.y + m.vy * dt * 0.06));
  }
}

/* ═══════════════════════════════════════════════════════════════
   MOTE MANAGEMENT
═══════════════════════════════════════════════════════════════ */
function ensureMote(id) {
  if (!motes.has(id)) {
    motes.set(id, {
      id,
      parent: null,
      T: null,
      crashed: false,
      x: W / 2 + (Math.random() - 0.5) * 300,
      y: H / 2 + (Math.random() - 0.5) * 300,
      vx: 0, vy: 0,
    });
    updateHUDStats();
  }
  return motes.get(id);
}

/* ═══════════════════════════════════════════════════════════════
   EVENT HANDLERS — called for each SSE / demo event
═══════════════════════════════════════════════════════════════ */
function handleEvent(evt) {
  switch (evt.type) {
    case 'TRAFFIC':        onTraffic(evt);        break;
    case 'PARENT_CHANGED': onParentChanged(evt);  break;
    case 'PERIOD_UPDATED': onPeriodUpdated(evt);  break;
    case 'CRASH':          onCrash(evt);          break;
    default:
      console.warn('[iot] unknown event type', evt.type);
  }
}

function onTraffic({ moteId }) {
  const m = ensureMote(moteId);
  statTraffic++;
  document.getElementById('stat-traffic').textContent = statTraffic;

  if (m.parent == null || !motes.has(m.parent)) return;
  const p = motes.get(m.parent);
  // spawn a moving pulse along the child→parent edge
  anims.push({ type: 'pulse', fromId: moteId, toId: m.parent,
    t: 0, duration: CFG.pulseLifetime,
    x0: m.x, y0: m.y, x1: p.x, y1: p.y });
}

function onParentChanged({ moteId, newParentId }) {
  const m = ensureMote(moteId);
  const oldParent = m.parent;
  m.parent = newParentId != null ? newParentId : null;
  if (newParentId != null) ensureMote(newParentId);
  // animate the reconnection: flash old link then new
  anims.push({ type: 'relink', moteId, oldParent, newParent: m.parent,
    t: 0, duration: 500 });
}

function onPeriodUpdated({ moteId, newT }) {
  const m = ensureMote(moteId);
  m.T = newT;
  // halo pulse on the mote
  anims.push({ type: 'haze', moteId, t: 0, duration: CFG.hazeLifetime });
  // refresh inspector if open for this mote
  if (inspectorMoteId === moteId) openInspector(moteId);
}

function onCrash({ moteId }) {
  const m = ensureMote(moteId);
  m.crashed = true;
  statCrashes++;
  document.getElementById('stat-crashes').textContent = statCrashes;
  // flash red ring
  anims.push({ type: 'crash', moteId, t: 0, duration: 800 });
  if (inspectorMoteId === moteId) openInspector(moteId);
}

/* ═══════════════════════════════════════════════════════════════
   DRAW
═══════════════════════════════════════════════════════════════ */
const COLOR = {
  bg:           '#0d1117',
  edge:         'rgba(56,189,248,0.18)',
  edgeHighlight:'rgba(56,189,248,0.7)',
  moteNormal:   '#38bdf8',
  moteCrashed:  '#f87171',
  moteSelected: '#fbbf24',
  label:        '#8b949e',
  pulse:        '#7dd3fc',
};

// mote selected via click
let selectedMote = null;

function draw(now) {
  ctx.clearRect(0, 0, W, H);

  // subtle grid
  drawGrid();

  // edges
  for (const m of motes.values()) {
    if (m.parent != null && motes.has(m.parent)) {
      const p = motes.get(m.parent);
      ctx.beginPath();
      ctx.moveTo(m.x, m.y);
      ctx.lineTo(p.x, p.y);
      ctx.strokeStyle = COLOR.edge;
      ctx.lineWidth = 1.5;
      ctx.stroke();
    }
  }

  // animations (drawn before nodes so pulses sit on top of edges)
  drawAnims(now);

  // nodes
  for (const m of motes.values()) {
    drawMote(m);
  }
}

function drawGrid() {
  ctx.strokeStyle = 'rgba(255,255,255,0.025)';
  ctx.lineWidth = 1;
  const step = 60;
  for (let x = 0; x < W; x += step) {
    ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, H); ctx.stroke();
  }
  for (let y = 0; y < H; y += step) {
    ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(W, y); ctx.stroke();
  }
}

function drawMote(m) {
  const r = CFG.moteRadius;
  const isSelected = selectedMote === m.id;
  const color = m.crashed ? COLOR.moteCrashed
    : isSelected ? COLOR.moteSelected
    : COLOR.moteNormal;

  // outer glow
  const glow = ctx.createRadialGradient(m.x, m.y, r * 0.5, m.x, m.y, r * 2.4);
  glow.addColorStop(0, hexAlpha(color, 0.25));
  glow.addColorStop(1, 'transparent');
  ctx.beginPath();
  ctx.arc(m.x, m.y, r * 2.4, 0, Math.PI * 2);
  ctx.fillStyle = glow;
  ctx.fill();

  // fill circle
  ctx.beginPath();
  ctx.arc(m.x, m.y, r, 0, Math.PI * 2);
  const grad = ctx.createRadialGradient(m.x - r * 0.3, m.y - r * 0.3, 0, m.x, m.y, r);
  grad.addColorStop(0, lighten(color, 0.4));
  grad.addColorStop(1, darken(color, 0.3));
  ctx.fillStyle = grad;
  ctx.fill();

  // ring
  ctx.lineWidth = isSelected ? 2.5 : 1.5;
  ctx.strokeStyle = hexAlpha(color, isSelected ? 1 : 0.6);
  ctx.stroke();

  // crash X indicator
  if (m.crashed) {
    ctx.strokeStyle = '#fff';
    ctx.lineWidth = 1.5;
    const s = r * 0.4;
    ctx.beginPath(); ctx.moveTo(m.x - s, m.y - s); ctx.lineTo(m.x + s, m.y + s); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(m.x + s, m.y - s); ctx.lineTo(m.x - s, m.y + s); ctx.stroke();
  }

  // label
  ctx.font = CFG.labelFont;
  ctx.fillStyle = COLOR.label;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(`#${m.id}`, m.x, m.y);
}

function drawAnims(now) {
  for (let i = anims.length - 1; i >= 0; i--) {
    const a = anims[i];
    const progress = a.t / a.duration;

    if (a.type === 'pulse') {
      // moving dot along child→parent edge, following current node positions
      const child  = motes.get(a.fromId);
      const parent = motes.get(a.toId);
      if (!child || !parent) { anims.splice(i, 1); continue; }
      const x = child.x + (parent.x - child.x) * progress;
      const y = child.y + (parent.y - child.y) * progress;
      const alpha = 1 - progress;
      ctx.beginPath();
      ctx.arc(x, y, 5, 0, Math.PI * 2);
      ctx.fillStyle = hexAlpha(COLOR.pulse, alpha);
      ctx.fill();
      // trail
      ctx.beginPath();
      ctx.arc(x, y, 9, 0, Math.PI * 2);
      ctx.fillStyle = hexAlpha(COLOR.pulse, alpha * 0.2);
      ctx.fill();

    } else if (a.type === 'haze') {
      // expanding ring on mote
      const m = motes.get(a.moteId);
      if (!m) { anims.splice(i, 1); continue; }
      const r = CFG.moteRadius + progress * 40;
      const alpha = (1 - progress) * 0.8;
      ctx.beginPath();
      ctx.arc(m.x, m.y, r, 0, Math.PI * 2);
      ctx.strokeStyle = hexAlpha('#34d399', alpha);
      ctx.lineWidth = 2;
      ctx.stroke();

    } else if (a.type === 'crash') {
      // pulsing red ring
      const m = motes.get(a.moteId);
      if (!m) { anims.splice(i, 1); continue; }
      const r = CFG.moteRadius + progress * 50;
      const alpha = (1 - progress) * 0.9;
      ctx.beginPath();
      ctx.arc(m.x, m.y, r, 0, Math.PI * 2);
      ctx.strokeStyle = hexAlpha('#f87171', alpha);
      ctx.lineWidth = 2.5;
      ctx.stroke();

    } else if (a.type === 'relink') {
      // flash new edge bright
      const m = motes.get(a.moteId);
      const p = a.newParent != null ? motes.get(a.newParent) : null;
      if (m && p) {
        const alpha = (1 - progress) * 0.9;
        ctx.beginPath();
        ctx.moveTo(m.x, m.y);
        ctx.lineTo(p.x, p.y);
        ctx.strokeStyle = hexAlpha(COLOR.edgeHighlight, alpha);
        ctx.lineWidth = 3;
        ctx.stroke();
      }
    }

    // remove expired animations
    if (progress >= 1) anims.splice(i, 1);
  }
}

/* ═══════════════════════════════════════════════════════════════
   ANIMATION LOOP
═══════════════════════════════════════════════════════════════ */
let lastNow = 0;

function loop(now) {
  const dt = now - lastNow;
  lastNow = now;

  // advance animation timers
  for (const a of anims) a.t = Math.min(a.t + dt, a.duration);

  layoutTick(dt);
  draw(now);
  requestAnimationFrame(loop);
}

function startLoop() {
  requestAnimationFrame(loop);
}

/* ═══════════════════════════════════════════════════════════════
   HUD STATS
═══════════════════════════════════════════════════════════════ */
function updateHUDStats() {
  document.getElementById('stat-motes').textContent = motes.size;
}

/* ═══════════════════════════════════════════════════════════════
   SSE CONNECTION
═══════════════════════════════════════════════════════════════ */
let sseSource = null;
let sseDelay  = CFG.sseReconnectMin;
let sseTimer  = null;
let demoMode  = false;
let demoTimer = null;

function setSSEStatus(state, label) {
  const dot = document.getElementById('sse-status');
  dot.className = `dot ${state}`;
  document.getElementById('sse-label').textContent = label;
}

function connectSSE() {
  if (demoMode) return;
  disconnectSSE();

  setSSEStatus('connecting', 'Connecting…');
  const url = `${CFG.apiBase}/events`;

  try {
    sseSource = new EventSource(url);
  } catch (e) {
    scheduleReconnect();
    return;
  }

  sseSource.onopen = () => {
    setSSEStatus('connected', 'Live');
    sseDelay = CFG.sseReconnectMin; // reset backoff
  };

  sseSource.onmessage = (e) => {
    try {
      const evt = JSON.parse(e.data);
      handleEvent(evt);
    } catch (err) {
      console.warn('[iot] SSE parse error', err, e.data);
    }
  };

  sseSource.onerror = () => {
    setSSEStatus('disconnected', 'Reconnecting…');
    disconnectSSE();
    scheduleReconnect();
  };
}

function disconnectSSE() {
  if (sseSource) { sseSource.close(); sseSource = null; }
  if (sseTimer)  { clearTimeout(sseTimer); sseTimer = null; }
}

function scheduleReconnect() {
  if (demoMode) return;
  sseTimer = setTimeout(() => {
    connectSSE();
    // exponential backoff capped at max
    sseDelay = Math.min(sseDelay * 2, CFG.sseReconnectMax);
  }, sseDelay);
}

/* ═══════════════════════════════════════════════════════════════
   DEMO MODE — built-in event simulator
═══════════════════════════════════════════════════════════════ */
// seed a small network
const DEMO_MOTE_IDS = [0, 1, 2, 3, 4, 5, 6, 7];

function initDemoNetwork() {
  for (const id of DEMO_MOTE_IDS) {
    const m = ensureMote(id);
    m.crashed = false;
    m.T = 5 + id;
    // simple tree: 0 is root, rest point to lower-id neighbours
    m.parent = id === 0 ? null : Math.floor(id / 2);
  }
}

function demoTick() {
  const roll = Math.random();
  const ids  = DEMO_MOTE_IDS;

  if (roll < 0.45) {
    // TRAFFIC — most common
    const id = ids[Math.floor(Math.random() * ids.length)];
    handleEvent({ type: 'TRAFFIC', moteId: id, timestamp: Date.now() });

  } else if (roll < 0.65) {
    // PARENT_CHANGED
    const child  = ids[1 + Math.floor(Math.random() * (ids.length - 1))];
    let newParent = ids[Math.floor(Math.random() * ids.length)];
    if (newParent === child) newParent = 0;
    handleEvent({ type: 'PARENT_CHANGED', moteId: child, newParentId: newParent });

  } else if (roll < 0.80) {
    // PERIOD_UPDATED
    const id = ids[Math.floor(Math.random() * ids.length)];
    const newT = 1 + Math.floor(Math.random() * 30);
    handleEvent({ type: 'PERIOD_UPDATED', moteId: id, newT });

  } else {
    // CRASH — rare; auto-recover after 3 s
    const id = ids[1 + Math.floor(Math.random() * (ids.length - 1))];
    handleEvent({ type: 'CRASH', moteId: id });
    setTimeout(() => {
      const m = motes.get(id);
      if (m) { m.crashed = false; }
    }, 3000);
  }
}

function startDemo() {
  disconnectSSE();
  setSSEStatus('connected', 'Demo mode');
  initDemoNetwork();
  demoTimer = setInterval(demoTick, CFG.demoRate);
}

function stopDemo() {
  if (demoTimer) { clearInterval(demoTimer); demoTimer = null; }
  setSSEStatus('disconnected', 'Disconnected');
}

/* ═══════════════════════════════════════════════════════════════
   INSPECTOR
═══════════════════════════════════════════════════════════════ */
let inspectorMoteId = null;

function openInspector(id) {
  const m = motes.get(id);
  if (!m) return;
  inspectorMoteId = id;
  selectedMote = id;

  document.getElementById('ins-id-badge').textContent = `#${m.id}`;
  document.getElementById('ins-parent').textContent  = m.parent != null ? `#${m.parent}` : 'Root';
  document.getElementById('ins-t').textContent       = m.T != null ? `${m.T}s` : '—';
  const statusEl = document.getElementById('ins-status');
  statusEl.textContent  = m.crashed ? 'Crashed' : 'Active';
  statusEl.style.color  = m.crashed ? 'var(--danger)' : 'var(--success)';

  const icon = document.getElementById('ins-icon');
  icon.style.borderColor = m.crashed ? 'var(--danger)' : 'var(--accent)';
  icon.style.boxShadow   = m.crashed
    ? '0 0 12px rgba(248,113,113,0.4)'
    : '0 0 12px var(--accent-glow)';

  document.getElementById('new-t-input').value = '';
  document.getElementById('form-msg').textContent = '';
  document.getElementById('inspector').classList.remove('hidden');
}

function closeInspector() {
  document.getElementById('inspector').classList.add('hidden');
  inspectorMoteId = null;
  selectedMote = null;
}

function initInspectorEvents() {
  document.getElementById('ins-close').addEventListener('click', closeInspector);
  document.getElementById('inspector').addEventListener('click', (e) => {
    if (e.target === document.getElementById('inspector')) closeInspector();
  });
}

/* ═══════════════════════════════════════════════════════════════
   PERIOD UPDATE — POST to /updateT
═══════════════════════════════════════════════════════════════ */
function initFormEvents() {
  document.getElementById('update-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = document.getElementById('form-msg');
    if (inspectorMoteId == null) return;

    const newT = parseInt(document.getElementById('new-t-input').value, 10);
    if (!Number.isFinite(newT) || newT < 1) {
      showFormMsg('error', 'Enter a valid positive integer.');
      return;
    }

    const btn = document.getElementById('send-btn');
    btn.disabled = true;
    btn.textContent = 'Sending…';

    try {
      const res = await fetch(`${CFG.apiBase}/updateT`, {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ moteId: inspectorMoteId, newT }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      showFormMsg('ok', `T updated to ${newT}s`);
      // optimistic local update
      const m = motes.get(inspectorMoteId);
      if (m) { m.T = newT; openInspector(inspectorMoteId); }
    } catch (err) {
      showFormMsg('error', `Failed: ${err.message}`);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Send';
    }
  });
}

function showFormMsg(type, text) {
  const el = document.getElementById('form-msg');
  el.className = type;
  el.textContent = text;
}

/* ═══════════════════════════════════════════════════════════════
   CANVAS CLICK — hit-test motes
═══════════════════════════════════════════════════════════════ */
function initCanvasEvents() {
  canvas.addEventListener('click', (e) => {
    const rect = canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;

    for (const m of motes.values()) {
      const dx = mx - m.x, dy = my - m.y;
      if (Math.sqrt(dx * dx + dy * dy) <= CFG.moteRadius + 6) {
        openInspector(m.id);
        return;
      }
    }
    // click on empty space: deselect
    selectedMote = null;
  });
}

/* ═══════════════════════════════════════════════════════════════
   HUD CONTROLS
═══════════════════════════════════════════════════════════════ */
let demoToggle;

function initHUDControls() {
  demoToggle = document.getElementById('demo-toggle');
  demoToggle.addEventListener('change', () => {
    demoMode = demoToggle.checked;
    if (demoMode) {
      startDemo();
    } else {
      stopDemo();
      connectSSE();
    }
  });

  document.getElementById('api-apply').addEventListener('click', () => {
    const val = document.getElementById('api-input').value.trim().replace(/\/$/, '');
    if (val) {
      CFG.apiBase = val;
      if (!demoMode) connectSSE();
    }
  });

  document.getElementById('api-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') document.getElementById('api-apply').click();
  });
}

/* ═══════════════════════════════════════════════════════════════
   COLOUR UTILITIES
═══════════════════════════════════════════════════════════════ */
function hexAlpha(hex, alpha) {
  // parse '#rrggbb' → rgba()
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r},${g},${b},${alpha.toFixed(3)})`;
}

function lighten(hex, amount) {
  const r = Math.min(255, parseInt(hex.slice(1,3),16) + Math.round(255*amount));
  const g = Math.min(255, parseInt(hex.slice(3,5),16) + Math.round(255*amount));
  const b = Math.min(255, parseInt(hex.slice(5,7),16) + Math.round(255*amount));
  return `rgb(${r},${g},${b})`;
}

function darken(hex, amount) {
  return lighten(hex, -amount);
}

/* ═══════════════════════════════════════════════════════════════
   BOOT
═══════════════════════════════════════════════════════════════ */
// Wait for DOM before initializing canvas, event listeners, and loop
function init() {
  initCanvas();
  initCanvasEvents();
  initInspectorEvents();
  initFormEvents();
  initHUDControls();
  startLoop();
  // Kick off SSE connection on load (live mode by default)
  connectSSE();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
