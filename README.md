# 🌐 NSDS Project 2 — Digital Twin for a Low-power Wireless Network

> **Politecnico di Milano** | Networked Software for Distributed Systems | A.Y. 2025/2026  
> **Technologies:** Contiki-NG · Akka · Node-RED

***

## 📖 What Is This Project?

A **Digital Twin** is a virtual copy of a physical system that stays synchronized in real-time.

This project builds a digital twin of a low-power IoT wireless network:

| Layer | Technology | Role |
|---|---|---|
| Real IoT Network | Contiki-NG + Cooja | Simulates real sensors forming an RPL tree |
| Digital Twin | Akka (Java) | One actor = one sensor's ghost copy |
| Coordination | Node-RED | Keeps real and virtual world in sync |

### ⚠️ Critical Design Rule (Professor will ask!)

- ❌ NO coordination logic inside Contiki-NG
- ❌ NO coordination logic inside Akka actors
- ✅ ALL coordination logic in Node-RED **only**
- You must be able to **swap a Node-RED flow** to change twin behavior without touching Contiki or Akka

***

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────┐
│          COOJA SIMULATOR (Contiki-NG)             │
│                                                   │
│   [Root Node fd00::1]                             │
│        ↑                                          │
│   [Node 1] ──RPL Tree── [Node 2] ── [Node 3]     │
│                                                   │
└─────────────────↕ UDP/JSON ──────────────────────┘
                  ↕
┌──────────────────────────────────────────────────┐
│              NODE-RED  (Port 1880)                │
│  Flow 1: Parent Change  ──→ notify Akka           │
│  Flow 2: App Message    ──→ mimic in Akka         │
│  Flow 3: Set Period T   ──→ command Contiki node  │
│  Flow 4: Crash          ──→ crash Akka actor      │
└─────────────────↕ HTTP ──────────────────────────┘
                  ↕
┌──────────────────────────────────────────────────┐
│             AKKA ACTOR SYSTEM                     │
│  SupervisorActor                                  │
│    ├── IoTNodeActor [node1]                       │
│    │     • currentParent                          │
│    │     • lastKParents[]  (history of K parents) │
│    │     • messagePeriod T                        │
│    ├── IoTNodeActor [node2]                       │
│    └── IoTNodeActor [node3]                       │
└──────────────────────────────────────────────────┘
```

***

## 🔧 Prerequisites — Mac Setup

Install in this exact order:

```bash
# 1. Homebrew
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# 2. Git LFS (for large Contiki submodules)
brew install git-lfs
git lfs install

# 3. Java 21 (for Cooja GUI + Akka)
brew install openjdk@21
echo 'export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
java -version   # should print openjdk 21

# 4. Node.js + Node-RED
brew install node
npm install -g --unsafe-perm node-red

# 5. Maven (for Akka Java project)
brew install maven

# 6. Docker Desktop
# Download: https://www.docker.com/products/docker-desktop/
# Open app and wait for whale icon in menu bar
```

***

## 🌱 Part 1 — Contiki-NG + Cooja Setup

### Clone the Repository

```bash
cd ~
git clone https://github.com/contiki-ng/contiki-ng.git
cd contiki-ng
# If full submodule fails due to gecko_sdk, run this instead:
git submodule update --init tools/cooja
```

### Set Docker Alias (for C firmware compilation only)

```bash
echo 'export CNG_PATH=~/contiki-ng' >> ~/.zshrc
echo 'alias contiker="docker run --privileged \
  --mount type=bind,source=$CNG_PATH,destination=/home/user/contiki-ng \
  --sysctl net.ipv6.conf.all.disable_ipv6=0 \
  -ti contiker/contiki-ng"' >> ~/.zshrc
source ~/.zshrc

