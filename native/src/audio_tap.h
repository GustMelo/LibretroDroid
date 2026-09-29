#ifndef LIBRETRODROID_AUDIO_TAP_H
#define LIBRETRODROID_AUDIO_TAP_H

#include <cstddef>
#include <cstdint>

namespace libretrodroid {
// Receives the audio exactly as it is played (interleaved 16-bit stereo at audioTapSampleRate()), on the
// audio thread. Implemented by each platform's audio backend.
using AudioTap = void (*)(void *context, const int16_t *frames, size_t count);
void setAudioTap(AudioTap audio, void *context);
// Streaming to another screen: once the tap has the audio, this device plays silence instead. The sound is heard
// only where the picture is. Off by default; the tap receives the same audio either way.
void setAudioTapOnly(bool tapOnly);
int32_t audioTapSampleRate();
}

#endif
