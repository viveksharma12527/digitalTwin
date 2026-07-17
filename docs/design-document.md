# NSDS Project 2 — Digital Twin Design Document
**Politecnico di Milano** | Networked Software for Distributed Systems
**Authors:** Vivek Kumar Sharma & Shaurya *[surname]* | A.Y. 2025/2026
**Professor:** Luca Mottola

---

## 1. Executive Summary & Design Philosophy
This document details the architectural design and implementation of a real-time Digital Twin for a low-power wireless sensor network. The central design philosophy is **strict separation of layers with zero coordination logic inside the endpoints**.

Coordination is completely factored out into an external engine (**Node-RED**), which bridges the physical world simulated in **Cooja (Contiki-NG)** and the virtual twin copies running inside the **Akka Actor System**. This decoupling enables the twin behavior to be adapted or modified simply by swapping the Node-RED flow, without recompiling node firmware or altering actor source code.

---

## 2. Layer 1: Real IoT Network & RPL Tree Dynamics
The physical sensor layer is simulated in Cooja using native "Cooja motes" running Contiki-NG.

### 2.1 RPL Tree Topology
* The network is structured as a Destination-Oriented Directed Acyclic Graph (DODAG) governed by the RPL-Lite routing protocol.
* **Mote 1** runs the standard `rpl-border-router` firmware (`fd00::1`), serving as the DAG Root and internet gateway. It carries no digital-twin logic whatsoever.
* **Motes 2, 3, and 4** run the custom C firmware `iot-node.c` and attach themselves dynamically to the RPL tree.

### 2.2 Telemetry Collection and Transmission
* **Dynamic Node ID Resolution:** Rather than compiling separate firmware binaries with hardcoded IDs, the C code includes `#include "sys/node-id.h"`. Motes resolve their integer ID dynamically at boot from the simulator context.
* **Parent Changed Event:** The process periodically checks `rpl_get_any_dag()->preferred_parent`. If it changed relative to `last_parent_addr`, the node resolves the parent's numeric node id directly from its IID (`0000:00ff:fe00:<node_id>` — the id lives in the last two bytes) and sends a UDP JSON payload to Node-RED on port `5000`:
  ```json
  {"moteId": 2, "type": "PARENT_CHANGE", "newParentId": 3}
  ```
  Sending the numeric id directly — rather than an encoded address string — means Node-RED doesn't need to reverse-engineer which byte group holds it, and the same field works unchanged whether it came from real Cooja motes or `contiki-simulator.py`.
* **Application Telemetry:** Nodes periodically send dummy packets to the Border Router root (port `5678`) and, in parallel, send the same payload to Node-RED on port `5000` as application telemetry:
  ```json
  {"moteId": 2, "type": "TRAFFIC", "seq": 105, "parent": "0201:0000:0000:0203"}
  ```
* **Command Reception:** Each node also listens on port `6000` for two generic, physical-layer commands that carry no digital-twin semantics of their own:
  ```json
  {"action": "SET_PERIOD", "value": 3}
  {"action": "CRASH"}
  {"action": "REVIVE"}
  ```
  `SET_PERIOD` updates the send interval; `CRASH` stops periodic sending (`is_running = 0`); `REVIVE` is the **symmetric counterpart** of `CRASH` — it resumes periodic sending (`is_running = 1`) and is what allows a crashed physical node to actually recover, rather than staying down forever once crashed.

---

## 3. Layer 2: Akka Actor System (The Digital Twin)
The virtual world is represented as a hierarchy of Akka Classic actors implemented in Java 21.

### 3.1 Actor Hierarchy
```
                    ┌──────────────────┐
                    │   MoteManager    │  (supervisor, one per system)
                    └────────┬─────────┘
            ┌────────────────┼────────────────┐
            ▼                ▼                ▼
     ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
     │  ModeActor  │  │  ModeActor  │  │  ModeActor  │  (twin copies)
     │  "twin-2"   │  │  "twin-3"   │  │  "twin-4"   │
     └─────────────┘  └─────────────┘  └─────────────┘
```
Twins are **not** pre-created: `MoteManager` routes every inbound message by `moteId` and, on first contact, lazily spawns a child `ModeActor` for that ID via `getContext().actorOf(ModeActor.props(), "twin-" + moteId)`. Mote 1 (the border router) never sends a message of any of the four types below, so it never gets a twin — by design, only the four IoT-node concepts (parent, traffic, period, crash) are represented in the actor system.

