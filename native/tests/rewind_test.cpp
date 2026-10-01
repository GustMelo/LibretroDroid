#include "../src/rewind.h"
#include <cassert>
#include <chrono>
#include <cstdio>
#include <random>

using libretrodroid::RewindBuffer;

static std::vector<uint8_t> mutate(std::vector<uint8_t> state, std::mt19937& random, int changes) {
    for (int i = 0; i < changes; i++) state[random() % state.size()] = static_cast<uint8_t>(random());
    return state;
}

int main() {
    std::mt19937 random(7);
    for (size_t size : {size_t(0x61), size_t(400 * 1024 + 3), size_t(4500 * 1024)}) {
        RewindBuffer buffer;
        buffer.configure(256u << 20);
        std::vector<std::vector<uint8_t>> history;
        std::vector<uint8_t> state(size);
        for (auto& b : state) b = static_cast<uint8_t>(random());
        for (int frame = 0; frame < 60; frame++) {
            state = mutate(state, random, frame % 7 == 0 ? 5000 : 40);
            history.push_back(state);
            buffer.push(state.data(), state.size());
        }
        assert(buffer.state() == history.back());
        for (int back = 58; back >= 0; back--) {
            assert(buffer.pop());
            assert(buffer.state() == history[back]);
        }
        assert(!buffer.pop());
        // Pushing again after a rewind continues from the restored state.
        state = mutate(buffer.state(), random, 10);
        buffer.push(state.data(), state.size());
        assert(buffer.pop() && buffer.state() == history[0]);
    }

    // The budget drops the oldest entries but keeps the newest reachable.
    RewindBuffer small;
    small.configure(64 * 1024);
    std::vector<uint8_t> state(32 * 1024, 0);
    std::vector<std::vector<uint8_t>> history;
    for (int frame = 0; frame < 200; frame++) {
        state = mutate(state, random, 200);
        history.push_back(state);
        small.push(state.data(), state.size());
        assert(small.usedBytes() <= 64 * 1024 + 32 * 1024);
    }
    size_t kept = small.size();
    assert(kept > 0 && kept < 199);
    for (size_t i = 0; i < kept; i++) {
        assert(small.pop());
        assert(small.state() == history[history.size() - 2 - i]);
    }

    // A state of another size restarts the history.
    std::vector<uint8_t> other(100, 1);
    small.push(other.data(), other.size());
    assert(small.empty() && small.state() == other);

    // Cost of a PlayStation-sized state with a typical per-frame change.
    RewindBuffer timing;
    std::vector<uint8_t> big(4500 * 1024);
    for (auto& b : big) b = static_cast<uint8_t>(random());
    timing.push(big.data(), big.size());
    auto start = std::chrono::steady_clock::now();
    for (int i = 0; i < 100; i++) {
        big = mutate(big, random, 2000);
        timing.push(big.data(), big.size());
    }
    auto us = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count();
    std::printf("rewind: %zu entries, %zu KB, %.2f ms per 4.5 MB push\n", timing.size(), timing.usedBytes() / 1024, us / 100000.0);
    return 0;
}
