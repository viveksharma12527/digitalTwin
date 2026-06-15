#!/usr/bin/env bash
# =============================================================================
# Digital Twin Full-Stack Runner
# Contiki (Cooja) --> Node-RED --> Akka
#
# Usage:
#   ./run.sh compile   — Build iot-node.cooja firmware natively on macOS
#   ./run.sh start     — Start Node-RED, Akka, tunslip6, Cooja
#   ./run.sh stop      — Kill all background services
#   ./run.sh status    — Show live status of every component
#   ./run.sh test      — Run end-to-end verification (UDP + HTTP)
#   ./run.sh logs      — Tail ALL service logs
#   ./run.sh logs akka|nodered|cooja|tunslip  — Tail one log
# =============================================================================
set -euo pipefail

# Force C locale for sed to prevent macOS Assertion failed (advance > 0) crashes on escape codes
sed() {
  LC_ALL=C command sed "$@"
}

# ── Paths ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONTIKI_NG="$HOME/contiki-ng"
IOT_NODE_SRC="$SCRIPT_DIR/contiki/iot-node"
AKKA_DIR="$SCRIPT_DIR/NSDS-Digitaltwin-Project-master/akka/digitaltwin"
NODERED_FLOW="$SCRIPT_DIR/NSDS-Digitaltwin-Project-master/nodered.json"
SIM_FILE="$SCRIPT_DIR/contiki/simulation_mac.csc"
COOJA_DIR="$CONTIKI_NG/tools/cooja"
TUNSLIP_DIR="$CONTIKI_NG/tools/serial-io"

# ── Log + PID storage ─────────────────────────────────────────────────────────
LOG_DIR="$SCRIPT_DIR/logs"
PID_DIR="$SCRIPT_DIR/.pids"
mkdir -p "$LOG_DIR" "$PID_DIR"

LOG_AKKA="$LOG_DIR/akka.log"
LOG_NODERED="$LOG_DIR/nodered.log"
LOG_COOJA="$LOG_DIR/cooja.log"
LOG_TUNSLIP="$LOG_DIR/tunslip.log"
LOG_MAIN="$LOG_DIR/runner.log"
LOG_STATUS="$LOG_DIR/status.log"

PID_AKKA="$PID_DIR/akka.pid"
PID_NODERED="$PID_DIR/nodered.pid"
PID_COOJA="$PID_DIR/cooja.pid"
PID_TUNSLIP="$PID_DIR/tunslip.pid"

# ── Config ────────────────────────────────────────────────────────────────────
NODERED_PORT=1880
AKKA_PORT=8080
TUNSLIP_PORT=60001
TUNSLIP_PREFIX="fd00::1/64"

