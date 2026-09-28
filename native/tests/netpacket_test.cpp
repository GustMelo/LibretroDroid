#include "../src/netpacket.h"
#include <cassert>
using libretrodroid::Netpacket;
static int starts, stops, sends, receives, polls, receivePolls, joins, leaves;
static retro_netpacket_poll_receive_t pump;
static retro_netpacket_send_t emit;
static void start(uint16_t, retro_netpacket_send_t send, retro_netpacket_poll_receive_t pollReceive) { ++starts; emit = send; pump = pollReceive; }
static void stop() { ++stops; }
static void receive(const void*, size_t, uint16_t) { ++receives; }
static void poll() { ++polls; }
static void transport(void*, int, const void*, size_t, uint16_t, bool) { ++sends; }
static void transportPoll(void*) { ++receivePolls; }
static bool connected(uint16_t id) { ++joins; return id == 1; }
static void disconnected(uint16_t) { ++leaves; }
int main() {
    auto& bridge = Netpacket::getInstance();
    retro_netpacket_callback callbacks { start, receive, stop, poll, connected, disconnected, "test-v1" };
    bridge.setCoreInterface(&callbacks);
    assert(!bridge.start(0));
    bridge.setTransport(nullptr, transport);
    bridge.setPoll(transportPoll);
    assert(bridge.start(0));
    assert(bridge.connected(1));
    assert(!bridge.connected(2));
    bridge.disconnected(1);
    pump();
    assert(joins == 2 && leaves == 1 && receivePolls == 1);
    assert(!bridge.start(0));
    const uint8_t data[] { 42 };
    emit(1, data, 1, 1);
    assert(sends == 1);
    emit(0, nullptr, 0, 1);
    assert(sends == 2);
    assert(!bridge.send(1, data, 65537, 1, false));
    bridge.receive(data, 1, 1);
    bridge.poll();
    assert(receives == 1 && polls == 1);
    bridge.stop();
    bridge.stop();
    bridge.receive(data, 1, 1);
    bridge.poll();
    emit(1, data, 1, 1);
    assert(stops == 1 && receives == 1 && polls == 1 && sends == 2);
    pump();
    assert(receivePolls == 1);
    assert(!bridge.connected(1));
    bridge.disconnected(1);
    assert(leaves == 1);
    bridge.clear();
    assert(stops == 1 && starts == 1);
}
