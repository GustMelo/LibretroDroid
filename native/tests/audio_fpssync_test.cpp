// Output volume and frame pacing: the settings a frontend changes while a game runs.
#include <cassert>
#include <cstdarg>
#include <cstdint>
#include <cstdio>

#include "../src/audio.h"
#include "../src/fpssync.h"

namespace libretrodroid {
void logPrint(int, const char*, const char*, ...) {}
void logVPrint(int, const char*, const char*, va_list) {}
}

using namespace libretrodroid;

static void volumeScalesSamplesAndLeavesFullVolumeAlone() {
    int16_t full[] = {1000, -1000, 32767, -32768};
    applyVolume(full, 4, 1.0f);
    assert(full[0] == 1000 && full[1] == -1000 && full[2] == 32767 && full[3] == -32768);

    int16_t half[] = {1000, -1000, 32767, -32768};
    applyVolume(half, 4, 0.5f);
    // Squared, so half the control is a quarter of the amplitude.
    assert(half[0] == 250 && half[1] == -250 && half[2] == 8191 && half[3] == -8192);

    int16_t silent[] = {1000, -1000};
    applyVolume(silent, 2, 0.0f);
    assert(silent[0] == 0 && silent[1] == 0);

    int16_t below[] = {1000};
    applyVolume(below, 1, -3.0f);
    assert(below[0] == 0);
}

static void vsyncIsUsedOnlyWhenAllowedAndTheRatesMatch() {
    FPSSync matching(59.73, 60.0);
    assert(matching.advanceFrames() == 1);
    assert(matching.getTimeStretchFactor() != 1.0);

    matching.setVSyncAllowed(false);
    assert(matching.getTimeStretchFactor() == 1.0);
    assert(matching.advanceFrames() >= 1);

    matching.setVSyncAllowed(true);
    assert(matching.getTimeStretchFactor() != 1.0);

    // A 90 Hz screen never paces a 60 Hz console by its refresh, whatever the setting.
    FPSSync other(60.0, 90.0);
    other.setVSyncAllowed(true);
    assert(other.getTimeStretchFactor() == 1.0);
}

int main() {
    volumeScalesSamplesAndLeavesFullVolumeAlone();
    vsyncIsUsedOnlyWhenAllowedAndTheRatesMatch();
    std::printf("audio volume and frame pacing: ok\n");
    return 0;
}
