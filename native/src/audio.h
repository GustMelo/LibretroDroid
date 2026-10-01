/*
 *     Copyright (C) 2019  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#ifndef LIBRETRODROID_AUDIO_H
#define LIBRETRODROID_AUDIO_H

#include <cstddef>
#include <cstdint>
#include <memory>

namespace libretrodroid {
class Audio {
public:
    Audio(int32_t sampleRate, double refreshRate, bool preferLowLatencyAudio);
    ~Audio();

    void start();
    void stop();

    void write(const int16_t *data, size_t frames);
    void setPlaybackSpeed(double newPlaybackSpeed);
    /** How loud the game plays on this device, 0 (silent) to 1; what an audio tap receives keeps its full volume. */
    void setVolume(float newVolume);

    struct Impl;

private:
    std::unique_ptr<Impl> impl;
};

class AudioRateControl {
public:
    double update(double capacityFrames, double availableFrames, double dt);

private:
    const double kp = 0.006;
    const double ki = 0.00002;
    const double maxp = 0.003;
    const double maxi = 0.02;

    double errorIntegral = 0.0;
};

/** Scales [samples] interleaved samples in place by a volume of 0..1, squared so the control feels even; 1 leaves them alone. */
void applyVolume(int16_t *data, size_t samples, float volume);

int32_t audioBufferSize(int32_t inputSampleRate, double contentRefreshRate, unsigned bufferSizeInVideoFrames);
}

#endif
