#include "../src/videoobservation.h"
#include <cassert>
#include <thread>

using libretrodroid::VideoObservation;

static uint64_t number(const std::vector<uint8_t>& packet, size_t offset, size_t count) {
    uint64_t value = 0;
    for (size_t i = 0; i < count; i++) value |= static_cast<uint64_t>(packet[offset + i]) << (8 * i);
    return value;
}

int main() {
    VideoObservation observer;
    assert(!observer.request());
    assert(observer.takeRequest());
    assert(!observer.takeRequest());
    const uint16_t source[] {0x7c00, 0x03e0, 0xffff, 0x001f, 0x7fff, 0xffff};
    observer.publish(source, 2, 2, 6, VideoObservation::RGB1555, 4.f / 3, 1);
    const auto frame = observer.request();
    assert(frame && frame->size() == 56);
    assert(number(*frame, 4, 4) == 2 && number(*frame, 8, 4) == 2);
    assert(number(*frame, 12, 4) == 1);
    assert((*frame)[40] == 255 && (*frame)[41] == 0 && (*frame)[42] == 0);
    assert((*frame)[44] == 0 && (*frame)[45] == 255);
    assert((*frame)[48] == 0 && (*frame)[50] == 255);
    assert(source[0] == 0x7c00); // Never mutate the core's pixels, unlike renderer conversion.
    const uint64_t id = number(*frame, 16, 8);
    const uint32_t rgb[] {0x00ff0000, 0x000000ff};
    observer.publish(rgb, 1, 2, 4, VideoObservation::XRGB8888, 0, 0, true);
    assert((*observer.request())[42] == 255); // GPU bottom row becomes top row.
    assert((*frame)[40] == 255); // Previously delivered buffer is immutable.
    observer.clear();
    assert(!observer.request());
    observer.publish(rgb, 2, 1, 8, VideoObservation::XRGB8888, 2, 0);
    assert(number(*observer.request(), 16, 8) > id);
    const auto valid = observer.request();
    observer.publish(rgb, 2, 1, 1, VideoObservation::XRGB8888, 2, 0);
    assert(observer.request() == valid); // Invalid pitch cannot expose an out-of-bounds read.
    std::thread reader([&] { for (int i = 0; i < 1000; i++) assert(observer.request()); });
    for (int i = 0; i < 1000; i++) observer.publish(rgb, 2, 1, 8, VideoObservation::XRGB8888, 2, 0);
    reader.join();
}
