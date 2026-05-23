# Digital Twin for a Low-Power Wireless Network
**Politecnico di Milano** | Networked Software for Distributed Systems | A.Y. 2025/2026  
*Technologies: Contiki-NG (Cooja) · Akka (Java 21) · Node-RED · Maven*

---

## 📖 Architecture & Design Philosophy

This project implements a **real-time Digital Twin** of an IoT low-power wireless sensor network. The system is split into three decoupled operational layers, coordinated exclusively by an orchestration engine.

```
                  ┌──────────────────────────────────────────────────┐
                  │          COOJA SIMULATOR (Contiki-NG)             │
                  │  RPL Tree: [Root Mote] ◄── [Mote 2] ◄── [Mote 3]   │
                  └─────────────────↕ UDP/IPv6 ──────────────────────┘
                                    ↕
                  ┌──────────────────────────────────────────────────┐
                  │            NODE-RED ORCHESTRATION LAYER          │
                  │               (Listening on Port 1880)           │
                  │   All routing, command parsing, & twin sync logic │
                  └─────────────────↕ HTTP ──────────────────────────┘
                                    ↕
                  ┌──────────────────────────────────────────────────┐
                  │            AKKA DIGITAL TWIN (Java)              │
                  │  SupervisorActor ◄──► IoTNodeActor ghost copies  │
                  └──────────────────────────────────────────────────┘
```

### ⚠️ Critical Design Rule
* **No Coordination in Contiki-NG (C):** Firmware merely sends events to a registered Node-RED port and listens for simple period change packets. It has no knowledge of the virtual copies.
* **No Coordination in Akka (Java):** Actors maintain the current state of a node (`currentParent`, `lastKParents`, `messagePeriod`), and throw a `RuntimeException` on a crash command. They do not know about the physical network or make network calls.
* **All Coordination in Node-RED:** Keeps the physical and virtual systems in perfect synchronization. If you want to change the behavior of the digital twin, you only need to swap the Node-RED flow, without modifying the Contiki firmware or the Akka application.

---

## 🔌 Network Port Allocations & JSON Schema

### Ports Reference
| Protocol | Port | Source | Target | Role |
| :--- | :--- | :--- | :--- | :--- |
| **UDP/IPv6** | `5005` | Contiki Nodes | Node-RED | Telemetry channel (parent change, app messages, crash reports) |
| **UDP/IPv6** | `5010` | Node-RED | Contiki Nodes | Command channel (message period T changes) |
| **HTTP/JSON**| `8080` | Node-RED | Akka Twin | HTTP POST endpoints to update/crash virtual actors |
| **HTTP/JSON**| `1880` | User/Akka | Node-RED | Set period endpoints `/setPeriod` |

### JSON Specifications
* **Parent Changed Event (Contiki ➔ Node-RED ➔ Akka):**
  ```json
  {"nodeId": 2, "event": "parent_change", "newParent": "fd00::201", "oldParent": "fd00::203"}
  ```
* **App Message Telemetry (Contiki ➔ Node-RED ➔ Akka):**
  ```json
  {"nodeId": 2, "event": "app_message", "seq": 2400500}
  ```
