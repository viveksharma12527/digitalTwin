# NSDS Project 2 — Digital Twin for a Low-power Wireless Network
Politecnico di Milano — Networked Software for Distributed Systems

================================================================
WHAT IS THIS PROJECT?
================================================================

You have tiny battery-powered sensors talking wirelessly.
You create a virtual ghost copy of every sensor on your laptop.
When a real sensor changes route → ghost updates.
When a real sensor crashes → ghost crashes and recovers.
That is a Digital Twin.

3 technologies working together:
- Contiki-NG + Cooja  →  simulates the real IoT sensor network
- Akka (Java)         →  virtual ghost copies (digital twins)
- Node-RED            →  the glue keeping real and virtual in sync

================================================================
ARCHITECTURE
================================================================

[COOJA SIMULATOR]
    [Root Node]
        ↑
    [Node 1] ←RPL Tree→ [Node 2] → [Node 3]
         ↕ UDP/JSON
[NODE-RED - THE GLUE]
    Flow 1: Parent Change  → notify Akka
    Flow 2: App Message    → mimic in Akka
    Flow 3: Set Period T   → command Contiki node
    Flow 4: Crash detected → crash Akka actor
         ↕ HTTP/TCP
[AKKA ACTOR SYSTEM]
    SupervisorActor
        ├── IoTNodeActor (twin of Node 1)
        │     state: currentParent, lastKParents, T
        ├── IoTNodeActor (twin of Node 2)
        └── IoTNodeActor (twin of Node 3)

CRITICAL RULE:
  NO coordination logic in Contiki-NG
  NO coordination logic in Akka actors
  ALL coordination logic ONLY in Node-RED
  You must be able to SWAP a Node-RED flow to change behavior

================================================================
PREREQUISITES — INSTALL THESE FIRST (MAC)
================================================================

1. Homebrew
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

2. Git LFS
   brew install git-lfs
   git lfs install

3. Docker Desktop
   Download: https://www.docker.com/products/docker-desktop/
   Open it → wait for whale icon in menu bar

4. XQuartz (for Cooja GUI on Mac)
   brew install --cask xquartz
   *** RESTART MAC AFTER THIS ***
   Then: XQuartz → Preferences → Security → CHECK "Allow connections from network clients"

5. Java 17
   brew install openjdk@17
   echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
   source ~/.zshrc

6. Node-RED
   brew install node
   npm install -g --unsafe-perm node-red

7. Maven
   brew install maven

================================================================
PART 1 — CONTIKI-NG + COOJA SETUP
================================================================

Step 1: Clone
   cd ~
   git clone https://github.com/contiki-ng/contiki-ng.git
   cd contiki-ng
   git submodule update --init --recursive

   IF gecko_sdk fails, run this instead:
   git submodule update --init tools/cooja

Step 2: Set Docker alias (run once)
   echo 'export CNG_PATH=~/contiki-ng' >> ~/.zshrc
   echo 'alias contiker="docker run --privileged --mount type=bind,source=$CNG_PATH,destination=/home/user/contiki-ng --sysctl net.ipv6.conf.all.disable_ipv6=0 -e DISPLAY=host.docker.internal:0 -ti contiker/contiki-ng"' >> ~/.zshrc
   source ~/.zshrc

Step 3: Pull Docker image
   docker pull contiker/contiki-ng

Step 4: Enter container
   contiker bash
   → You will see: user@abc123:/home/user$
   → WARNING about linux/amd64 vs arm64 is NORMAL on M1/M2/M3 — ignore it

Step 5: Launch Cooja
   ON MAC TERMINAL (not inside Docker):
   xhost +localhost

   INSIDE DOCKER:
   cd ~/contiki-ng/tools/cooja
   ./gradlew run

Step 6: Fix if Cooja window does not open
   exit
   xhost +localhost
   docker run --privileged \
     --mount type=bind,source=/Users/vivek/contiki-ng,destination=/home/user/contiki-ng \
     --sysctl net.ipv6.conf.all.disable_ipv6=0 \
     -e DISPLAY=host.docker.internal:0 \
     -ti contiker/contiki-ng bash
   cd ~/contiki-ng/tools/cooja && ./gradlew run

================================================================
PART 2 — BUILD IOT NODES IN COOJA
================================================================

Network to create:
- 1 Root/Border Router node (all traffic goes here)
- 3+ Sensor nodes (form RPL tree, send periodic messages)

Build sensor node (inside Docker):
   cd ~/contiki-ng/examples/udp-client
   make TARGET=cooja

What your sensor node C code must do:
   1. Send periodic UDP messages to root every T seconds
   2. Detect parent change → notify Node-RED via UDP
      Send: {"nodeId":"1","event":"parent_change","newParent":"fd00::203"}
   3. Listen for period change commands from Node-RED
      Receive: {"nodeId":"1","event":"set_period","newPeriod":2000}
   4. Support crash simulation (stop radio or infinite loop)

