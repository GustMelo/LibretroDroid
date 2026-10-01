#ifndef LIBRETRODROID_REWIND_H
#define LIBRETRODROID_REWIND_H

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <deque>
#include <vector>

namespace libretrodroid {

/**
 * Rewind history of save states, newest last, kept within a byte budget.
 *
 * Only the newest state is stored whole ([current]). Every older one is the XOR of two neighbours, run-length
 * coded over 64-bit words: consecutive frames differ in a few KB of RAM, so a 400 KB GBA state usually costs a few
 * KB and even a 4.5 MB PlayStation state well under its size. Applying a delta to [current] steps one state back in
 * place, without a scratch copy. The oldest deltas are dropped first when the budget is exceeded.
 *
 * Not thread-safe: the engine pushes and pops on its emulation thread.
 */
class RewindBuffer {
public:
    void configure(size_t budgetBytes) {
        budget = budgetBytes;
        trim();
    }

    void clear() {
        for (auto& entry : entries) recycle(std::move(entry));
        entries.clear();
        current.clear();
        used = 0;
    }

    size_t size() const { return entries.size(); }
    size_t usedBytes() const { return used + current.size(); }
    bool empty() const { return entries.empty(); }

    /** The newest state: the one pushed last, or the one [pop] stepped back to. */
    const std::vector<uint8_t>& state() const { return current; }

    /** Records [data] as the newest state. A state of another size (core option, disc swap) restarts the history. */
    void push(const uint8_t* data, size_t length) {
        if (length == 0) return;
        if (current.size() != length) {
            clear();
            current.assign(data, data + length);
            return;
        }
        std::vector<uint8_t> delta = takeBuffer();
        encodeDelta(current.data(), data, length, delta);
        // A recycled buffer may be far larger than this delta: memory, not entries, is what the budget limits.
        if (delta.capacity() > 2 * delta.size() + 4096) delta.shrink_to_fit();
        used += delta.capacity();
        entries.push_back(std::move(delta));
        std::memcpy(current.data(), data, length);
        trim();
    }

    /** Steps [state] one entry back in time; false once the history is exhausted. */
    bool pop() {
        if (entries.empty()) return false;
        std::vector<uint8_t> delta = std::move(entries.back());
        entries.pop_back();
        used -= delta.capacity();
        apply(delta, current);
        recycle(std::move(delta));
        return true;
    }

private:
    std::vector<uint8_t> current;
    std::deque<std::vector<uint8_t>> entries;
    std::vector<std::vector<uint8_t>> pool;
    size_t used = 0;
    size_t budget = 64u << 20;

    void trim() {
        while (!entries.empty() && used + current.size() > budget) {
            used -= entries.front().capacity();
            recycle(std::move(entries.front()));
            entries.pop_front();
        }
    }

    std::vector<uint8_t> takeBuffer() {
        if (pool.empty()) return {};
        std::vector<uint8_t> buffer = std::move(pool.back());
        pool.pop_back();
        buffer.clear();
        return buffer;
    }

    void recycle(std::vector<uint8_t>&& buffer) {
        // A handful of buffers is enough to avoid allocating per frame; the rest goes back to the system.
        if (pool.size() < POOL) pool.push_back(std::move(buffer));
    }

    static constexpr size_t POOL = 8;

    static void putVarint(std::vector<uint8_t>& out, uint64_t value) {
        while (value >= 0x80) {
            out.push_back(static_cast<uint8_t>(value | 0x80));
            value >>= 7;
        }
        out.push_back(static_cast<uint8_t>(value));
    }

    static uint64_t getVarint(const uint8_t*& in) {
        uint64_t value = 0;
        int shift = 0;
        uint8_t byte;
        do {
            byte = *in++;
            value |= static_cast<uint64_t>(byte & 0x7f) << shift;
            shift += 7;
        } while (byte & 0x80);
        return value;
    }

public:
    /** Word-level XOR run-length code: (zero words, literal words, literal XOR words...)*, then the trailing bytes. */
    static void encodeDelta(const uint8_t* previous, const uint8_t* next, size_t length, std::vector<uint8_t>& out) {
        const size_t words = length / 8;
        size_t i = 0;
        while (i < words) {
            size_t zeros = 0;
            while (i + zeros < words && load(previous, i + zeros) == load(next, i + zeros)) zeros++;
            size_t start = i + zeros;
            size_t literals = 0;
            // A literal run ends at two equal words in a row: one equal word costs less inside the run than a new token.
            while (start + literals < words) {
                size_t k = start + literals;
                if (load(previous, k) == load(next, k) &&
                    (k + 1 >= words || load(previous, k + 1) == load(next, k + 1))) break;
                literals++;
            }
            putVarint(out, zeros);
            putVarint(out, literals);
            for (size_t k = start; k < start + literals; k++) {
                uint64_t x = load(previous, k) ^ load(next, k);
                const auto* bytes = reinterpret_cast<const uint8_t*>(&x);
                out.insert(out.end(), bytes, bytes + 8);
            }
            i = start + literals;
        }
        for (size_t k = words * 8; k < length; k++) out.push_back(previous[k] ^ next[k]);
    }

    /** XORs [delta] into [state]: turns either neighbour of the delta into the other. */
    static void apply(const std::vector<uint8_t>& delta, std::vector<uint8_t>& state) {
        const size_t words = state.size() / 8;
        const uint8_t* in = delta.data();
        const uint8_t* end = delta.data() + delta.size();
        const size_t tail = state.size() - words * 8;
        size_t i = 0;
        while (i < words && in < end - tail) {
            i += getVarint(in);
            size_t literals = getVarint(in);
            for (size_t k = 0; k < literals; k++, i++, in += 8) {
                uint64_t x;
                std::memcpy(&x, in, 8);
                uint64_t value = load(state.data(), i) ^ x;
                std::memcpy(state.data() + i * 8, &value, 8);
            }
        }
        for (size_t k = 0; k < tail; k++) state[words * 8 + k] ^= end[-static_cast<std::ptrdiff_t>(tail) + k];
    }

private:
    static uint64_t load(const uint8_t* data, size_t word) {
        uint64_t value;
        std::memcpy(&value, data + word * 8, 8);
        return value;
    }
};

}

#endif
