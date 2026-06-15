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
| **UDP/IPv6** | `5000` | Contiki Nodes | Node-RED | Telemetry channel (parent change, app messages, crash reports) |
| **UDP/IPv6** | `6000` | Node-RED | Contiki Nodes | Command channel (message period T changes) |
| **HTTP/JSON**| `8080` | Node-RED | Akka Twin | HTTP POST endpoints to update/crash virtual actors |
| **HTTP/JSON**| `1880` | User/Akka | Node-RED | Set period endpoints `/set-params` |
| **TCP**      | `60001`| Cooja Mote 1 | tunslip6 | Cooja serial port socket |

### JSON Specifications
* **Parent Changed Event (Contiki ➔ Node-RED ➔ Akka):**
  ```json
  {"nodeId": 2, "event": "PARENT_CHANGE", "newParentId": "0201:0000:0000:0000"}
  ```
* **App Message Telemetry (Contiki ➔ Node-RED ➔ Akka):**
  ```json
  {"nodeId": 2, "event": "TRAFFIC", "seq": 2400500, "parent": "0201:0000:0000:0000"}
  ```
* **Set Period Command (HTTP Client ➔ Node-RED ➔ Contiki):**
  ```json
  {"moteId": 2, "newT": 3}
  ```
* **Crash Mote Event (Contiki ➔ Node-RED ➔ Akka):**
  ```json
  {"nodeId": 2, "event": "crash"}
  ```

---

## 💻 Prerequisites (macOS)

Before you begin, install these dependencies natively on your Mac (compilation for Cooja runs natively to match the host Java simulator):

### 1. Homebrew & System Utilities
```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
brew install git maven node
```

### 2. XQuartz (for Cooja GUI on Mac) ⚠️ IMPORTANT
```bash
brew install --cask xquartz
```
**After installation, RESTART your Mac.** Then:
1. Open XQuartz.
2. Go to **Preferences ➔ Security**.
3. Check **"Allow connections from network clients"**.

### 3. Java 21 (JDK)
Ensure you are using Java 21, which is required by both Akka and the latest Gradle wrappers:
```bash
brew install openjdk@21
# Add openjdk to path (zsh)
echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

### 4. Node-RED Flow Editor
```bash
npm install -g --unsafe-perm node-red
```

### 5. Contiki-NG + Cooja Repo Setup
Ensure Contiki-NG is cloned into your home directory (`~/contiki-ng`):
```bash
cd ~
git clone https://github.com/contiki-ng/contiki-ng.git
cd contiki-ng
git submodule update --init tools/cooja
```

---

## 🚀 The Quick-Start Runner (`run.sh`)

We have created an automated, all-in-one runner script called `run.sh` in the project root directory. It compiles the firmware natively and starts/stops all background services automatically.

### Commands Reference
* `./run.sh compile` — Build the Contiki firmware natively on macOS.
* `./run.sh start` — Start all services (Node-RED, Akka, Cooja Simulator, tunslip6).
* `./run.sh stop` — Stop and kill all background processes.
* `./run.sh status` — Get a quick status view of running ports and logs.
* `./run.sh dashboard` — Open a live, auto-refreshing terminal dashboard.
* `./run.sh test` — Run the end-to-end verification test suite.

---

### Step-by-Step Execution Workflow

### Step 1: Clean & Build Firmware
Run the compiler script to build the firmware natively on macOS. This will automatically clean any Linux ELF objects (from Docker) to prevent compiler/linker architecture conflicts:
```bash
./run.sh compile
```

### Step 2: Start All Services
Start the Node-RED runtime, compile and launch the Akka HTTP server, and boot the Cooja Simulator:
```bash
./run.sh start
```
*Note: This command starts the serial bridge `tunslip6` in the background. If sudo password prompt is required, it will output a terminal warning. You can also run the command with `sudo ./run.sh start` to ensure your password is cached for the tunnel.*

### Step 3: Monitor in Live Dashboard
Open the live dashboard to view the health of all services, open network ports, and the latest 3 lines of logs for each component:
```bash
./run.sh dashboard
```

### Step 4: Run Verification Tests
Verify that Node-RED is forwarding packets and Akka endpoints are receiving data:
```bash
./run.sh test
```

### Step 5: Clean Shutdown
To shut down and kill all background processes safely:
```bash
./run.sh stop
```

---

## 🛠️ Manual Execution Guide (Without `run.sh`)

If you prefer to run the components manually in separate terminal windows:

### 1. Compile Motes Natively
```bash
cd ~/contiki-ng/examples/iot-node
make clean TARGET=cooja
make -j8 TARGET=cooja
```

### 2. Start Cooja
```bash
xhost +localhost
cd ~/contiki-ng/tools/cooja
./gradlew run --args="/Users/vivek/Documents/GitHub/MUTI/digitalTwin/contiki/simulation_mac.csc"
```
*(Once Cooja GUI loads, press the **Start** button to begin the simulation)*

### 3. Connect the Tunnel
In a new terminal window:
```bash
cd ~/contiki-ng/tools/serial-io
sudo ./tunslip6 -a 127.0.0.1 -p 60001 fd00::1/64
```

### 4. Start Node-RED
```bash
node-red
```
Open `http://localhost:1880` and import `/Users/vivek/Documents/GitHub/MUTI/digitalTwin/NSDS-Digitaltwin-Project-master/nodered.json`. Click **Deploy**.

### 5. Start Akka Twin
```bash
cd /Users/vivek/Documents/GitHub/MUTI/digitalTwin/NSDS-Digitaltwin-Project-master/akka/digitaltwin
mvn clean compile exec:java -Dexec.mainClass=com.digitaltwin.App
```

---

## 🔍 Diagnostics & macOS-Specific Gotchas

* **"unknown file type in build/cooja/obj/*.o" Linker Errors**:
  * This happens when you mixed compiling in Docker (generating Linux ELF binaries) with building/running Cooja natively on macOS (which uses Mach-O). Fix this by running `./run.sh compile` which performs a clean host compile.
* **Cooja Fails to start with `-quickstart` error**:
  * Newer versions of Cooja (Contiki-NG v5+) use Picocli for CLI options and no longer support the old `-quickstart=` flag. Pass the path directly to the wrapper as a positional argument (e.g. `./gradlew run --args="path/to/simulation.csc"`).
* **Akka System.in.read() Exit on Start**:
  * The Java application uses `System.in.read()` to halt execution when ENTER is pressed. If run in the background with redirected stdin (`< /dev/null`), it exits immediately. In `run.sh`, we pipe `tail -f /dev/null` into it to keep stdin open and prevent premature exits.
