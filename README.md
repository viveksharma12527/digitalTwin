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

## 🚀 Setup & Execution Guide

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
1. Launch Cooja:
   ```bash
   cd ~/contiki-ng/tools/cooja
   ./gradlew run
   ```
2. In the Cooja GUI, select **File ➔ Open simulation** and choose `project2-digital-twin/contiki/simulation.csc`.
3. Press **Start** to run the RPL low-power network simulation.

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
