#ifndef LIBRETRODROID_VIDEOOBSERVATION_H
#define LIBRETRODROID_VIDEOOBSERVATION_H

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <vector>

namespace libretrodroid {

/** Owned, demand-driven observations. A consumer never waits for the core or owns its pixel pointer. */
class VideoObservation {
public:
    static VideoObservation& instance() { static VideoObservation store; return store; }

    enum Format { RGB1555 = 0, XRGB8888 = 1, RGB565 = 2, RGBA8888 = 3 };

    std::shared_ptr<const std::vector<uint8_t>> request() {
        requested.store(true);
        return std::atomic_load(&latest);
    }

    bool takeRequest() { return requested.exchange(false); }

    void clear() {
        std::atomic_store(&latest, std::shared_ptr<const std::vector<uint8_t>> {});
        // Demand survives reset/context recreation; the next visible frame replaces stale content.
    }

    void publish(const void* data, unsigned width, unsigned height, size_t pitch, Format format,
                 float aspect, unsigned rotation, bool bottomUp = false) {
        const size_t bytesPerPixel = format == RGB1555 || format == RGB565 ? 2 : 4;
        if (!data || !width || !height || width > std::numeric_limits<size_t>::max() / bytesPerPixel ||
            pitch < static_cast<size_t>(width) * bytesPerPixel ||
            height > std::numeric_limits<size_t>::max() / pitch ||
            static_cast<size_t>(width) > (std::numeric_limits<size_t>::max() - HEADER) / height / 4) return;
        auto packet = std::make_shared<std::vector<uint8_t>>(HEADER + static_cast<size_t>(width) * height * 4);
        auto* out = packet->data();
        std::memcpy(out, "GIPF", 4);
        write(out + 4, width, 4);
        write(out + 8, height, 4);
        write(out + 12, rotation % 4, 4);
        write(out + 16, ++sequence, 8);
        const auto micros = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        write(out + 24, micros, 8);
        if (!std::isfinite(aspect) || aspect <= 0) aspect = static_cast<float>(width) / height;
        uint32_t aspectBits;
        std::memcpy(&aspectBits, &aspect, sizeof(aspect));
        write(out + 32, aspectBits, 4);
        for (unsigned y = 0; y < height; y++) {
            const auto* row = static_cast<const uint8_t*>(data) + (bottomUp ? height - 1 - y : y) * pitch;
            for (unsigned x = 0; x < width; x++) {
                auto* pixel = out + HEADER + (static_cast<size_t>(y) * width + x) * 4;
                if (format == RGBA8888) {
                    std::memcpy(pixel, row + x * 4, 4);
                } else if (format == XRGB8888) {
                    uint32_t value;
                    std::memcpy(&value, row + x * 4, 4);
                    pixel[0] = value >> 16; pixel[1] = value >> 8; pixel[2] = value;
                } else {
                    uint16_t value;
                    std::memcpy(&value, row + x * 2, 2);
                    const unsigned red = (value >> (format == RGB565 ? 11 : 10)) & 31;
                    const unsigned green = (value >> 5) & (format == RGB565 ? 63 : 31);
                    const unsigned blue = value & 31;
                    pixel[0] = (red << 3) | (red >> 2);
                    pixel[1] = format == RGB565 ? (green << 2) | (green >> 4) : (green << 3) | (green >> 2);
                    pixel[2] = (blue << 3) | (blue >> 2);
                }
                pixel[3] = 255;
            }
        }
        std::atomic_store(&latest, std::shared_ptr<const std::vector<uint8_t>>(std::move(packet)));
    }

    static constexpr size_t HEADER = 40;

private:
    static void write(uint8_t* out, uint64_t value, size_t count) {
        for (size_t i = 0; i < count; i++) out[i] = value >> (8 * i);
    }
    std::atomic<bool> requested {false};
    std::shared_ptr<const std::vector<uint8_t>> latest;
    // IDs remain monotonic through context recreation and state changes.
    inline static std::atomic<uint64_t> sequence {0};
};
}
#endif