# ── ANSI colors ───────────────────────────────────────────────────────────────
RED='\033[0;31m';  GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m';  BOLD='\033[1m';  RESET='\033[0m'

# ── Logging helpers ───────────────────────────────────────────────────────────
ts()      { date '+%H:%M:%S'; }
log()     { echo -e "${CYAN}[$(ts)]${RESET} $*" | tee -a "$LOG_MAIN"; }
ok()      { echo -e "${GREEN}[$(ts)] ✅ $*${RESET}" | tee -a "$LOG_MAIN"; }
warn()    { echo -e "${YELLOW}[$(ts)] ⚠️  $*${RESET}" | tee -a "$LOG_MAIN"; }
err()     { echo -e "${RED}[$(ts)] ❌ $*${RESET}" | tee -a "$LOG_MAIN"; }
banner()  { echo -e "\n${BOLD}${BLUE}══════════════════════════════════════${RESET}"; \
            echo -e "${BOLD}${BLUE}  $*${RESET}"; \
            echo -e "${BOLD}${BLUE}══════════════════════════════════════${RESET}\n"; }

# ── PID helpers ───────────────────────────────────────────────────────────────
save_pid()  { echo "$1" > "$2"; }
read_pid()  { [[ -f "$1" ]] && cat "$1" || echo ""; }
is_alive()  { local p; p=$(read_pid "$1"); [[ -n "$p" ]] && kill -0 "$p" 2>/dev/null; }

# ── Port helpers ──────────────────────────────────────────────────────────────
port_open() { lsof -i :"$1" -sTCP:LISTEN -t &>/dev/null; }
wait_port() {
  local port=$1 label=$2 timeout=${3:-30}
  log "Waiting for $label to open port $port (timeout ${timeout}s)…"
  local i=0
  while ! port_open "$port"; do
    sleep 1; ((++i))
    if [[ $i -ge $timeout ]]; then
      err "$label did not open port $port within ${timeout}s"
      return 1
    fi
    [[ $((i % 5)) -eq 0 ]] && log "  …still waiting ($i/${timeout}s)"
  done
  ok "$label is up on port $port"
}

# =============================================================================
# COMPILE
# =============================================================================
cmd_compile() {
  banner "Step 1 — Compile Contiki Firmware"

  log "Copying iot-node sources → $CONTIKI_NG/examples/iot-node/"
  mkdir -p "$CONTIKI_NG/examples/iot-node"
  cp "$IOT_NODE_SRC/iot-node.c"  "$CONTIKI_NG/examples/iot-node/iot-node.c"
  cp "$IOT_NODE_SRC/Makefile"    "$CONTIKI_NG/examples/iot-node/Makefile"
  ok "Sources copied"

  log "Cleaning existing build directories to avoid ELF/Mach-O conflicts…"
  make -C "$CONTIKI_NG/examples/iot-node" clean TARGET=cooja >/dev/null 2>&1 || true
  make -C "$CONTIKI_NG/examples/rpl-border-router" clean TARGET=cooja >/dev/null 2>&1 || true

  log "Building iot-node.cooja natively on macOS…"
  local cpus
  cpus=$(sysctl -n hw.ncpu 2>/dev/null || echo "4")
  
  make -C "$CONTIKI_NG/examples/iot-node" -j"$cpus" TARGET=cooja 2>&1 | tee "$LOG_DIR/compile.log"

  local fw_path="$CONTIKI_NG/examples/iot-node/build/cooja/iot-node.cooja"
  if [[ -f "$fw_path" ]]; then
    ok "Firmware compiled natively: $fw_path"
  else
    err "Compilation failed — check $LOG_DIR/compile.log"
    exit 1
  fi
}

# =============================================================================
# START INDIVIDUAL SERVICES
# =============================================================================

start_nodered() {
  banner "Starting Node-RED"
  if is_alive "$PID_NODERED"; then
    warn "Node-RED is already running (pid $(read_pid $PID_NODERED))"
    return
  fi

  if ! command -v node-red &>/dev/null; then
    err "node-red not found. Install with: npm install -g --unsafe-perm node-red"
    exit 1
  fi

  log "Starting Node-RED → log: $LOG_NODERED"
  node-red > "$LOG_NODERED" 2>&1 &
  save_pid $! "$PID_NODERED"
  ok "Node-RED started (pid $!)"

  wait_port $NODERED_PORT "Node-RED" 45

  # Auto-import the flow via Node-RED admin API
  log "Importing nodered.json flow…"
  sleep 2  # give the runtime a moment to settle
  local http_code
  http_code=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "http://localhost:$NODERED_PORT/flows" \
    -H "Content-Type: application/json" \
    -d @"$NODERED_FLOW" 2>/dev/null || echo "000")

  if [[ "$http_code" == "200" || "$http_code" == "204" ]]; then
    ok "Flow imported and deployed (HTTP $http_code)"
  else
    warn "Flow auto-import returned HTTP $http_code — open http://localhost:$NODERED_PORT and import manually"
  fi
}

start_akka() {
  banner "Starting Akka Digital Twin"
  if is_alive "$PID_AKKA"; then
    warn "Akka is already running (pid $(read_pid $PID_AKKA))"
    return
  fi

  if ! command -v mvn &>/dev/null; then
    err "Maven (mvn) not found. Install with: brew install maven"
    exit 1
  fi

  log "Building + starting Akka → log: $LOG_AKKA"
  # Use 'tail -f /dev/null' pipe so App.java's System.in.read() blocks forever (never exits)
  (cd "$AKKA_DIR" && tail -f /dev/null | mvn -q clean compile exec:java \
    -Dexec.mainClass=com.digitaltwin.App 2>&1) > "$LOG_AKKA" 2>&1 &
  save_pid $! "$PID_AKKA"
  ok "Akka started (pid $!)"

  # Akka HTTP binds asynchronously — check via HTTP not lsof
  log "Waiting for Akka HTTP server on port $AKKA_PORT..."
  local timeout=45
  local i=0
  while ! curl -s -o /dev/null http://localhost:$AKKA_PORT/ 2>/dev/null; do
    sleep 1; ((++i))
    if [[ $i -ge $timeout ]]; then
      err "Akka did not respond within ${timeout}s"
      return 1
    fi
    [[ $((i % 10)) -eq 0 ]] && log "  ...still waiting ($i/${timeout}s)"
  done
  ok "Akka HTTP server is up on port $AKKA_PORT"
}

start_cooja() {
  banner "Starting Cooja Simulator"
  if is_alive "$PID_COOJA"; then
    warn "Cooja is already running (pid $(read_pid $PID_COOJA))"
    return
  fi

  # Ensure XQuartz / X11 is allowed
  log "Enabling X11 connections (xhost +localhost)…"
  xhost +localhost &>/dev/null || warn "xhost failed — ensure XQuartz is running"

  if [[ ! -f "$COOJA_DIR/gradlew" ]]; then
    err "Cooja not found at $COOJA_DIR"
    err "Run: git clone https://github.com/contiki-ng/contiki-ng.git ~/contiki-ng && cd ~/contiki-ng && git submodule update --init tools/cooja"
    exit 1
  fi

  log "Launching Cooja with simulation_mac.csc → log: $LOG_COOJA"
  (cd "$COOJA_DIR" && ./gradlew run --args="--autostart $SIM_FILE" 2>&1) > "$LOG_COOJA" 2>&1 &
  save_pid $! "$PID_COOJA"
  ok "Cooja launched (pid $!) — GUI may take 15-30s to appear"
  log "  Simulation file: $SIM_FILE"
  log "  If GUI opens but simulation isn't running, press ▶ Start in Cooja"
}

start_tunslip() {
  banner "Starting tunslip6 Serial Tunnel"
  if is_alive "$PID_TUNSLIP"; then
    warn "tunslip6 is already running (pid $(read_pid $PID_TUNSLIP))"
    return
  fi

  if [[ ! -f "$TUNSLIP_DIR/tunslip6" ]]; then
    warn "tunslip6 not found at $TUNSLIP_DIR"
    warn "Build it: cd $CONTIKI_NG/tools/serial-io && make"
    warn "Skipping tunslip6 — RPL DODAG will not form without it"
    return
  fi

  # Wait for Cooja's serial socket to open
  if ! wait_port $TUNSLIP_PORT "Cooja Serial Socket" 45; then
    err "Cannot start tunslip6 because Cooja Serial Socket is not open."
    return 1
  fi

  log "Starting tunslip6 (requires sudo) → log: $LOG_TUNSLIP"
  warn "You may be prompted for your macOS password (sudo)"
  sudo "$TUNSLIP_DIR/tunslip6" -a 127.0.0.1 -p $TUNSLIP_PORT "$TUNSLIP_PREFIX" \
    > "$LOG_TUNSLIP" 2>&1 &
  save_pid $! "$PID_TUNSLIP"
  ok "tunslip6 started (pid $!)"
}

# =============================================================================
# START ALL
# =============================================================================
cmd_start() {
  banner "🚀 Digital Twin — Full Stack Start"
  echo -e "${BOLD}Flow: Contiki (Cooja) → Node-RED → Akka${RESET}\n"

  local start_native_cooja=true
  if [[ "${1:-}" == "--no-cooja" || "${1:-}" == "no-cooja" ]]; then
    start_native_cooja=false
    log "Skipping native Cooja startup (Cooja is expected to run in Docker)"
  fi

  # Check firmware exists (only if starting native cooja)
  if $start_native_cooja && [[ ! -f "$CONTIKI_NG/examples/iot-node/build/cooja/iot-node.cooja" ]]; then
    warn "Firmware not compiled yet — running compile step first…"
    cmd_compile
  fi

  start_nodered
  echo ""
  start_akka
  echo ""
  if $start_native_cooja; then
    start_cooja
    echo ""
  fi
  start_tunslip
  echo ""

  banner "✅ All services started"
  cmd_status
  echo ""
  echo -e "${BOLD}Run  ${CYAN}./run.sh logs${RESET}${BOLD}  to tail all logs${RESET}"
  echo -e "${BOLD}Run  ${CYAN}./run.sh test${RESET}${BOLD}  to verify the end-to-end flow${RESET}"
}

# =============================================================================
# STOP ALL
# =============================================================================
cmd_stop() {
  banner "🛑 Stopping All Services"

  for name_pid in "Cooja:$PID_COOJA" "Akka:$PID_AKKA" "Node-RED:$PID_NODERED" "tunslip6:$PID_TUNSLIP"; do
    local name="${name_pid%%:*}"
    local pidfile="${name_pid##*:}"
    local pid
    pid=$(read_pid "$pidfile")
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      log "Stopping $name (pid $pid)…"
      kill -TERM "$pid" 2>/dev/null || true
      sleep 1
      kill -KILL "$pid" 2>/dev/null || true
      ok "$name stopped"
    else
      log "$name is not running (according to pidfile)"
    fi
    rm -f "$pidfile"
  done

  log "Cleaning up any remaining processes on ports $AKKA_PORT, $NODERED_PORT..."
  
  # Kill processes on Akka port (using sudo to handle root-owned instances)
  local akka_pid
  akka_pid=$(sudo lsof -t -i :"$AKKA_PORT" -sTCP:LISTEN 2>/dev/null || true)
  if [[ -n "$akka_pid" ]]; then
    log "Stopping process on Akka port $AKKA_PORT (pid $akka_pid)…"
    sudo kill -TERM "$akka_pid" 2>/dev/null || true
    sleep 0.5
    sudo kill -KILL "$akka_pid" 2>/dev/null || true
  fi

  # Kill processes on Node-RED port (using sudo)
  local nr_pid
  nr_pid=$(sudo lsof -t -i :"$NODERED_PORT" -sTCP:LISTEN 2>/dev/null || true)
  if [[ -n "$nr_pid" ]]; then
    log "Stopping process on Node-RED port $NODERED_PORT (pid $nr_pid)…"
    sudo kill -TERM "$nr_pid" 2>/dev/null || true
    sleep 0.5
    sudo kill -KILL "$nr_pid" 2>/dev/null || true
  fi

  # Kill any stray processes by name (using sudo)
  log "Stopping any remaining processes by name..."
  sudo pkill -f "com.digitaltwin.App" 2>/dev/null || true
  sudo pkill -f "org.contikios.cooja" 2>/dev/null || true
  sudo pkill -f "GradleWrapperMain" 2>/dev/null || true
  sudo pkill -f tunslip6 2>/dev/null || true

  ok "All services stopped"
}

# =============================================================================
# STATUS
# =============================================================================
check_service() {
  local name=$1 pidfile=$2 port=$3 logfile=$4
  local pid status_text color

  pid=$(read_pid "$pidfile")

  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    if [[ -n "$port" ]] && port_open "$port"; then
      color=$GREEN; status_text="RUNNING  (pid $pid, port $port ✅)"
    elif [[ -n "$port" ]]; then
      color=$YELLOW; status_text="STARTING (pid $pid, port $port not open yet)"
    else
      color=$GREEN; status_text="RUNNING  (pid $pid)"
    fi
  else
    color=$RED; status_text="STOPPED"
  fi

  printf "  ${BOLD}%-12s${RESET}  ${color}%s${RESET}\n" "$name" "$status_text"

  # Show last 3 log lines
  if [[ -f "$logfile" && -s "$logfile" ]]; then
    echo -e "    ${CYAN}↳ Recent log:${RESET}"
    tail -3 "$logfile" | sed 's/^/      /'
  fi
  echo ""
}

cmd_status() {
  banner "📊 Service Status"
  check_service "Node-RED"  "$PID_NODERED" "$NODERED_PORT" "$LOG_NODERED"
  check_service "Akka"      "$PID_AKKA"    "$AKKA_PORT"    "$LOG_AKKA"
  check_service "Cooja"     "$PID_COOJA"   ""              "$LOG_COOJA"
  check_service "tunslip6"  "$PID_TUNSLIP" ""              "$LOG_TUNSLIP"

  echo -e "  ${BOLD}Log files:${RESET}"
  for f in "$LOG_NODERED" "$LOG_AKKA" "$LOG_COOJA" "$LOG_TUNSLIP" "$LOG_MAIN"; do
    local size=""
    [[ -f "$f" ]] && size=" ($(du -sh "$f" 2>/dev/null | cut -f1))"
    printf "    %-40s %s\n" "$(basename "$f")$size" "$f"
  done
}

# =============================================================================
# LOGS
# =============================================================================
cmd_logs() {
  local target="${1:-all}"
  banner "📋 Logs — $target"

  case "$target" in
    akka)    tail -f "$LOG_AKKA" ;;
    nodered) tail -f "$LOG_NODERED" ;;
    cooja)   tail -f "$LOG_COOJA" ;;
    tunslip) tail -f "$LOG_TUNSLIP" ;;
    compile) tail -f "$LOG_DIR/compile.log" ;;
    all)
      # Tail all logs in parallel with labels using multitail or plain tail
      if command -v multitail &>/dev/null; then
        multitail -l "tail -f $LOG_AKKA" \
                  -l "tail -f $LOG_NODERED" \
                  -l "tail -f $LOG_COOJA" \
                  -l "tail -f $LOG_TUNSLIP"
      else
        echo -e "${YELLOW}Tip: brew install multitail for a split-pane view${RESET}"
        echo -e "${BOLD}Tailing all logs (Ctrl+C to stop):${RESET}\n"
        tail -f "$LOG_NODERED" "$LOG_AKKA" "$LOG_COOJA" "$LOG_TUNSLIP" 2>/dev/null
      fi
      ;;
    *) err "Unknown service: $target. Use: akka|nodered|cooja|tunslip|compile|all" ;;
  esac
}

