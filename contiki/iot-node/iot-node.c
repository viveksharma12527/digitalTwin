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

#define NODERED_PORT    5000
#define CMD_PORT        6000
#define UDP_SERVER_PORT 5678
#define DEFAULT_PERIOD  5

static struct simple_udp_connection udp_to_nodered;
static struct simple_udp_connection udp_to_root;
static struct simple_udp_connection udp_cmd_listener;

static uip_ipaddr_t nodered_addr;
static uip_ipaddr_t root_addr;
static uip_ipaddr_t last_parent_addr;

static int  send_period = DEFAULT_PERIOD;
static int  is_running  = 1;
static unsigned long seq_counter = 0;

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

static int parent_id_from_addr(const uip_ipaddr_t *addr)
{
  return (addr->u8[14] << 8) | addr->u8[15];
}

static const char *json_str_value(const char *buf, const char *key)
{
  const char *p = strstr(buf, key);
  if(!p) return NULL;
  p += strlen(key);
  while(*p == ' ' || *p == ':') p++;
  if(*p == '"') p++;
  return p;
}

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

  if(strstr(buf, "\"CRASH\"") != NULL) {
    is_running = 0;
    LOG_INFO("[CMD] Node CRASHED!\n");
    return;
  }

  if(strstr(buf, "\"REVIVE\"") != NULL) {
    is_running = 1;
    LOG_INFO("[CMD] Node REVIVED!\n");
    return;
  }
}

PROCESS(iot_node_process, "IoT Node Process");
AUTOSTART_PROCESSES(&iot_node_process);

PROCESS_THREAD(iot_node_process, ev, data)
{
  static struct etimer timer;
  static char msg_buf[256];
  static char parent_str[40];
  uip_ipaddr_t current_parent;
  rpl_dag_t *dag;

  PROCESS_BEGIN();

  uip_ip6addr(&nodered_addr, 0xfd00,0,0,0,0,0,0,0x0001);

  simple_udp_register(&udp_to_root,      0,        NULL, UDP_SERVER_PORT, NULL);
  simple_udp_register(&udp_to_nodered,   0,        NULL, NODERED_PORT,    NULL);
  simple_udp_register(&udp_cmd_listener, CMD_PORT, NULL, 0, cmd_rx_callback);

  memset(&last_parent_addr, 0, sizeof(last_parent_addr));
  memset(parent_str, 0, sizeof(parent_str));
  LOG_INFO("Node %d started. Period = %d sec\n", node_id, send_period);

  etimer_set(&timer, CLOCK_SECOND * 10);
  PROCESS_WAIT_UNTIL(etimer_expired(&timer));

  while(1) {

    dag = rpl_get_any_dag();
    if(dag != NULL && dag->preferred_parent != NULL) {
      uip_ipaddr_copy(&current_parent,
        rpl_parent_get_ipaddr(dag->preferred_parent));

      if(!ipaddr_equal(&current_parent, &last_parent_addr)) {
        snprintf(msg_buf, sizeof(msg_buf),
          "{\"moteId\":%d,\"type\":\"PARENT_CHANGE\",\"newParentId\":%d}",
          node_id,
          parent_id_from_addr(&current_parent));

        LOG_INFO("[SENT] PARENT_CHANGE: %s\n", msg_buf);
        simple_udp_sendto(&udp_to_nodered,
          msg_buf, strlen(msg_buf), &nodered_addr);

        uip_ipaddr_copy(&last_parent_addr, &current_parent);
        strncpy(parent_str, ipaddr_to_str(&current_parent), sizeof(parent_str) - 1);
        parent_str[sizeof(parent_str) - 1] = '\0';
      }
    }

    if(is_running && NETSTACK_ROUTING.node_is_reachable()) {
      seq_counter++;

      NETSTACK_ROUTING.get_root_ipaddr(&root_addr);

      snprintf(msg_buf, sizeof(msg_buf),
        "{\"moteId\":%d,\"type\":\"TRAFFIC\",\"seq\":%lu,\"parent\":\"%s\"}",
        node_id,
        seq_counter,
        parent_str[0] != '\0' ? parent_str : "none");

      LOG_INFO("[SENT] TRAFFIC: %s\n", msg_buf);

      simple_udp_sendto(&udp_to_nodered,
        msg_buf, strlen(msg_buf), &nodered_addr);

      simple_udp_sendto(&udp_to_root,
        msg_buf, strlen(msg_buf), &root_addr);
    }

    etimer_set(&timer, CLOCK_SECOND * send_period);
    PROCESS_WAIT_UNTIL(etimer_expired(&timer));
  }

  PROCESS_END();
}