# Pull the Docker image
docker pull contiker/contiki-ng
```

### Launch Cooja GUI — On Mac Directly (NO Docker!)

```bash
cd ~/contiki-ng/tools/cooja
./gradlew run
```

> ✅ Cooja is a Java app. Run it natively on Mac.  
> 🐳 Docker is **only** used to compile C firmware — not for the GUI.

***

## 📡 Part 2 — Sensor Node C Code

### Create Project Folder

```bash
cd ~/contiki-ng/examples
mkdir iot-node && cd iot-node
```

### Makefile

```makefile
CONTIKI_PROJECT = iot-node
all: $(CONTIKI_PROJECT)
CONTIKI = ../..
include $(CONTIKI)/Makefile.include
```

### iot-node.c

```c
#include "contiki.h"
#include "net/routing/routing.h"
#include "net/netstack.h"
#include "net/ipv6/simple-udp.h"
#include "net/routing/rpl-lite/rpl.h"
#include "sys/log.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

#define LOG_MODULE "IoT-Node"
#define LOG_LEVEL  LOG_LEVEL_INFO

#define UDP_SERVER_PORT  5678   /* root listens here       */
#define NODERED_PORT     5005   /* Node-RED listens here   */
#define CMD_PORT         5010   /* this node listens here  */
#define DEFAULT_PERIOD   5      /* seconds between sends   */

static struct simple_udp_connection udp_to_root;
static struct simple_udp_connection udp_to_nodered;
static struct simple_udp_connection udp_cmd_listener;

static uip_ipaddr_t root_addr;
static uip_ipaddr_t nodered_addr;
static uip_ipaddr_t last_parent_addr;

static int send_period = DEFAULT_PERIOD;
static int node_id = 1; /* ← CHANGE THIS: 1, 2, or 3 per node */

/* Convert IP to string */
static char ip_buf[40];
static char *ipaddr_to_str(const uip_ipaddr_t *addr) {
  snprintf(ip_buf, sizeof(ip_buf),
    "%02x%02x:%02x%02x:%02x%02x:%02x%02x",
    addr->u8[8],  addr->u8[9],
    addr->u8[10], addr->u8[11],
    addr->u8[12], addr->u8[13],
    addr->u8[14], addr->u8[15]);
  return ip_buf;
}

static int ipaddr_equal(const uip_ipaddr_t *a, const uip_ipaddr_t *b) {
  return memcmp(a, b, sizeof(uip_ipaddr_t)) == 0;
}

/* Receive commands from Node-RED */
static void cmd_rx_callback(struct simple_udp_connection *c,
  const uip_ipaddr_t *sender_addr, uint16_t sender_port,
  const uip_ipaddr_t *receiver_addr, uint16_t receiver_port,
  const uint8_t *data, uint16_t datalen)
{
  char buf[64];
  memcpy(buf, data, datalen < 63 ? datalen : 63);
  buf[datalen < 63 ? datalen : 63] = '\0';
  LOG_INFO("CMD received: %s\n", buf);

  char *p = strstr(buf, "newPeriod");
  if(p != NULL) {
    p = strchr(p, ':');
    if(p != NULL) {
      int new_period = atoi(p + 1);
      if(new_period > 0 && new_period < 3600) {
        send_period = new_period;
        LOG_INFO("Period updated to %d sec\n", send_period);
      }
    }
  }
}

PROCESS(iot_node_process, "IoT Node Process");
AUTOSTART_PROCESSES(&iot_node_process);