# =============================================================================
# TEST — End-to-end verification
# =============================================================================
cmd_test() {
  banner "🧪 End-to-End Verification"
  local pass=0 fail=0

  # Helper: test result
  check() {
    local desc=$1 result=$2
    if [[ "$result" == "ok" ]]; then
      ok "PASS — $desc"; ((++pass))
    else
      err "FAIL — $desc ($result)"; ((++fail))
    fi
  }

  # ── 1. Node-RED reachable ─────────────────────────────────────────────────
  log "[1/6] Node-RED HTTP reachable (port $NODERED_PORT)…"
  if curl -sf "http://localhost:$NODERED_PORT" -o /dev/null; then
    check "Node-RED is reachable" "ok"
  else
    check "Node-RED is reachable" "connection refused on port $NODERED_PORT"
  fi

  # ── 2. Node-RED flow deployed (udp-in node on port 5000) ─────────────────
  log "[2/6] Checking Node-RED flow has UDP-in node on port 5000…"
  local flow_json
  flow_json=$(curl -sf "http://localhost:$NODERED_PORT/flows" 2>/dev/null || echo "")
  if echo "$flow_json" | grep -q '"5000"'; then
    check "UDP-in node deployed on port 5000" "ok"
  else
    check "UDP-in node deployed on port 5000" "flow not found or not deployed"
  fi

  # ── 3. Akka HTTP reachable ────────────────────────────────────────────────
  log "[3/6] Akka HTTP reachable (port $AKKA_PORT)…"
  local akka_code
  akka_code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$AKKA_PORT/" 2>/dev/null || echo "000")
  if [[ "$akka_code" != "000" ]]; then
    check "Akka is reachable (HTTP $akka_code)" "ok"
  else
    check "Akka is reachable" "connection refused on port $AKKA_PORT"
  fi

  # ── 4. UDP listener test — can port 5000 accept packets? ─────────────────
  log "[4/6] Sending a test TRAFFIC UDP packet to port 5000…"
  local test_payload='{"moteId":99,"type":"TRAFFIC","seq":1,"parent":"test"}'
  echo "$test_payload" | nc -u -w1 127.0.0.1 5000 2>/dev/null && \
    check "UDP TRAFFIC packet sent to Node-RED port 5000" "ok" || \
    check "UDP TRAFFIC packet sent to Node-RED port 5000" "nc failed (netcat not installed?)"

  # ── 5. SET_PERIOD command via Node-RED /set-params ────────────────────────
  log "[5/6] Testing SET_PERIOD via POST /set-params…"
  local sp_code
  sp_code=$(curl -s -o "$LOG_DIR/test_setperiod.out" -w "%{http_code}" \
    -X POST "http://localhost:$NODERED_PORT/set-params" \
    -H "Content-Type: application/json" \
    -d '{"moteId": 2, "newT": 3}' 2>/dev/null || echo "000")
  if [[ "$sp_code" == "200" || "$sp_code" == "204" ]]; then
    check "SET_PERIOD command accepted (HTTP $sp_code)" "ok"
  else
    check "SET_PERIOD command accepted" "HTTP $sp_code — check $LOG_DIR/test_setperiod.out"
  fi

  # ── 6. Akka /traffic endpoint ─────────────────────────────────────────────
  log "[6/6] Testing Akka /traffic endpoint directly…"
  local tr_code
  tr_code=$(curl -s -o "$LOG_DIR/test_traffic.out" -w "%{http_code}" \
    -X POST "http://localhost:$AKKA_PORT/traffic" \
    -H "Content-Type: application/json" \
    -d '{"moteId": 2}' 2>/dev/null || echo "000")
  if [[ "$tr_code" == "200" || "$tr_code" == "204" ]]; then
    check "Akka /traffic accepted (HTTP $tr_code)" "ok"
  else
    check "Akka /traffic accepted" "HTTP $tr_code — check $LOG_DIR/test_traffic.out"
  fi

  # ── Summary ───────────────────────────────────────────────────────────────
  echo ""
  local total=$((pass + fail))
  if [[ $fail -eq 0 ]]; then
    ok "All $total tests passed 🎉 — full flow is operational!"
  else
    warn "$pass/$total tests passed, $fail failed — see errors above"
    echo ""
    echo -e "${YELLOW}Common fixes:${RESET}"
    echo "  • Services not started?  → ./run.sh start"
    echo "  • Firmware not compiled? → ./run.sh compile"
    echo "  • Cooja not running?     → open Cooja GUI and press ▶ Start"
    echo "  • Node-RED flow missing? → import nodered.json at http://localhost:$NODERED_PORT"
  fi
}

