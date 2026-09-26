#ifndef LIBRETRODROID_STATE_HASH_WORKER_H
#define LIBRETRODROID_STATE_HASH_WORKER_H

#include "netplay_state_hash.h"
#include <algorithm>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <thread>

namespace libretrodroid {
class StateHashWorker {
public:
    using Publish = std::function<void(uint64_t, uint32_t, uint64_t, const StateDiagnostic*, size_t)>;
    explicit StateHashWorker(Publish publish) : publish(std::move(publish)), worker([this] { run(); }) {}
    ~StateHashWorker() {
        {
            std::lock_guard<std::mutex> guard(lock);
            stopped = true;
        }
        arrived.notify_one();
        worker.join();
    }
    void submit(const std::vector<uint8_t>& bytes, uint32_t frame, uint64_t generation, bool diagnose) {
        {
            std::lock_guard<std::mutex> guard(lock);
            pending.assign(bytes.begin(), bytes.end());
            pendingFrame = frame;
            pendingGeneration = generation;
            pendingDiagnose = diagnose;
            ready = true;
        }
        arrived.notify_one();
    }
private:
    void run() {
        std::vector<uint8_t> bytes;
        for (;;) {
            uint32_t frame;
            uint64_t generation;
            bool diagnose;
            {
                std::unique_lock<std::mutex> guard(lock);
                arrived.wait(guard, [this] { return ready || stopped; });
                if (stopped) return;
                bytes.swap(pending);
                frame = pendingFrame;
                generation = pendingGeneration;
                diagnose = pendingDiagnose;
                ready = false;
            }
            StateDiagnostic blocks;
            const auto hash = netplayStateHash(bytes, diagnose ? &blocks : nullptr);
            const auto count = std::min(blocks.size(), (bytes.size() + STATE_DIAGNOSTIC_BLOCK_SIZE - 1) / STATE_DIAGNOSTIC_BLOCK_SIZE);
            publish(generation, frame, hash, diagnose ? &blocks : nullptr, count);
        }
    }
    Publish publish;
    std::mutex lock;
    std::condition_variable arrived;
    std::vector<uint8_t> pending;
    uint32_t pendingFrame = 0;
    uint64_t pendingGeneration = 0;
    bool pendingDiagnose = false;
    bool ready = false;
    bool stopped = false;
    std::thread worker;
};
}
#endif
