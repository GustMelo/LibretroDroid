#ifndef LIBRETRODROID_NETPACKET_H
#define LIBRETRODROID_NETPACKET_H

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

#include "../third_party/libretro/libretro-common/include/libretro.h"

namespace libretrodroid {

class Netpacket {
public:
    using TransportSend = void (*)(void*, int, const void*, size_t, uint16_t, bool);
    static Netpacket& getInstance();

    void setCoreInterface(const retro_netpacket_callback* callbacks);
    void clear();
    bool start(uint16_t clientId);
    void stop();
    void poll();
    bool send(int flags, const void* data, size_t size, uint16_t clientId, bool broadcast);
    void receive(const void* data, size_t size, uint16_t clientId);
    void setTransport(void* context, TransportSend send);
    using PacketCallback = void (*)(void*, int, const void*, size_t, uint16_t, bool);
    void drain(void* context, PacketCallback callback);

private:
    const retro_netpacket_callback* core = nullptr;
    retro_netpacket_send_t sendFn = nullptr;
    void* transportContext = nullptr;
    TransportSend transportSend = nullptr;
    struct Outgoing { int flags; std::vector<uint8_t> data; uint16_t clientId; bool broadcast; };
    std::mutex lock;
    std::vector<Outgoing> outbox;
};

}

#endif