Create simulation in Cooja GUI:
   File → New Simulation → name it
   Motes → Add motes → Sky mote → load your .cooja file
   Add 1 root + 3-5 sensor nodes
   Press Start → nodes auto-form RPL tree

================================================================
PART 3 — AKKA DIGITAL TWIN
================================================================

pom.xml dependency:
   <dependency>
       <groupId>com.typesafe.akka</groupId>
       <artifactId>akka-actor_2.13</artifactId>
       <version>2.8.0</version>
   </dependency>

IoTNodeActor.java:
   public class IoTNodeActor extends AbstractActor {
       private String nodeId;
       private String currentParent;
       private List<String> lastKParents;
       private int messagePeriod;

       public Receive createReceive() {
           return receiveBuilder()
               .match(ParentChangedMsg.class, this::onParentChanged)
               .match(AppMessageMsg.class,    this::onAppMessage)
               .match(SetPeriodMsg.class,     this::onSetPeriod)
               .match(CrashMsg.class,         this::onCrash)
               .build();
       }

       private void onParentChanged(ParentChangedMsg msg) {
           lastKParents.add(0, currentParent);
           if (lastKParents.size() > K) lastKParents.remove(lastKParents.size()-1);
           currentParent = msg.newParent;
       }

       private void onCrash(CrashMsg msg) {
           throw new RuntimeException("Node crashed!");
       }
   }

SupervisorActor.java:
   public SupervisorStrategy supervisorStrategy() {
       return new OneForOneStrategy(
           DeciderBuilder
               .match(Exception.class, e -> SupervisorStrategy.restart())
               .build()
       );
   }

================================================================
PART 4 — NODE-RED FLOWS
================================================================

Start Node-RED:
   node-red
   Open browser: http://localhost:1880

Flow 1 — Parent Change (Cooja to Akka):
   [UDP In :5005] → [JSON] → [Function] → [HTTP POST to Akka]

Flow 2 — App Message (Cooja to Akka):
   [UDP In :5006] → [JSON] → [Function] → [HTTP POST to Akka]

Flow 3 — Set Period (Akka to Cooja):
   [HTTP In /setPeriod] → [Function] → [UDP Out to Cooja]

Flow 4 — Crash (Cooja to both systems):
   [UDP In :5007] → [JSON] → [HTTP POST crash to Akka]
                           → [UDP command restart to Cooja]

UDP message formats:
   Parent change:  {"nodeId":"1","event":"parent_change","newParent":"fd00::203","oldParent":"fd00::201"}
   App message:    {"nodeId":"1","event":"app_message","destination":"fd00::1"}
   Set period:     {"nodeId":"1","event":"set_period","newPeriod":2000}
   Crash:          {"nodeId":"1","event":"crash"}

================================================================
PROJECT FOLDER STRUCTURE
================================================================

project2-digital-twin/
├── README.md
├── contiki/
│   ├── iot-node/
│   │   ├── iot-node.c
│   │   └── Makefile
│   └── simulation.csc
├── akka/
│   ├── pom.xml
│   └── src/main/java/
│       ├── Main.java
│       ├── IoTNodeActor.java
│       ├── SupervisorActor.java
│       └── messages/
├── nodered/
│   └── flows.json
└── docs/
    └── design-document.pdf

================================================================
REQUIREMENTS CHECKLIST
================================================================

[ ] Parent change in Contiki mirrored in Akka actor
[ ] App message flow in Contiki mimicked in Akka network
[ ] Changing T in Akka reflected in real Contiki node
[ ] Node crash in Contiki mirrored in actor + recovery in both
[ ] Node-RED can be swapped to change digital twin logic
[ ] Demo runs on 2+ laptops or VMs
[ ] 4-page design document ready

================================================================
COMMON MAC ERRORS AND FIXES
================================================================

Cannot connect to Docker daemon
→ Open Docker Desktop app first

git-lfs: command not found
→ brew install git-lfs && git lfs install

Can't connect to X11 window server
→ Open XQuartz, enable network clients, run: xhost +localhost

No such file or directory: contiki-ng
→ Use full path: /Users/vivek/contiki-ng in Docker command

WARNING platform linux/amd64 mismatch
→ Normal on M1/M2/M3 Mac — ignore it

Cooja window black or frozen
→ Docker emulation is slow on M-chips — wait 30 seconds

================================================================
SUBMISSION
================================================================

Email BOTH instructors:
  luca.mottola@polimi.it
  alessandro.margara@polimi.it

At least 2 weeks before your presentation date.
Attach: complete source code + 4-page design document.
Demo must run on 2+ machines.

NSDS Project 2 — Polimi — A.Y. 2025/2026