### 3.2 State Management in `ModeActor`
Each twin actor maintains exactly the state required by the assignment, and nothing else:
1. `currentParent` (int): the node's current parent identifier, resolved by Node-RED from the mote's IPv6 address and passed in as a plain integer.
2. `parentHistory` (`LinkedList<Integer>`): a FIFO of the last `K = 3` **previous** parents. The current parent is pushed onto this list only when it is actually superseded by a new one (never duplicated, never pushed on the very first assignment).
3. `periodT` (int): the current interval between telemetry sends, default `5`.

No `ModeActor` performs network I/O directly, and neither does `MoteManager`: the only outbound side effect either of them triggers is period-sync, and even that is a relay through Node-RED (see §4.1), never a coordination decision made in Akka.

### 3.3 Supervision Strategy & Symmetric Crash Recovery
Recovery in this system is **bidirectional**: the twin recovers, and that recovery is mirrored back onto the physical/simulated node — not just the other way around. Crucially, *which side decides to trigger the physical revive* had to be chosen carefully: the assignment requires that "the needed coordination between Akka actors and IoT nodes must be implemented in a separate component using Node-RED," so Akka must never be the one deciding to command the physical/simulated node — it may only react to facts Node-RED reports to it.

1. A crash (self-triggered `CRASH` command from the physical layer, or externally detected via Node-RED's watchdog) reaches `ModeActor` as a `MoteCrashed` message. The actor logs `CRASH!` and **throws** a dedicated `MoteCrashSimulationException`, carrying the `moteId`.
2. `MoteManager` applies a **One-For-One** `SupervisorStrategy`: only the crashed mote's own twin is restarted; sibling twins are unaffected. Restarting recreates the actor via `ModeActor.props()`, resetting `currentParent`, `parentHistory`, and `periodT` to a clean state — this is the actor-side recovery, and it is the *only* thing the decider does. It does **not** call out to Node-RED to request a physical revive.
3. Node-RED is the one that decided a crash occurred in the first place (either it forwarded a genuine `CRASH` telemetry event, or its own watchdog detected silence — see §4.1), so it is also the one that decides when to revive the physical/simulated node: after a short recovery delay, it sends the mote a `REVIVE` UDP command directly, then confirms the recovery back to Akka via `POST /revived {moteId}`. `MoteManager` routes this to the twin (`ModeActor` just logs it — there is no state left to reset) and `DigitalTwinServer` broadcasts a `REVIVED` dashboard event.

This keeps the recovery *policy* — whether to revive at all, how long to wait, whether to retry — entirely swappable by editing the Node-RED flow, with zero changes to Akka or Contiki-NG source: exactly the "swap the flow to change the twin logic" property the assignment calls for.

---

## 4. Layer 3: Node-RED Orchestration & State Synchronization
Node-RED sits at the center, acting as the **only** component in the system aware of both the physical and the digital-twin world. All protocol translation and per-node address routing lives here, in one importable/swappable flow (`nodered.json`).

### 4.1 Flow Implementation Details
1. **Telemetry Pipeline (UDP `:5000` → HTTP `:8080`):**
   - A single `udp in` node, opened in **dual-stack (`udp6`)** mode, receives all incoming telemetry. This matters: motes reach Node-RED over a genuine IPv6 path (`tunslip6` bridging Cooja's RPL mesh, rooted at `fd00::1`, to the host); a plain IPv4-only (`udp4`) socket would silently drop all real mesh traffic while still appearing to work under local loopback testing.
   - A `json` node parses the payload, followed by an **IP Tracker** function node that records the sender's source address (`msg.ip`) keyed by `moteId` in flow context (`node_ip_<id>`). This is what makes outbound commands *addressable*: a later `SET_PERIOD` or `REVIVE` request is routed to that specific mote's last-known address, not a single fixed target.
   - A `switch` node dispatches on `payload.type`: `TRAFFIC` → `POST /traffic {moteId}`; `PARENT_CHANGE` → `POST /parent {moteId, newParentId}` (both fields are just passed through — no per-node address parsing needed, since motes already send `newParentId` as a plain integer).
   - A **function-based** dead-man's-switch (`crash watchdog`) replaces a fixed-duration `trigger` node: it re-arms a per-`moteId` `setTimeout` on every message, sized to `3×periodT + 5` seconds rather than a flat window. `periodT` is read from flow context (`period_<moteId>`, updated whenever a `SET_PERIOD` request for that mote succeeds; it defaults to `5`, matching the firmware/simulator default). This is what lets a mote's own configurable period be raised well past what an old fixed 10-second window would have tolerated, without the watchdog mistaking a legitimately slow mote for a crashed one. If the timer fires, it posts `POST /crash {moteId}` — this is how a physically stopped Cooja mote (which cannot itself announce its own death) still gets mirrored as a crash in the digital twin.
2. **Period Command Pipeline (HTTP `/set-params` → UDP `:6000`):**
   - Receives `POST /set-params {moteId, newT}` from Akka (`PhysicalNodeSync`), which itself was invoked by a user-facing `POST /updateT` request against Akka's HTTP API.
   - A function node records the confirmed period in flow context (`period_<moteId>`, feeding the watchdog above), looks up the tracked IP address for that `moteId`, and builds the UDP payload `{"action":"SET_PERIOD","value":T,"moteId":X}`, falling back to `localhost` with a warning if no address has been observed yet.
   - The command is sent via a `udp out` node (also dual-stack, port `6000`) with a dynamic destination address (`msg.ip`), plus an HTTP `200 OK` response back to Akka.
3. **Crash → Revive Pipeline (owned entirely by Node-RED):**
   - The moment the crash watchdog fires (or a genuine `CRASH` telemetry event is forwarded), the same message fans out two ways: one branch posts `/crash` to Akka as described above; the other passes through a fixed recovery `delay` node and into a `revive_handler` function.
   - `revive_handler` reuses the tracked-IP lookup to send `{"action":"REVIVE","moteId":X}` directly to the mote over the same `udp out` node used for period commands, and separately confirms the recovery to Akka via `POST /revived {moteId}`.
   - Nothing in this pipeline is triggered by an inbound call from Akka — Node-RED decided the mote had crashed, so Node-RED also decides when it has recovered. Akka only ever learns about the crash and the revival after the fact, via `/crash` and `/revived`.

---

## 5. Live Demonstration & Verification Plan
For your presentation, prepare to demonstrate:
1. **Network Startup:** Boot Cooja and Akka, showing the RPL tree forming in the Cooja visualizer.
2. **Real-time Parent Update:** Move a mote in Cooja. Watch the parent change, observe the log in Node-RED, and see Akka print: `Parent changed to: <id>`.
3. **Telemetry Mimicking:** Point out the application traffic logs printing in Akka (`App traffic received for mote: <id>`), and the live traffic counter climbing on the web dashboard.
4. **Command Execution:** Send an HTTP request changing the period of Node 3:
   ```bash
   curl -X POST -H "Content-Type: application/json" \
     -d '{"moteId":3,"newT":2}' http://localhost:8080/updateT
   ```
   Show the Cooja log pane print `CMD received: {"action":"SET_PERIOD",...}` and `[CMD] Period T updated to 2 sec`, followed by Akka syncing: `Twin period T updated to: 2 for mote 3`.
5. **Symmetric Supervised Recovery:** Trigger a crash for a mote (stop it in Cooja, or `POST /crash {"moteId":2}`). Observe:
   - Akka: `CRASH!` → `ModeActor stopped` → `ModeActor started` (the supervised restart, with clean state) — decided entirely by `MoteManager`'s supervisor, with no outbound call to Node-RED;
   - A few seconds later, Node-RED's own recovery delay elapses and it sends the `REVIVE` UDP command on its own initiative, then confirms it to Akka;
   - Akka: `Node 2 revived.` (logged by the twin on receiving `MoteRevived`), and the dashboard's `stat-crashes`/mote color reset to healthy via the `REVIVED` SSE event;
   - Cooja's log pane for that mote: `[CMD] Node REVIVED!`, followed by periodic `TRAFFIC` sends resuming.

   This closes the loop required by the assignment: the crash is mirrored into the twin, **and** the twin's recovery is mirrored symmetrically back onto the physical/simulated node — with the recovery *decision* living in Node-RED throughout, not in Akka.

---

## Appendix: Verified End-to-End Behavior

The full pipeline described above has been exercised live (not just unit-tested) against a running Node-RED instance, a running Akka process, and UDP peers standing in for physical motes:

* Injected `PARENT_CHANGE` / `TRAFFIC` packets were confirmed to reach the correct Akka endpoint and to be broadcast, correctly shaped, over the SSE stream (`GET /events`) consumed by the dashboard.
* A `/updateT` request for one mote was confirmed to produce a `SET_PERIOD` UDP command addressed **only** to that mote's tracked IP, not a shared fixed target.
* A simulated crash was confirmed to (i) restart the correct twin with clean state, without disturbing sibling twins, (ii) cause Node-RED — not Akka — to deliver a `REVIVE` command to the physical/simulated mote after a fixed recovery delay, and (iii) result in a `REVIVED` event reaching the dashboard once Node-RED confirms the recovery back to Akka.
* `contiki-simulator.py` was confirmed to reach Node-RED's `udp6` listener by speaking IPv6 loopback (`::1`) itself, matching the same socket family real `tunslip6`-bridged Cooja motes use — a plain IPv4 socket to `127.0.0.1` cannot reach an IPv6-only listener.

A formatted ~4-page design document covering the same architecture is available at `document design/main.pdf`.
