#include "../src/state_hash_worker.h"
#include <cassert>
#include <chrono>

int main() {
    std::mutex lock;
    std::condition_variable done;
    bool received = false;
    uint64_t result = 0;
    std::vector<uint8_t> state(0x440000, 42);
    const auto expected = libretrodroid::netplayStateHash(state);
    libretrodroid::StateHashWorker worker([&](uint64_t generation, uint32_t frame, uint64_t hash,
                                            const libretrodroid::StateDiagnostic* blocks, size_t count) {
        assert(generation == 17 && frame == 600 && blocks && count == 68);
        std::lock_guard<std::mutex> guard(lock);
        result = hash;
        received = true;
        done.notify_one();
    });
    worker.submit(state, 600, 17, true);
    std::fill(state.begin(), state.end(), 0);
    std::unique_lock<std::mutex> guard(lock);
    assert(done.wait_for(guard, std::chrono::seconds(5), [&] { return received; }));
    assert(result == expected);
}
