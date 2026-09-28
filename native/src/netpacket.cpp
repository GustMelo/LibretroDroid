#include "netpacket.h"

namespace libretrodroid {

static Netpacket* active = nullptr;

Netpacket& Netpacket::getInstance() {
    static Netpacket instance;
    return instance;
}

static void sendThunk(int flags, const void* data, size_t size, uint16_t clientId) {
    if (active) active->send(flags, data, size, clientId, clientId == RETRO_NETPACKET_BROADCAST);
}

static void pollReceiveThunk() {
    if (active) active->pollReceive();
}

void Netpacket::setCoreInterface(const retro_netpacket_callback* callbacks) {
    clear();
    core = callbacks;
}

void Netpacket::clear() {
    stop();
    core = nullptr;
    transportContext = nullptr;
    transportSend = nullptr;
    active = nullptr;
    transportPoll = nullptr;
}

bool Netpacket::start(uint16_t clientId) {
    if (running || !transportSend || !core || !core->start || !core->receive) return false;
    running = true;
    active = this;
    core->start(clientId, sendThunk, pollReceiveThunk);
    return true;
}

void Netpacket::stop() {
    const bool wasRunning = running;
    running = false;
    active = nullptr;
    if (wasRunning && core && core->stop) core->stop();
    transportSend = nullptr;
    transportContext = nullptr;
    transportPoll = nullptr;
}

void Netpacket::poll() {
    if (running && core && core->poll) core->poll();
}

bool Netpacket::send(int flags, const void* data, size_t size, uint16_t clientId, bool broadcast) {
    if (!running || !transportSend || size > 65536) return false;
    // NULL/zero length is a flush request in the current Netpacket API.
    if (!data && size != 0) return false;
    transportSend(transportContext, flags, data, size, clientId, broadcast);
    return true;
}

void Netpacket::setTransport(void* context, TransportSend send) {
    transportContext = context;
    transportSend = send;
}

void Netpacket::setPoll(TransportPoll poll) { transportPoll = poll; }

void Netpacket::pollReceive() {
    if (running && transportPoll) transportPoll(transportContext);
}

bool Netpacket::connected(uint16_t clientId) {
    return running && core && (!core->connected || core->connected(clientId));
}

void Netpacket::disconnected(uint16_t clientId) {
    if (running && core && core->disconnected) core->disconnected(clientId);
}

void Netpacket::receive(const void* data, size_t size, uint16_t clientId) {
    if (running && data && size > 0 && size <= 65536 && core && core->receive) core->receive(data, size, clientId);
}

}