* **Set Period Command (HTTP Client ➔ Node-RED ➔ Contiki):**
  ```json
  {"nodeId": 2, "newPeriod": 3}
  ```
  *(Cooja command format sent by Node-RED: raw string `"newPeriod:3"` to node's port `5010`)*
* **Crash Mote Event (Contiki ➔ Node-RED ➔ Akka):**
  ```json
  {"nodeId": 2, "event": "crash"}
  ```

---

## � Prerequisites (macOS)

Before you begin, install these dependencies:

### 1. Homebrew
```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

### 2. Git LFS
```bash
brew install git-lfs
git lfs install
```

### 3. Docker Desktop
- Download: https://www.docker.com/products/docker-desktop/
- Open the app and wait for the whale icon to appear in the menu bar

### 4. XQuartz (for Cooja GUI on Mac) ⚠️ IMPORTANT
```bash
brew install --cask xquartz
```
**After installation, RESTART your Mac.** Then:
- Open XQuartz → Preferences → Security
- ✅ CHECK "Allow connections from network clients"

### 5. Java 17
```bash
brew install openjdk@17
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

### 6. Node-RED
```bash
brew install node
npm install -g --unsafe-perm node-red
```

### 7. Maven
```bash
brew install maven
```

### 8. Contiki-NG + Cooja
```bash
cd ~
git clone https://github.com/contiki-ng/contiki-ng.git
cd contiki-ng
git submodule update --init --recursive
```

**If gecko_sdk fails**, run this instead:
```bash
git submodule update --init tools/cooja
```

#### Set Docker Alias (run once)
```bash
echo 'export CNG_PATH=~/contiki-ng' >> ~/.zshrc
echo 'alias contiker="docker run --privileged --mount type=bind,source=$CNG_PATH,destination=/home/user/contiki-ng --sysctl net.ipv6.conf.all.disable_ipv6=0 -e DISPLAY=host.docker.internal:0 -ti contiker/contiki-ng"' >> ~/.zshrc
source ~/.zshrc
```

#### Pull Contiki-NG Docker Image
```bash
docker pull contiker/contiki-ng
```

**Note:** On M1/M2/M3 Macs, Docker will warn about `linux/amd64` vs `arm64` — this is normal and can be ignored.

---

## �🚀 Setup & Execution Guide

Follow these steps in exact chronological order:

### 1. Compile Contiki-NG Firmware
Ensure your Docker Desktop is running and compiling is performed inside the official Contiki-NG build environment.
1. Enter the Docker container:
   ```bash
   docker run --privileged --mount type=bind,source=~/contiki-ng,destination=/home/user/contiki-ng --sysctl net.ipv6.conf.all.disable_ipv6=0 -ti contiker/contiki-ng
   ```
2. Navigate to the project directory:
   ```bash
   cd /home/user/contiki-ng/examples/iot-node/project2-digital-twin/contiki/iot-node
   ```
3. Compile the Cooja firmware:
   ```bash
   make TARGET=cooja
   ```
4. Exit the Docker container:
   ```bash
   exit
   ```
   *(Note: The firmware dynamically reads the system `node_id` from `"sys/node-id.h"`, so a single firmware compilation yields `iot-node.cooja` which works automatically for all nodes!)*

### 2. Start Cooja Simulator (Native Mac)
Do not start Cooja inside Docker. On macOS, run it natively to enable the Java Swing GUI.

⚠️ **Before launching Cooja**, enable X11 connections:
```bash
xhost +localhost
```

Then launch Cooja:
```bash
cd ~/contiki-ng/tools/cooja
./gradlew run
```

In the Cooja GUI:
1. Select **File ➔ Open simulation** and choose `project2-digital-twin/contiki/simulation.csc`
2. Press **Start** to run the RPL low-power network simulation

**Troubleshooting:** If the Cooja window does not open, exit and retry:
```bash
exit
xhost +localhost
docker run --privileged \
  --mount type=bind,source=$CNG_PATH,destination=/home/user/contiki-ng \
  --sysctl net.ipv6.conf.all.disable_ipv6=0 \
  -e DISPLAY=host.docker.internal:0 \
  -ti contiker/contiki-ng bash
cd ~/contiki-ng/tools/cooja && ./gradlew run
```

### 2b. Build Network in Cooja GUI
Once Cooja is open, create the RPL network topology:

1. In the Cooja GUI, select **File ➔ New Simulation**
2. Give it a name (e.g., `digitalTwin-network`)
3. Add motes:
   - Go to **Motes ➔ Add motes ➔ Sky mote**
   - Load the compiled firmware from `~/contiki-ng/examples/iot-node/project2-digital-twin/contiki/iot-node/iot-node.cooja`
   - Add **1 Root/Border Router node** (this collects all traffic)
   - Add **3+ Sensor nodes** (they will automatically form an RPL tree)
4. Start the simulation: Press **Start**
5. Nodes automatically form an RPL tree with the Root at the top

**Sensor Node Capabilities:**
- Send periodic UDP messages to the root every T seconds
- Detect parent changes and notify Node-RED via UDP: `{"nodeId":"1","event":"parent_change","newParent":"fd00::203"}`
- Listen for period change commands from Node-RED: `{"nodeId":"1","event":"set_period","newPeriod":2000}`
- Support crash simulation (stop radio or trigger infinite loop)

### 3. Start Akka Digital Twin
1. Navigate to the Akka directory:
   ```bash
   cd project2-digital-twin/akka
   ```
2. Compile and run the Maven project:
   ```bash
   mvn clean compile exec:java
   ```
This boots the `ActorSystem`, instantiates the supervisor along with three ghost nodes (`node1`, `node2`, `node3`), and starts the HTTP server listening on `http://localhost:8080`.

### 4. Configure & Start Node-RED
1. Run Node-RED in your terminal:
   ```bash
   node-red
   ```
2. Open the Node-RED flow editor in your browser: `http://localhost:1880`.
3. Import the flow:
   - Click the hamburger menu (top-right) ➔ **Import**.
   - Paste the contents of `project2-digital-twin/nodered/flows.json`.
   - Click **Import** and press **Deploy** (top-right red button).

---

## 🛠️ Diagnostics & Troubleshooting (Mac / Apple Silicon M-Series)

* **Cannot connect to Docker daemon:**
  * Ensure Docker Desktop is completely open (wait for the green whale icon in the menu bar).
* **GNU Make version 4.0+ required:**
  * macOS default `make` is an old v3.81. Always compile inside the Docker container using `contiker` where GNU Make is updated to 4.0+.
* **Slow Cooja Interface on M-series Chips:**
  * Java graphics pipeline can feel slow on macOS under Rosetta. Give the GUI 15–30 seconds to settle upon launching.
* **X11 / Display Server Errors:**
  * Make sure you are running Cooja natively on Mac via `./gradlew run` and **not** inside the Linux Docker container, as Docker lacks native access to macOS display servers.
* **Dynamic IP Fallback in Node-RED:**
  * The Node-RED flow keeps track of node IPs dynamically. If a `/setPeriod` HTTP request is made *before* the corresponding node has sent any packets, Node-RED will fall back to `fd00::20X` (where `X` is the node ID), which matches standard Cooja IPv6 formatting.
