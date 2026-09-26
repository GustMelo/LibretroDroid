#include "netplay.h"

#include "log.h"

#include <algorithm>

namespace libretrodroid {
void Netplay::start(int localPort, int players, int inputDelay, int hashInterval, bool rollback) {
    std::lock_guard<std::mutex> guard(lock);
    ++generation;
    local = localPort;
    playerCount = std::clamp(players, 1, MAX_PLAYERS);
    delay = std::clamp(inputDelay, 0, 30);
    hashEvery = hashInterval;
    rollbackMode = rollback;
    frame = 0;
    running = 0;
    stallCount = 0;
    rollbackFrom = NONE;
    pendingHash = NONE;
    advantage = 0;
    vsyncsSinceSyncSkip = 0;
    outbox.clear();
    stats = Stats {};
    for (auto& port : slots) port.fill(Slot {});
    for (auto& port : used) port.fill(Slot {});

    for (int p = 0; p < MAX_PLAYERS; p++) {
        for (uint32_t f = 0; f < static_cast<uint32_t>(delay); f++) {
            slots[p][f % WINDOW] = Slot { f, 0 };
        }
        confirmed[p] = static_cast<int64_t>(delay) - 1;
        newest[p] = static_cast<int64_t>(delay) - 1;
    }
    active = true;
    LOGI("netplay: start local=%d players=%d delay=%d mode=%s", local, playerCount, delay, rollback ? "rollback" : "lockstep");
}

void Netplay::stop() {
    std::lock_guard<std::mutex> guard(lock);
    if (active) LOGI("netplay: stop at frame %u (stalls=%u)", frame, stallCount);
    active = false;
    outbox.clear();
    arrived.notify_all();
}

void Netplay::advanceConfirmedLocked(int port) {
    int64_t c = confirmed[port];
    while (slots[port][static_cast<uint32_t>(c + 1) % WINDOW].frame == static_cast<uint32_t>(c + 1)) c++;
    confirmed[port] = c;
}

int64_t Netplay::confirmedThroughLocked() const {
    int64_t result = INT64_MAX;
    for (int p = 0; p < playerCount; p++) result = std::min(result, confirmed[p]);
    return result;
}

void Netplay::setRemote(int port, uint32_t f, uint16_t buttons) {
    if (port < 0 || port >= MAX_PLAYERS) return;
    std::lock_guard<std::mutex> guard(lock);
    if (!active || port == local) return;
    if (f + WINDOW <= frame || f >= frame + WINDOW) return;
    Slot& slot = slots[port][f % WINDOW];
    if (slot.frame == f) return;
    slot = Slot { f, buttons };
    newest[port] = std::max<int64_t>(newest[port], f);
    advanceConfirmedLocked(port);

    if (rollbackMode) {
        const Slot& guess = used[port][f % WINDOW];
        if (guess.frame == f && guess.buttons != buttons) rollbackFrom = std::min(rollbackFrom, f);
    }
    arrived.notify_all();
}

void Netplay::recordLocalLocked(uint16_t localButtons) {
    const uint32_t target = frame + static_cast<uint32_t>(delay);
    Slot& mine = slots[local][target % WINDOW];
    if (mine.frame != target) {
        mine = Slot { target, localButtons };
        newest[local] = target;
        advanceConfirmedLocked(local);
        outbox.push_back(Outgoing { Outgoing::LOCAL_INPUT, target, localButtons });
    }
}

bool Netplay::readyLocked(uint32_t f) const {
    for (int p = 0; p < playerCount; p++) {
        if (slots[p][f % WINDOW].frame != f) return false;
    }
    return true;
}

bool Netplay::beginFrame(uint16_t localButtons, std::chrono::microseconds timeout) {
    std::unique_lock<std::mutex> guard(lock);
    if (!active) return true;
    recordLocalLocked(localButtons);

    const uint32_t current = frame;
    running = current;
    if (arrived.wait_for(guard, timeout, [&] { return !active || readyLocked(current); })) return true;
    stallCount++;
    return false;
}

Netplay::Plan Netplay::beginRollbackFrame(uint16_t localButtons, std::chrono::microseconds timeout) {
    std::unique_lock<std::mutex> guard(lock);
    Plan plan;
    plan.frame = frame;
    if (!active) return plan;
    recordLocalLocked(localButtons);

    int64_t slowest = INT64_MAX;
    for (int p = 0; p < playerCount; p++) {
        if (p != local) slowest = std::min(slowest, newest[p] - delay + 1);
    }
    vsyncsSinceSyncSkip++;
    if (slowest != INT64_MAX) {
        advantage = advantage * 0.95 + (static_cast<double>(frame) - static_cast<double>(slowest)) * 0.05;
        uint32_t spacing = advantage > 3 ? 10 : 60;
        if (advantage > 1.5 && vsyncsSinceSyncSkip > spacing) {
            vsyncsSinceSyncSkip = 0;
            advantage -= 1;
            stats.syncSkips++;
            return plan;
        }
    }

    for (int p = 0; p < playerCount; p++) {
        if (p != local && newest[p] < static_cast<int64_t>(delay)) {
            if (!arrived.wait_for(guard, timeout, [&] { return !active || newest[p] >= static_cast<int64_t>(delay); })) {
                stallCount++;
                return plan;
            }
        }
    }

    auto fits = [&] { return !active || static_cast<int64_t>(frame) - (confirmedThroughLocked() + 1) < MAX_ROLLBACK; };
    if (!arrived.wait_for(guard, timeout, fits)) {
        stallCount++;
        return plan;
    }
    plan.run = true;
    plan.rollbackFrom = rollbackFrom < frame ? rollbackFrom : NONE;
    rollbackFrom = NONE;
    return plan;
}

uint16_t Netplay::predictLocked(int port, uint32_t f) const {
    int64_t c = std::min<int64_t>(confirmed[port], static_cast<int64_t>(f) - 1);
    if (c < 0) return 0;
    const Slot& slot = slots[port][static_cast<uint32_t>(c) % WINDOW];
    return slot.frame == static_cast<uint32_t>(c) ? slot.buttons : 0;
}

void Netplay::prepareFrame(uint32_t f) {
    std::lock_guard<std::mutex> guard(lock);
    running = f;
    for (int p = 0; p < playerCount; p++) {
        const Slot& slot = slots[p][f % WINDOW];
        uint16_t value = slot.frame == f ? slot.buttons : predictLocked(p, f);
        used[p][f % WINDOW] = Slot { f, value };
    }
}

uint32_t Netplay::takeConfirmedHashFrame() {
    std::lock_guard<std::mutex> guard(lock);
    if (pendingHash == NONE || pendingHash >= frame) return NONE;
    if (confirmedThroughLocked() < static_cast<int64_t>(pendingHash) - 1) return NONE;
    if (rollbackFrom < pendingHash) return NONE;
    uint32_t result = pendingHash;
    pendingHash = NONE;
    return result;
}

uint16_t Netplay::buttons(unsigned port) const {
    std::lock_guard<std::mutex> guard(lock);
    if (port >= MAX_PLAYERS) return 0;
    if (rollbackMode) {
        const Slot& slot = used[port][running % WINDOW];
        return slot.frame == running ? slot.buttons : 0;
    }
    const Slot& slot = slots[port][frame % WINDOW];
    return slot.frame == frame ? slot.buttons : 0;
}

bool Netplay::advance() {
    std::lock_guard<std::mutex> guard(lock);
    frame++;
    bool hashFrame = hashEvery > 0 && frame % static_cast<uint32_t>(hashEvery) == 0;
    if (rollbackMode) {
        if (hashFrame) pendingHash = frame;
        return false;
    }
    return hashFrame;
}

void Netplay::pushStateHash(uint32_t f, uint64_t hash) {
    std::lock_guard<std::mutex> guard(lock);
    outbox.push_back(Outgoing { Outgoing::STATE_HASH, f, hash });
}

uint64_t Netplay::generationId() const {
    std::lock_guard<std::mutex> guard(lock);
    return generation;
}

void Netplay::publishStateCheck(uint64_t expected, uint32_t f, uint64_t hash, const uint64_t* blocks, size_t count) {
    std::lock_guard<std::mutex> guard(lock);
    if (!active || expected != generation) return;
    outbox.push_back(Outgoing { Outgoing::STATE_HASH, f, hash });
    if (blocks) for (size_t i = 0; i < std::min<size_t>(count, 128); ++i) {
        outbox.push_back(Outgoing { static_cast<Outgoing::Type>(Outgoing::DIAGNOSTIC_BASE + i), f, blocks[i] });
    }
}

void Netplay::recordVsync(bool ran, std::chrono::microseconds wait, std::chrono::microseconds run,
                          uint32_t replayed, std::chrono::microseconds snapshot) {
    auto now = std::chrono::steady_clock::now();
    if (stats.vsyncs == 0) stats.since = now;
    stats.vsyncs++;
    if (ran) stats.frames++; else stats.stalls++;
    if (replayed > 0) stats.rollbacks++;
    stats.replayed += replayed;
    stats.replayMax = std::max(stats.replayMax, replayed);
    stats.waitUs += wait.count();
    stats.waitMaxUs = std::max<int64_t>(stats.waitMaxUs, wait.count());
    stats.runUs += run.count();
    stats.runMaxUs = std::max<int64_t>(stats.runMaxUs, run.count());
    stats.snapshotUs += snapshot.count();
    double seconds = std::chrono::duration<double>(now - stats.since).count();
    if (seconds < 5) return;
    uint32_t syncSkips = stats.syncSkips;
    LOGI("netplay stats: fps=%.1f vsync=%.1f/s stalls=%u syncSkips=%u rollbacks=%u replayed=%u (max %u) "
         "wait avg=%.2fms max=%.2fms run avg=%.2fms max=%.2fms snapshot avg=%.3fms advantage=%.2f frame=%u",
         stats.frames / seconds, stats.vsyncs / seconds, stats.stalls - syncSkips, syncSkips, stats.rollbacks,
         stats.replayed, stats.replayMax,
         stats.waitUs / 1000.0 / stats.vsyncs, stats.waitMaxUs / 1000.0,
         stats.frames ? stats.runUs / 1000.0 / stats.frames : 0.0, stats.runMaxUs / 1000.0,
         stats.frames ? stats.snapshotUs / 1000.0 / stats.frames : 0.0, advantage, frame);
    uint64_t counters = std::min<uint32_t>(stats.stalls - syncSkips, 65535)
        | (static_cast<uint64_t>(std::min<uint32_t>(stats.replayMax, 65535)) << 16)
        | (static_cast<uint64_t>(std::min<uint32_t>(stats.rollbacks, 65535)) << 32)
        | (static_cast<uint64_t>(std::min<uint32_t>(stats.replayed, 65535)) << 48);
    {
        std::lock_guard<std::mutex> guard(lock);
        outbox.push_back(Outgoing { Outgoing::PERFORMANCE, frame, counters });
    }
    stats = Stats {};
}

std::vector<Netplay::Outgoing> Netplay::drainOutbox() {
    std::lock_guard<std::mutex> guard(lock);
    std::vector<Outgoing> result;
    result.swap(outbox);
    return result;
}
}
