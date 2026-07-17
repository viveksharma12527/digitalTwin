/*
 * iot-node.c  —  Contiki-NG IoT node
 *
 * Drop-in replacement for contiki-simulator.py.
 * Produces the same JSON message format so Node-RED + Akka work unchanged.
 *
 * Flow:  Contiki  -->  Node-RED (UDP :5000)  -->  Akka (HTTP)
 *        Akka/Node-RED  -->  Contiki (UDP :6000)  <-- SET_PERIOD / CRASH
 *
 * Message types sent to Node-RED (matching Python simulator exactly):
 *   TRAFFIC      : {"moteId":<id>,"type":"TRAFFIC","seq":<n>,"parent":"<ip>"}
 *   PARENT_CHANGE: {"moteId":<id>,"type":"PARENT_CHANGE","newParentId":<id>}
 *                  (newParentId is the parent's numeric node id, not an
 *                  encoded address -- see parent_id_from_addr())
 *
 * Commands received from Node-RED (same JSON as Python listener):
 *   {"action":"SET_PERIOD","value":<seconds>}   -> update send period
 *   {"action":"CRASH"}                          -> stop sending (node crash)
 *   {"action":"REVIVE"}                         -> resume sending (recovery, symmetric to CRASH)
 */

#include "contiki.h"
#include "net/routing/routing.h"
#include "net/netstack.h"
#include "net/ipv6/simple-udp.h"
#include "net/routing/rpl-lite/rpl.h"
#include "sys/log.h"
#include "sys/node-id.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>

#define LOG_MODULE "IoT-Node"
#define LOG_LEVEL  LOG_LEVEL_INFO

/*
 * Port mapping (must match Node-RED flow / contiki-simulator.py):
 *   NODERED_PORT : Node-RED "udp in" listens here  (Python NODE_RED_PORT=5000)
 *   CMD_PORT     : this node listens for commands   (Python LISTEN_PORT=6000)
 */
#define NODERED_PORT    5000   /* Node-RED listens here                    */
#define CMD_PORT        6000   /* this node listens here for commands      */
#define UDP_SERVER_PORT 5678   /* RPL root listens here (mesh traffic)     */
#define DEFAULT_PERIOD  5      /* seconds between sends (Python state["T"])*/

/* ------------------------------------------------------------------ */
/* UDP connections                                                      */
/* ------------------------------------------------------------------ */
static struct simple_udp_connection udp_to_nodered;   /* outbound → Node-RED  */
static struct simple_udp_connection udp_to_root;      /* outbound → RPL root  */
static struct simple_udp_connection udp_cmd_listener; /* inbound  ← Node-RED  */

/* ------------------------------------------------------------------ */
/* Addresses                                                           */
/* ------------------------------------------------------------------ */
static uip_ipaddr_t nodered_addr;
static uip_ipaddr_t root_addr;
static uip_ipaddr_t last_parent_addr;

/* ------------------------------------------------------------------ */
/* State (mirrors Python's state dict)                                  */
/* ------------------------------------------------------------------ */
static int  send_period = DEFAULT_PERIOD;  /* state["T"]          */
static int  is_running  = 1;               /* state["is_running"]  */
static unsigned long seq_counter = 0;      /* monotonic sequence   */

/* ------------------------------------------------------------------ */
/* Helper: IPv6 address → compact hex string                           */
/* ------------------------------------------------------------------ */
static char ip_buf[40];
static const char *ipaddr_to_str(const uip_ipaddr_t *addr)
{
  snprintf(ip_buf, sizeof(ip_buf),
    "%02x%02x:%02x%02x:%02x%02x:%02x%02x",
    addr->u8[8],  addr->u8[9],
    addr->u8[10], addr->u8[11],
    addr->u8[12], addr->u8[13],
    addr->u8[14], addr->u8[15]);
  return ip_buf;
}

static int ipaddr_equal(const uip_ipaddr_t *a, const uip_ipaddr_t *b)
{
  return memcmp(a, b, sizeof(uip_ipaddr_t)) == 0;
}

/*
 * A Contiki-NG node's IID is derived from its short address as
 * 0000:00ff:fe00:<node_id>, so the node's numeric id lives in the last two
 * bytes. Sending that id directly (instead of an encoded IP string) means
 * Node-RED doesn't have to reverse-engineer which byte group it lives in.
 */
static int parent_id_from_addr(const uip_ipaddr_t *addr)
{
  return (addr->u8[14] << 8) | addr->u8[15];
}

/* ------------------------------------------------------------------ */
/* Helper: lightweight JSON string search                              */
/*   Finds the value after  "key":"   or  "key":   in buf.            */
/* ------------------------------------------------------------------ */
static const char *json_str_value(const char *buf, const char *key)
{
  const char *p = strstr(buf, key);
  if(!p) return NULL;
  p += strlen(key);
  while(*p == ' ' || *p == ':') p++;
  if(*p == '"') p++;   /* skip opening quote for string values */
  return p;
}

