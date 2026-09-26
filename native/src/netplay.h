#ifndef LIBRETRODROID_NETPLAY_H
#define LIBRETRODROID_NETPLAY_H

#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <vector>

namespace libretrodroid {
class Netplay {
public:
    static constexpr int MAX_PLAYERS = 4;
    static constexpr uint32_t NONE = UINT32_MAX;
    static constexpr uint32_t MAX_ROLLBACK = 8;

    struct Outgoing {
        enum Type { LOCAL_INPUT, STATE_HASH, PERFORMANCE, DIAGNOSTIC_BASE = 100, DIAGNOSTIC_END = 227 } type;
        uint32_t frame;
        uint64_t value;
    };

    struct Plan {
        bool run = false;
        uint32_t rollbackFrom = NONE;
        uint32_t frame = 0;
    };

    static Netplay& getInstance() {
        static Netplay instance;
        return instance;
    }

    void start(int localPort, int players, int inputDelay, int hashInterval, bool rollback);
    void stop();
    bool isActive() const { return active; }
    bool isRollback() const { return rollbackMode; }
    int localPort() const { return local; }
    uint32_t currentFrame() const { return frame; }

    void setRemote(int port, uint32_t frame, uint16_t buttons);

    bool beginFrame(uint16_t localButtons, std::chrono::microseconds timeout);

    Plan beginRollbackFrame(uint16_t localButtons, std::chrono::microseconds timeout);

    void prepareFrame(uint32_t f);

    uint32_t takeConfirmedHashFrame();

    uint16_t buttons(unsigned port) const;

    bool advance();

    void pushStateHash(uint32_t frame, uint64_t hash);
    uint64_t generationId() const;
    void publishStateCheck(uint64_t generation, uint32_t frame, uint64_t hash, const uint64_t* blocks, size_t count);

    std::vector<Outgoing> drainOutbox();

    uint32_t stalls() const { return stallCount; }

    void recordVsync(bool ran, std::chrono::microseconds wait, std::chrono::microseconds run,
                     uint32_t replayed = 0, std::chrono::microseconds snapshot = {});

private:
    static constexpr uint32_t WINDOW = 512;

    struct Slot {
        uint32_t frame = NONE;
        uint16_t buttons = 0;
    };

    bool readyLocked(uint32_t f) const;
    void recordLocalLocked(uint16_t localButtons);
    int64_t confirmedThroughLocked() const;
    void advanceConfirmedLocked(int port);
    uint16_t predictLocked(int port, uint32_t f) const;

    mutable std::mutex lock;
    std::condition_variable arrived;

    uint64_t generation = 0;
    bool active = false;
    bool rollbackMode = false;
    int local = 0;
    int playerCount = 1;
    int delay = 2;
    int hashEvery = 0;
    uint32_t frame = 0;
    uint32_t running = 0;
    uint32_t stallCount = 0;

    std::array<std::array<Slot, WINDOW>, MAX_PLAYERS> slots {};
    std::array<std::array<Slot, WINDOW>, MAX_PLAYERS> used {};
    std::array<int64_t, MAX_PLAYERS> confirmed {};
    std::array<int64_t, MAX_PLAYERS> newest {};
    uint32_t rollbackFrom = NONE;
    uint32_t pendingHash = NONE;
    double advantage = 0;
    uint32_t vsyncsSinceSyncSkip = 0;

    std::vector<Outgoing> outbox;

    struct Stats {
        std::chrono::steady_clock::time_point since {};
        uint32_t vsyncs = 0, frames = 0, stalls = 0, rollbacks = 0, replayed = 0, replayMax = 0, syncSkips = 0;
        int64_t waitUs = 0, waitMaxUs = 0, runUs = 0, runMaxUs = 0, snapshotUs = 0;
    } stats;
};
}

#endif
