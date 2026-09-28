#ifndef LIBRETRODROID_NETPACKET_H
#define LIBRETRODROID_NETPACKET_H

#include <cstddef>
#include <cstdint>

#include "../third_party/libretro/libretro-common/include/libretro.h"

namespace libretrodroid {

// Core callbacks and transport registration are confined to the emulation thread.
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
    using TransportPoll = void (*)(void*);
    void setPoll(TransportPoll poll);
    void pollReceive();
    bool connected(uint16_t clientId);
    void disconnected(uint16_t clientId);

private:
    const retro_netpacket_callback* core = nullptr;
    bool running = false;
    void* transportContext = nullptr;
    TransportSend transportSend = nullptr;
    TransportPoll transportPoll = nullptr;
};

}

#endif