PROCESS_THREAD(iot_node_process, ev, data)
{
  static struct etimer timer;
  static char msg_buf[128];
  static char notify_buf[200];
  uip_ipaddr_t current_parent;
  rpl_dag_t *dag;

  PROCESS_BEGIN();

  /* Node-RED machine IPv6 address — update if needed */
  uip_ip6addr(&nodered_addr, 0xfd00,0,0,0,0,0,0,0x0001);

  simple_udp_register(&udp_to_root,      0,        NULL, UDP_SERVER_PORT, NULL);
  simple_udp_register(&udp_to_nodered,   0,        NULL, NODERED_PORT,    NULL);
  simple_udp_register(&udp_cmd_listener, CMD_PORT, NULL, 0, cmd_rx_callback);

  memset(&last_parent_addr, 0, sizeof(last_parent_addr));
  LOG_INFO("Node %d started. Period = %d sec\n", node_id, send_period);

  /* Wait for network to form */
  etimer_set(&timer, CLOCK_SECOND * 10);
  PROCESS_WAIT_UNTIL(etimer_expired(&timer));

  while(1) {

    /* --- CHECK FOR PARENT CHANGE --- */
    dag = rpl_get_any_dag();
    if(dag != NULL && dag->preferred_parent != NULL) {
      uip_ipaddr_copy(&current_parent,
        rpl_parent_get_ipaddr(dag->preferred_parent));

      if(!ipaddr_equal(&current_parent, &last_parent_addr)) {
        LOG_INFO("Parent changed to: %s\n", ipaddr_to_str(&current_parent));

        snprintf(notify_buf, sizeof(notify_buf),
          "{\"nodeId\":%d,\"event\":\"parent_change\","
          "\"newParent\":\"%s\",\"oldParent\":\"%s\"}",
          node_id,
          ipaddr_to_str(&current_parent),
          ipaddr_to_str(&last_parent_addr));

        simple_udp_sendto(&udp_to_nodered,
          notify_buf, strlen(notify_buf), &nodered_addr);

        uip_ipaddr_copy(&last_parent_addr, &current_parent);
      }
    }

    /* --- SEND DUMMY TRAFFIC TO ROOT --- */
    if(NETSTACK_ROUTING.node_is_reachable()) {
      NETSTACK_ROUTING.get_root_ipaddr(&root_addr);

      snprintf(msg_buf, sizeof(msg_buf),
        "{\"nodeId\":%d,\"event\":\"app_message\",\"seq\":%lu}",
        node_id, (unsigned long)clock_time());

      LOG_INFO("Sending to root: %s\n", msg_buf);
      simple_udp_sendto(&udp_to_root,
        msg_buf, strlen(msg_buf), &root_addr);
      simple_udp_sendto(&udp_to_nodered,
        msg_buf, strlen(msg_buf), &nodered_addr);
    }

    /* --- WAIT T SECONDS --- */
    etimer_set(&timer, CLOCK_SECOND * send_period);
    PROCESS_WAIT_UNTIL(etimer_expired(&timer));
  }

  PROCESS_END();
}
```

### Compile (inside Docker — for each node)

```bash
# Enter Docker
contiker bash

# Compile
cd ~/contiki-ng/examples/iot-node
make TARGET=cooja

# Exit Docker
exit
```

> Change `node_id = 1` to `2` or `3` and recompile for each node.  
> Output: `iot-node.cooja`

***

## 🖥️ Part 3 — Cooja Simulation

```
1. Open Cooja:    cd ~/contiki-ng/tools/cooja && ./gradlew run
2. New sim:       File → New Simulation → name: digital-twin
3. Add root:      Motes → Add Motes → Sky Mote
                  → load: examples/rpl-border-router/border-router.cooja
                  → count: 1
4. Add sensors:   Motes → Add Motes → Sky Mote
                  → load: examples/iot-node/iot-node.cooja
                  → count: 3
5. Start:         Press Start button
6. Watch:         Network window shows RPL tree forming
7. Crash a node:  Right-click node → Stop mote
```

***

## 🎭 Part 4 — Akka Digital Twin

### Create Maven Project

```bash
mvn archetype:generate \
  -DgroupId=it.polimi.nsds \
  -DartifactId=digital-twin \
  -DarchetypeArtifactId=maven-archetype-quickstart \
  -DinteractiveMode=false
cd digital-twin
```

### pom.xml — Add Akka

```xml
<dependency>
    <groupId>com.typesafe.akka</groupId>
    <artifactId>akka-actor_2.13</artifactId>
    <version>2.8.0</version>
</dependency>
```

### Messages.java

```java
package it.polimi.nsds;

public class Messages {
    public static class ParentChangedMsg {
        public final String nodeId, newParent, oldParent;
        public ParentChangedMsg(String nodeId, String newParent, String oldParent) {
            this.nodeId = nodeId; this.newParent = newParent; this.oldParent = oldParent;
        }
    }
    public static class AppMessageMsg {
        public final String nodeId; public final long seq;
        public AppMessageMsg(String nodeId, long seq) { this.nodeId = nodeId; this.seq = seq; }
    }
    public static class SetPeriodMsg {
        public final String nodeId; public final int newPeriod;
        public SetPeriodMsg(String nodeId, int newPeriod) { this.nodeId = nodeId; this.newPeriod = newPeriod; }
    }
    public static class CrashMsg {
        public final String nodeId;
        public CrashMsg(String nodeId) { this.nodeId = nodeId; }
    }
}
```

### IoTNodeActor.java

```java
package it.polimi.nsds;

