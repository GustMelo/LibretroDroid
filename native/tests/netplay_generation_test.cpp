#include "../src/netplay.h"
#include <cassert>

namespace libretrodroid {
void logPrint(int, const char*, const char*, ...) {}
}

int main() {
    auto& session = libretrodroid::Netplay::getInstance();
    session.start(0, 2, 1, 600, true);
    const auto old = session.generationId();
    session.stop();
    session.publishStateCheck(old, 600, 123, nullptr, 0);
    assert(session.drainOutbox().empty());
    session.start(0, 2, 1, 600, true);
    session.publishStateCheck(old, 600, 123, nullptr, 0);
    assert(session.drainOutbox().empty());
    uint64_t blocks[] = {44, 55};
    session.publishStateCheck(session.generationId(), 600, 123, blocks, 2);
    auto outgoing = session.drainOutbox();
    assert(outgoing.size() == 3);
    assert(outgoing[0].frame == 600 && outgoing[0].value == 123);
    assert(outgoing[1].type == 100 && outgoing[1].value == 44);
    assert(outgoing[2].type == 101 && outgoing[2].value == 55);
    session.stop();
}
