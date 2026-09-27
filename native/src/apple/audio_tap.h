#ifndef LIBRETRODROID_AUDIO_TAP_H
#define LIBRETRODROID_AUDIO_TAP_H

#include <cstddef>
#include <cstdint>

namespace libretrodroid {
// Receives the audio exactly as it is played (48 kHz interleaved stereo), on the audio thread.
using AudioTap = void (*)(void *context, const int16_t *frames, size_t count);
void setAudioTap(AudioTap audio, void *context);
}

#endif