import akka.actor.AbstractActor;
import akka.actor.Props;
import java.util.ArrayList;
import java.util.List;

public class IoTNodeActor extends AbstractActor {

    private final String nodeId;
    private String currentParent = "";
    private final List<String> lastKParents = new ArrayList<>();
    private int messagePeriod;
    private static final int K = 3;

    public IoTNodeActor(String nodeId, int initialPeriod) {
        this.nodeId = nodeId;
        this.messagePeriod = initialPeriod;
    }

    public static Props props(String nodeId, int period) {
        return Props.create(IoTNodeActor.class, nodeId, period);
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
            .match(Messages.ParentChangedMsg.class, this::onParentChanged)
            .match(Messages.AppMessageMsg.class,    this::onAppMessage)
            .match(Messages.SetPeriodMsg.class,     this::onSetPeriod)
            .match(Messages.CrashMsg.class,         this::onCrash)
            .build();
    }

    private void onParentChanged(Messages.ParentChangedMsg msg) {
        System.out.println("[" + nodeId + "] Parent: " + currentParent + " → " + msg.newParent);
        if (!currentParent.isEmpty()) {
            lastKParents.add(0, currentParent);
            if (lastKParents.size() > K) lastKParents.remove(lastKParents.size() - 1);
        }
        currentParent = msg.newParent;
    }

    private void onAppMessage(Messages.AppMessageMsg msg) {
        System.out.println("[" + nodeId + "] App message mimicked. seq=" + msg.seq);
    }

    private void onSetPeriod(Messages.SetPeriodMsg msg) {
        System.out.println("[" + nodeId + "] Period: " + messagePeriod + " → " + msg.newPeriod);
        messagePeriod = msg.newPeriod;
        // Node-RED will forward this command to the real Contiki node
    }

    private void onCrash(Messages.CrashMsg msg) {
        System.out.println("[" + nodeId + "] CRASH!");
        throw new RuntimeException("Node " + nodeId + " crashed!");
    }
}
```

### SupervisorActor.java

```java
package it.polimi.nsds;

import akka.actor.*;
import akka.japi.pf.DeciderBuilder;
import java.util.HashMap;
import java.util.Map;

public class SupervisorActor extends AbstractActor {

    private final Map<String, ActorRef> nodeActors = new HashMap<>();

    public static Props props() { return Props.create(SupervisorActor.class); }

    @Override
    public SupervisorStrategy supervisorStrategy() {
        return new OneForOneStrategy(
            DeciderBuilder
                .match(RuntimeException.class, e -> {
                    System.out.println("Supervisor: restarting crashed actor...");
                    return SupervisorStrategy.restart();
                })
                .matchAny(e -> SupervisorStrategy.escalate())
                .build()
        );
    }

    @Override
    public void preStart() {
        for (int i = 1; i <= 3; i++) {
            String id = "node" + i;
            nodeActors.put(id, getContext().actorOf(IoTNodeActor.props(id, 5), id));
            System.out.println("Digital twin created: " + id);
        }
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
            .match(Messages.ParentChangedMsg.class, msg -> forward(msg.nodeId, msg))
            .match(Messages.AppMessageMsg.class,    msg -> forward(msg.nodeId, msg))
            .match(Messages.SetPeriodMsg.class,     msg -> forward(msg.nodeId, msg))
            .match(Messages.CrashMsg.class,         msg -> forward(msg.nodeId, msg))
            .build();
    }

    private void forward(String nodeId, Object msg) {
        ActorRef actor = nodeActors.get(nodeId);
        if (actor != null) actor.tell(msg, getSelf());
    }
}
```

### Main.java

```java
package it.polimi.nsds;

