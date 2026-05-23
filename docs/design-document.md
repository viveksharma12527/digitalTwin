# NSDS Project 2 — Digital Twin Design Document
**Politecnico di Milano** | Networked Software for Distributed Systems  
**Authors:** [Your Name] & [Partner's Name] | A.Y. 2025/2026

---

## 1. Executive Summary & Design Philosophy
This document details the architectural design and implementation of a real-time Digital Twin for a low-power wireless sensor network. The central design philosophy is **strict separation of layers with zero coordination logic inside the endpoints**. 

Coordination is completely factored out into an external engine (**Node-RED**), which bridges the physical world simulated in **Cooja (Contiki-NG)** and the virtual twin copies running inside the **Akka Actor System**. This decoupling enables the twin behavior to be adapted or modified simply by swapping the Node-RED flow, without recompiling node firmware or altering actor source code.

---

## 2. Layer 1: Real IoT Network & RPL Tree Dynamics
The physical sensor layer is simulated in Cooja using Sky Motes running Contiki-NG.

### 2.1 RPL Tree Topology
* The network is structured as a Destination-Oriented Directed Acyclic Graph (DODAG) governed by the RPL-Lite routing protocol.
* **Mote 1** acts as the RPL Border Router (`fd00::1`), serving as the DAG Root and internet gateway.
* **Motes 2, 3, and 4** run the custom C firmware `iot-node.c` and attach themselves dynamically to the RPL tree.

### 2.2 Telemetry Collection and Transmission
* **Dynamic Node ID Resolution:** Rather than compiling separate firmware binaries with hardcoded IDs, the C code includes `#include "sys/node-id.h"`. Motes resolve their integer ID dynamically at boot from the simulator context.
* **Parent Changed Event:** The process periodically polls `rpl_get_any_dag()`. If the `preferred_parent` changes relative to `last_parent_addr`, it stringifies the IPv6 address and sends a UDP JSON payload to Node-RED on port `5005`:
  ```json
  {"nodeId": 2, "event": "parent_change", "newParent": "fd00::201", "oldParent": "fd00::203"}
  ```
* **Application Telemetry:** Nodes periodically send dummy packets to the Border Router root (port `5678`) and copy these packets to Node-RED on port `5005` as application messages:
  ```json
  {"nodeId": 2, "event": "app_message", "seq": 105}
  ```

---

## 3. Layer 2: Akka Actor System (The Digital Twin)
The virtual world is represented as a hierarchy of Akka Classic actors implemented in Java 21.

### 3.1 Actor Hierarchy
```
                        ┌──────────────────┐
                        │ SupervisorActor  │ (Root)
                        └────────┬─────────┘
            ┌────────────────────┼────────────────────┐
            ▼                    ▼                    ▼
   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
   │  IoTNodeActor   │  │  IoTNodeActor   │  │  IoTNodeActor   │ (Ghost Copies)
   │    [node1]      │  │    [node2]      │  │    [node3]      │
   └─────────────────┘  └─────────────────┘  └─────────────────┘
```

### 3.2 State Management in `IoTNodeActor`
Each ghost actor maintains three essential states:
1. `currentParent` (String): The IPv6 address of the node's current parent.
2. `lastKParents` (List of Strings): A historical list of the last $K=3$ parents, managed as a FIFO queue.
3. `messagePeriod` (int): The current interval between telemetry sends.

### 3.3 Supervision Strategy & Resiliency
* The `SupervisorActor` applies a **One-For-One Strategy**.
* When a node is crashed (represented by an HTTP POST to `/crash` matching a stopped Cooja mote), the supervisor receives a `CrashMsg` and forwards it.
* The corresponding `IoTNodeActor` prints `CRASH!` and throws a `RuntimeException`.
* The supervisor catches the exception, outputs a recovery log, and triggers `SupervisorStrategy.restart()`. This recreates the actor, restoring it to a clean operational state.

---

## 4. Layer 3: Node-RED Orchestration & State Synchronization
Node-RED sits at the center, acting as the coordination hub.

### 4.1 Flow Implementation Details
1. **Telemetry Pipeline (Port 5005 ➔ HTTP 8080):** 
   - A single `UDP In` node on port 5005 receives all incoming packages.
   - The JSON block converts buffers to JavaScript objects.
   - The **Orchestrator and IP Tracker** function node tracks the node's incoming IPv6 address and stores it in the flow context (`node_ip_[nodeId]`). It then maps the event type and routes the payload to the corresponding Akka HTTP POST endpoint:
     * `/parentChanged`
     * `/appMessage`
     * `/crash`
2. **Command Pipeline (HTTP /setPeriod ➔ Port 5010):**
   - Receives an HTTP POST `/setPeriod` from the user or Akka containing the `nodeId` and `newPeriod`.
   - The **Build UDP Cmd & Route IP** node looks up the tracked IPv6 address for that `nodeId`.
   - It outputs three targets:
     * A UDP packet to port 5010 on the specific mote containing the raw payload `"newPeriod:T"`.
     * An HTTP POST to Akka (`/setPeriod`) to synchronize the virtual twin state.
     * An HTTP `200 OK` response to the client.

---

## 5. Live Demonstration & Verification Plan
For your presentation, prepare to demonstrate:
1. **Network Startup:** Boot Cooja and Akka, showing the RPL tree forming in the Cooja visualizer.
2. **Real-time Parent Update:** Move a mote in Cooja. Watch the parent change, observe the log in Node-RED, and see Akka print: `[node2] Parent: fd00::201 ➔ fd00::203`.
3. **Telemetry Mimicking:** Point out the application message logs printing in Akka.
4. **Command Execution:** Send an HTTP request changing the period of Node 3:
   ```bash
   curl -X POST -H "Content-Type: application/json" -d '{"nodeId":3,"newPeriod":2}' http://localhost:1880/setPeriod
   ```
   Show the Cooja log pane print `CMD received: newPeriod:2` and `Period updated to 2 sec` followed by Akka syncing: `[node3] Period: 5 ➔ 2`.
5. **Supervised Recovery:** Stop Mote 2 in Cooja. Trigger a crash via UDP/HTTP. Observe the supervisor restart the corresponding actor copy.