# =============================================================================
# WRITE STATUS SNAPSHOT  (plain text, updated every dashboard refresh)
# =============================================================================
write_status_snapshot() {
  local now; now=$(date '+%Y-%m-%d %H:%M:%S')
  {
    echo "=================================================================="
    echo "  Digital Twin — Status Snapshot"
    echo "  $(date '+%Y-%m-%d %H:%M:%S')"
    echo "=================================================================="
    echo ""

    for entry in "Node-RED:$PID_NODERED:$NODERED_PORT:$LOG_NODERED" \
                 "Akka:$PID_AKKA:$AKKA_PORT:$LOG_AKKA" \
                 "Cooja:$PID_COOJA::$LOG_COOJA" \
                 "tunslip6:$PID_TUNSLIP::$LOG_TUNSLIP"; do
      local svc pidfile port logfile
      IFS=':' read -r svc pidfile port logfile <<< "$entry"
      local pid; pid=$(read_pid "$pidfile")
      local state="STOPPED"
      [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null && state="RUNNING (pid $pid)"
      [[ -n "$port" ]] && port_open "$port" && state="$state  port:$port ✅"
      printf '  %-12s  %s\n' "$svc" "$state"
      if [[ -f "$logfile" && -s "$logfile" ]]; then
        echo "    --- last 5 lines of $(basename "$logfile") ---"
        tail -5 "$logfile" | sed 's/^/    /'
      fi
      echo ""
    done

    echo "------------------------------------------------------------------"
    echo "  Flow: Contiki:UDP:5000 → Node-RED:1880 → Akka:8080"
    echo "  Log dir: $LOG_DIR"
    echo "=================================================================="
  } > "$LOG_STATUS"
}

# =============================================================================
# DASHBOARD  — live auto-refreshing terminal view
# =============================================================================
cmd_dashboard() {
  local interval=${1:-2}   # refresh every N seconds

  # Hide cursor, restore on exit
  tput civis 2>/dev/null || true
  trap 'tput cnorm 2>/dev/null; echo; exit 0' INT TERM

  while true; do
    # ── Build frame in a variable to avoid flicker ────────────────────────
    local frame=""

    # Header
    frame+="$(echo -e "${BOLD}${BLUE}")"
    frame+="$(printf '═%.0s' {1..60})\n"
    frame+="  🌐  Digital Twin Live Dashboard"
    frame+="$(echo -e "${RESET}")\n"
    frame+="$(echo -e "${CYAN}")  $(date '+%Y-%m-%d %H:%M:%S')   "
    frame+="(refresh every ${interval}s  •  Ctrl+C to exit)"
    frame+="$(echo -e "${RESET}")\n"
    frame+="$(echo -e "${BLUE}$(printf '═%.0s' {1..60})${RESET}")\n\n"

    # Flow diagram
    frame+="$(echo -e "${BOLD}")  Flow:$(echo -e "${RESET}")"
    frame+="  Contiki/Cooja"
    frame+="$(echo -e "${CYAN}") ──UDP:5000──► $(echo -e "${RESET}")"
    frame+="Node-RED"
    frame+="$(echo -e "${CYAN}") ──HTTP:8080──► $(echo -e "${RESET}")"
    frame+="Akka\n\n"

    # Service rows
    for entry in "Node-RED:$PID_NODERED:$NODERED_PORT:$LOG_NODERED" \
                 "Akka:$PID_AKKA:$AKKA_PORT:$LOG_AKKA" \
                 "Cooja:$PID_COOJA::$LOG_COOJA" \
                 "tunslip6:$PID_TUNSLIP::$LOG_TUNSLIP"; do
      local svc pidfile port logfile
      IFS=':' read -r svc pidfile port logfile <<< "$entry"
      local pid; pid=$(read_pid "$pidfile" 2>/dev/null || echo "")

      local dot state_color state_txt
      if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        if [[ -n "$port" ]] && port_open "$port"; then
          dot="●"; state_color=$GREEN
          state_txt="RUNNING   pid:$pid  port:$port ✅"
        else
          dot="◑"; state_color=$YELLOW
          state_txt="STARTING  pid:$pid  (port $port not ready)"
        fi
      else
        dot="○"; state_color=$RED
        state_txt="STOPPED"
      fi

      frame+="  ${state_color}${BOLD}${dot} $(printf '%-10s' "$svc")${RESET}"
      frame+="  ${state_color}${state_txt}${RESET}\n"

      # Last 3 log lines, indented
      if [[ -f "$logfile" && -s "$logfile" ]]; then
        local log_lines
        log_lines=$(tail -3 "$logfile" 2>/dev/null \
          | sed 's/\x1B\[[0-9;]*[mK]//g' \
          | sed "s/^/    $(echo -e '\033[2m')/" \
          | sed "s/$/${RESET}/")
        frame+="$log_lines\n"
      fi
      frame+="\n"
    done

    # Port health summary
    frame+="$(echo -e "${BLUE}$(printf '─%.0s' {1..60})${RESET}")\n"
    frame+="$(echo -e "${BOLD}")  Port Health:$(echo -e "${RESET}")\n"
    for p_label in "5000:UDP→Node-RED (Contiki telemetry)" \
                   "$NODERED_PORT:TCP→Node-RED HTTP" \
                   "$AKKA_PORT:TCP→Akka HTTP" \
                   "$TUNSLIP_PORT:TCP→Cooja Serial Socket"; do
      local p lbl
      IFS=':' read -r p lbl <<< "$p_label"
      if lsof -i :"$p" -t &>/dev/null 2>&1; then
        frame+="    $(echo -e "${GREEN}")✅  :$p  $lbl$(echo -e "${RESET}")\n"
      else
        frame+="    $(echo -e "${RED}")○   :$p  $lbl$(echo -e "${RESET}")\n"
      fi
    done

    # Log file sizes
    frame+="\n$(echo -e "${BLUE}$(printf '─%.0s' {1..60})${RESET}")\n"
    frame+="$(echo -e "${BOLD}")  Log Files:$(echo -e "${RESET}")\n"
    for lf in "$LOG_NODERED" "$LOG_AKKA" "$LOG_COOJA" "$LOG_TUNSLIP" "$LOG_MAIN"; do
      local sz="(empty)"
      [[ -s "$lf" ]] && sz=$(du -sh "$lf" 2>/dev/null | cut -f1)
      frame+="    $(echo -e "${CYAN}")$(printf '%-20s' "$(basename "$lf")")$(echo -e "${RESET}")  $sz   $lf\n"
    done

    frame+="\n$(echo -e "${BLUE}$(printf '─%.0s' {1..60})${RESET}")\n"
    frame+="  $(echo -e "${BOLD}")Snapshot saved:$(echo -e "${RESET}") $LOG_STATUS\n"
    frame+="  Commands: $(echo -e "${CYAN}")./run.sh logs <svc>$(echo -e "${RESET}")  "
    frame+="$(echo -e "${CYAN}")./run.sh test$(echo -e "${RESET}")  "
    frame+="$(echo -e "${CYAN}")./run.sh stop$(echo -e "${RESET}")\n"

    # ── Atomic redraw ──────────────────────────────────────────────────────
    tput cup 0 0 2>/dev/null || clear
    echo -e "$frame"

    # ── Write plain-text snapshot to status.log ────────────────────────────
    write_status_snapshot

    sleep "$interval"
  done

  tput cnorm 2>/dev/null || true
}

# =============================================================================
# ENTRYPOINT
# =============================================================================
print_help() {
  cat <<EOF

${BOLD}${BLUE}Digital Twin Runner — Contiki → Node-RED → Akka${RESET}

Usage: ${BOLD}./run.sh <command> [args]${RESET}

Commands:
  ${GREEN}compile${RESET}        Build iot-node.cooja firmware (natively on macOS)
  ${GREEN}start${RESET}          Start all services (compiles first if needed)
  ${GREEN}stop${RESET}           Stop all background services
  ${GREEN}status${RESET}         Show live status + recent log lines for each service
  ${GREEN}dashboard${RESET}      Live auto-refreshing status view (updates every 2s)
  ${GREEN}test${RESET}           Run 6-step end-to-end verification suite
  ${GREEN}logs${RESET}           Tail ALL service logs simultaneously
  ${GREEN}logs <svc>${RESET}     Tail one service log: akka | nodered | cooja | tunslip | compile

Examples:
  ./run.sh compile          # First time only
  ./run.sh start            # Start everything
  ./run.sh dashboard        # Live dashboard (auto-refreshes every 2s)
  ./run.sh status           # One-shot status snapshot
  ./run.sh test             # Verify the flow works
  ./run.sh logs akka        # Watch Akka output
  ./run.sh stop             # Tear everything down

Snapshot log (updated by dashboard):
  ${CYAN}$LOG_STATUS${RESET}

Log files are written to: ${CYAN}$LOG_DIR/${RESET}

EOF
}

CMD="${1:-help}"
shift || true

case "$CMD" in
  compile)   cmd_compile ;;
  start)     cmd_start "$@" ;;
  stop)      cmd_stop ;;
  status)    cmd_status ;;
  dashboard) cmd_dashboard "${1:-2}" ;;
  test)      cmd_test ;;
  logs)      cmd_logs "${1:-all}" ;;
  help|--help|-h) print_help ;;
  *)
    err "Unknown command: $CMD"
    print_help
    exit 1
    ;;
esac