import akka.actor.*;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        ActorSystem system = ActorSystem.create("DigitalTwinSystem");
        system.actorOf(SupervisorActor.props(), "supervisor");
        System.out.println("Digital Twin System running. Waiting for Node-RED...");
        Thread.sleep(Long.MAX_VALUE);
    }
}
```

### Run Akka

```bash
mvn compile
mvn exec:java -Dexec.mainClass="it.polimi.nsds.Main"
```

***

## 🔴 Part 5 — Node-RED Flows

### Start Node-RED

```bash
node-red
# Open: http://localhost:1880
```

### Flow 1 — Parent Change (Cooja → Akka)

```
[UDP In :5005] → [JSON] → [Function] → [HTTP Request POST]
```

**Function node code:**
```javascript
msg.url = "http://localhost:8080/parentChanged";
msg.method = "POST";
msg.payload = {
    nodeId: msg.payload.nodeId,
    newParent: msg.payload.newParent,
    oldParent: msg.payload.oldParent
};
return msg;
```

### Flow 2 — App Message (Cooja → Akka)

```
[UDP In :5006] → [JSON] → [HTTP Request POST to localhost:8080/appMessage]
```

### Flow 3 — Set Period (Akka → Cooja)

```
[HTTP In POST /setPeriod] → [Function] → [UDP Out → Cooja node :5010]
```

**Function node code:**
```javascript
msg.payload = JSON.stringify({
    nodeId: msg.payload.nodeId,
    event: "set_period",
    newPeriod: msg.payload.newPeriod
});
return msg;
```

### Flow 4 — Crash (Cooja → Both Systems)

```
[UDP In :5007] → [JSON] → [HTTP Request POST to localhost:8080/crash]
```

### UDP Message Format Reference

```json
// Parent change  (Contiki → Node-RED)
{"nodeId":"1","event":"parent_change","newParent":"fd00::203","oldParent":"fd00::201"}

// App message    (Contiki → Node-RED)
{"nodeId":"1","event":"app_message","seq":12345}

// Set period     (Node-RED → Contiki)
{"nodeId":"1","event":"set_period","newPeriod":3}

// Crash          (Contiki → Node-RED)
{"nodeId":"1","event":"crash"}
```

***

## 🗂️ Project Structure

```
project2-digital-twin/
├── README.md
├── contiki/
│   ├── iot-node/
│   │   ├── iot-node.c
│   │   └── Makefile
│   └── simulation.csc
├── akka/
│   ├── pom.xml
│   └── src/main/java/it/polimi/nsds/
│       ├── Main.java
│       ├── SupervisorActor.java
│       ├── IoTNodeActor.java
│       └── Messages.java
├── nodered/
│   └── flows.json
└── docs/
    └── design-document.pdf
```

***

## 🚨 Common Errors & Fixes (Mac)

| Error | Fix |
|---|---|
| `Cannot connect to Docker daemon` | Open Docker Desktop, wait for whale icon |
| `git-lfs: command not found` | `brew install git-lfs && git lfs install` |
| `Can't connect to X11 window server` | Run Cooja natively on Mac — no Docker for GUI |
| `No such file or directory` in Docker | Use full path `/Users/yourname/contiki-ng` |
| `WARNING platform linux/amd64 mismatch` | Normal on M1/M2/M3 Mac — ignore it |
| Cooja window black or frozen | Slow on M-chips — wait 30 seconds |
| `make: command not found` in Docker | `sudo apt-get install build-essential` |

***

## ✅ Requirements Checklist

- [ ] Parent change in Contiki → mirrored in Akka (`currentParent` + `lastKParents` updated)
- [ ] App message in Contiki → mimicked in Akka actor network
- [ ] Changing period `T` in Akka → reflected in real Contiki node via Node-RED
- [ ] Node crash in Contiki → mirrored in actor, supervisor recovers both
- [ ] Node-RED flows can be swapped to change digital twin logic
- [ ] Demo runs on **2+ laptops or VMs**
- [ ] **4-page design document** ready for submission

***

## 📬 Submission

Email **both** instructors at least **2 weeks** before your presentation:

- 📧 luca.mottola@polimi.it
- 📧 alessandro.margara@polimi.it

Attach: **complete source code** + **4-page design document per project**

***

*NSDS Project 2 | Politecnico di Milano | A.Y. 2025/2026*
