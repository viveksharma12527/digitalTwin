import socket
import time
import json
import threading

# --- CONFIGURATION ---
MOTE_ID = 1
NODE_RED_IP = "127.0.0.1"
NODE_RED_PORT = 5000  # Node-RED listens here
LISTEN_PORT = 6000    # Mote listens here for commands
# ---------------------

# State variables
state = {
    "T": 5,           # Message interval in seconds
    "parent": 2,      # Current RPL parent
    "is_running": True
}

def send_to_nodered(data_type, extra_info={}):
    """Sends a UDP packet to Node-RED."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    payload = {
        "moteId": MOTE_ID,
        "type": data_type,
        "timestamp": time.time()
    }
    payload.update(extra_info)
    message = json.dumps(payload).encode('utf-8')
    sock.sendto(message, (NODE_RED_IP, NODE_RED_PORT))
    print(f"[SENT] {data_type}: {payload}")

def periodic_traffic():
    """Simulates dummy traffic flow."""
    while True:
        if state["is_running"]:
            send_to_nodered("TRAFFIC", {"seq": int(time.time()), "parent": state["parent"]})
        time.sleep(state["T"])

def listen_for_commands():
    """Listens for instructions from Node-RED (Digital Twin mirror)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind(("0.0.0.0", LISTEN_PORT))
        print(f"Mote {MOTE_ID} listening for commands on port {LISTEN_PORT}...")
        while True:
            data, addr = s.recvfrom(1024)
            cmd = json.loads(data.decode('utf-8'))
            
            if cmd.get("action") == "SET_PERIOD":
                state["T"] = cmd["value"]
                print(f"[CMD] Period T updated to {state['T']}")
                
            elif cmd.get("action") == "CRASH":
                state["is_running"] = False
                print("[CMD] Node CRASHED!")

# Start Threads
threading.Thread(target=periodic_traffic, daemon=True).start()
threading.Thread(target=listen_for_commands, daemon=True).start()

# Manual trigger for Parent Change (Type 'p' and Enter)
print("Type 'p' to simulate a Parent Change, or 'q' to quit.")
while True:
    val = input()
    if val == 'p':
        state["parent"] += 1
        send_to_nodered("PARENT_CHANGE", {"newParentId": state["parent"]})
    elif val == 'q':
        break