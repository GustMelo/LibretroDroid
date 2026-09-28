#include "netpacket.h"

namespace libretrodroid {

static Netpacket* active = nullptr;

Netpacket& Netpacket::getInstance() {
    static Netpacket instance;
    return instance;
}

static void sendThunk(int flags, const void* data, size_t size, uint16_t clientId, bool broadcast) {
    if (active) active->send(flags, data, size, clientId, broadcast);
}

void Netpacket::setCoreInterface(const retro_netpacket_callback* callbacks) {
    core = callbacks;
}

void Netpacket::clear() {
    if (core && core->stop) core->stop();
    core = nullptr;
    sendFn = nullptr;
    transportContext = nullptr;
    transportSend = nullptr;
    active = nullptr;
}

bool Netpacket::start(uint16_t clientId) {
    if (!core || !core->start) return false;
    active = this;
    core->start(clientId, sendThunk);
    return true;
}

void Netpacket::stop() {
    if (core && core->stop) core->stop();
    sendFn = nullptr;
    active = nullptr;
}

void Netpacket::poll() {
    if (core && core->poll) core->poll();
}

bool Netpacket::send(int flags, const void* data, size_t size, uint16_t clientId, bool broadcast) {
    if (!transportSend) return false;
    transportSend(transportContext, flags, data, size, clientId, broadcast);
    return true;
}

void Netpacket::setTransport(void* context, TransportSend send) {
    transportContext = context;
    transportSend = send;
}

void Netpacket::receive(const void* data, size_t size, uint16_t clientId) {
    if (core && core->receive) core->receive(data, size, clientId);
}

}