/* ------------------------------------------------------------------ */
/* Command receiver  (Python: listen_for_commands)                     */
/*   Handles:                                                          */
/*     {"action":"SET_PERIOD","value":<n>}                             */
/*     {"action":"CRASH"}                                              */
/* ------------------------------------------------------------------ */
static void cmd_rx_callback(struct simple_udp_connection *c,
  const uip_ipaddr_t *sender_addr, uint16_t sender_port,
  const uip_ipaddr_t *receiver_addr, uint16_t receiver_port,
  const uint8_t *data, uint16_t datalen)
{
  char buf[128];
  uint16_t copy_len = datalen < (sizeof(buf) - 1) ? datalen : (sizeof(buf) - 1);
  memcpy(buf, data, copy_len);
  buf[copy_len] = '\0';
  LOG_INFO("CMD received: %s\n", buf);

  /* --- SET_PERIOD  (Python: state["T"] = cmd["value"]) --- */
  if(strstr(buf, "\"SET_PERIOD\"") != NULL) {
    const char *vp = json_str_value(buf, "\"value\"");
    if(vp != NULL) {
      int new_period = atoi(vp);
      if(new_period > 0 && new_period < 3600) {
        send_period = new_period;
        LOG_INFO("[CMD] Period T updated to %d sec\n", send_period);
      }
    }
    return;
  }

  /* --- CRASH  (Python: state["is_running"] = False) --- */
  if(strstr(buf, "\"CRASH\"") != NULL) {
    is_running = 0;
    LOG_INFO("[CMD] Node CRASHED!\n");
    return;
  }

  /* --- REVIVE  (symmetric recovery: resume periodic traffic) --- */
  if(strstr(buf, "\"REVIVE\"") != NULL) {
    is_running = 1;
    LOG_INFO("[CMD] Node REVIVED!\n");
    return;
  }
}

/* ------------------------------------------------------------------ */
/* Contiki process                                                      */
/* ------------------------------------------------------------------ */
PROCESS(iot_node_process, "IoT Node Process");
AUTOSTART_PROCESSES(&iot_node_process);

PROCESS_THREAD(iot_node_process, ev, data)
{
  static struct etimer timer;
  static char msg_buf[256];
  static char parent_str[40];  /* holds previous parent string safely  */
  uip_ipaddr_t current_parent;
  rpl_dag_t *dag;

  PROCESS_BEGIN();

  /*
   * Node-RED host address.
   * In Cooja simulation the border router is typically at fd00::1.
   * Adjust to match your actual Node-RED machine's IPv6 address.
   * For the Python simulator replacement on localhost: fd00::1
   */
  uip_ip6addr(&nodered_addr, 0xfd00,0,0,0,0,0,0,0x0001);

  /* Register UDP connections */
  simple_udp_register(&udp_to_root,      0,        NULL, UDP_SERVER_PORT, NULL);
  simple_udp_register(&udp_to_nodered,   0,        NULL, NODERED_PORT,    NULL);
  simple_udp_register(&udp_cmd_listener, CMD_PORT, NULL, 0, cmd_rx_callback);

  memset(&last_parent_addr, 0, sizeof(last_parent_addr));
  memset(parent_str, 0, sizeof(parent_str));
  LOG_INFO("Node %d started. Period = %d sec\n", node_id, send_period);

  /* Wait for network to form (mirrors Python startup delay implicitly) */
  etimer_set(&timer, CLOCK_SECOND * 10);
  PROCESS_WAIT_UNTIL(etimer_expired(&timer));

  /* ----------------------------------------------------------------
   * Main loop  (mirrors Python's two threads collapsed into one)
   * ---------------------------------------------------------------- */
  while(1) {

    /* --- CHECK FOR PARENT CHANGE (Python: manual 'p' key → PARENT_CHANGE) --- */
    dag = rpl_get_any_dag();
    if(dag != NULL && dag->preferred_parent != NULL) {
      uip_ipaddr_copy(&current_parent,
        rpl_parent_get_ipaddr(dag->preferred_parent));

      if(!ipaddr_equal(&current_parent, &last_parent_addr)) {
        /* Build PARENT_CHANGE JSON — matches Python send_to_nodered("PARENT_CHANGE", ...) */
        snprintf(msg_buf, sizeof(msg_buf),
          "{\"moteId\":%d,\"type\":\"PARENT_CHANGE\",\"newParentId\":%d}",
          node_id,
          parent_id_from_addr(&current_parent));

        LOG_INFO("[SENT] PARENT_CHANGE: %s\n", msg_buf);
        simple_udp_sendto(&udp_to_nodered,
          msg_buf, strlen(msg_buf), &nodered_addr);

        /* Save new parent */
        uip_ipaddr_copy(&last_parent_addr, &current_parent);
        strncpy(parent_str, ipaddr_to_str(&current_parent), sizeof(parent_str) - 1);
        parent_str[sizeof(parent_str) - 1] = '\0';
      }
    }

    /* --- PERIODIC TRAFFIC  (Python: periodic_traffic → send_to_nodered("TRAFFIC",...)) --- */
    if(is_running && NETSTACK_ROUTING.node_is_reachable()) {
      seq_counter++;

      /* Fetch root address for mesh delivery */
      NETSTACK_ROUTING.get_root_ipaddr(&root_addr);

      /*
       * TRAFFIC JSON — matches Python payload exactly:
       *   {"moteId":<id>,"type":"TRAFFIC","seq":<n>,"parent":"<ip>"}
       * Node-RED switch routes on payload.type == "TRAFFIC"
       */
      snprintf(msg_buf, sizeof(msg_buf),
        "{\"moteId\":%d,\"type\":\"TRAFFIC\",\"seq\":%lu,\"parent\":\"%s\"}",
        node_id,
        seq_counter,
        parent_str[0] != '\0' ? parent_str : "none");

      LOG_INFO("[SENT] TRAFFIC: %s\n", msg_buf);

      /* Send to Node-RED (digital twin) */
      simple_udp_sendto(&udp_to_nodered,
        msg_buf, strlen(msg_buf), &nodered_addr);

      /* Also forward to RPL root (mesh backbone) */
      simple_udp_sendto(&udp_to_root,
        msg_buf, strlen(msg_buf), &root_addr);
    }

    /* --- WAIT T seconds (Python: time.sleep(state["T"])) --- */
    etimer_set(&timer, CLOCK_SECOND * send_period);
    PROCESS_WAIT_UNTIL(etimer_expired(&timer));
  }

  PROCESS_END();
}
