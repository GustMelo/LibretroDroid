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
    std::lock_guard guard(lock);
    outbox.clear();
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
    if (!data || size == 0) return false;
    {
        std::lock_guard guard(lock);
        outbox.push_back({flags, std::vector<uint8_t>(static_cast<const uint8_t*>(data), static_cast<const uint8_t*>(data) + size), clientId, broadcast});
    }
    if (transportSend) transportSend(transportContext, flags, data, size, clientId, broadcast);
    return true;
}

void Netpacket::setTransport(void* context, TransportSend send) {
    transportContext = context;
    transportSend = send;
}

void Netpacket::drain(void* context, PacketCallback callback) {
    if (!callback) return;
    std::vector<Outgoing> pending;
    {
        std::lock_guard guard(lock);
        pending.swap(outbox);
    }
    for (const auto& packet : pending) callback(context, packet.flags, packet.data.data(), packet.data.size(), packet.clientId, packet.broadcast);
}

void Netpacket::receive(const void* data, size_t size, uint16_t clientId) {
    if (core && core->receive) core->receive(data, size, clientId);
}

}
