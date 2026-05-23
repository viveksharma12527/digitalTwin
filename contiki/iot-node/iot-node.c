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
