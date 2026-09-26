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

#include "audio.h"

#include <algorithm>

#include "log.h"

namespace libretrodroid {
double AudioRateControl::update(double capacityFrames, double availableFrames, double dt) {
    double errorMeasure = (capacityFrames - 2.0f * availableFrames) / capacityFrames;

    errorIntegral += errorMeasure * dt;

    double proportionalAdjustment = std::clamp(kp * errorMeasure, -maxp, maxp);

    double integralAdjustment = std::clamp(ki * errorIntegral, -maxi, maxi);

    double finalAdjustment = proportionalAdjustment + integralAdjustment;

    LOGD("Audio speed adjustments (p: %f) (i: %f)", proportionalAdjustment, integralAdjustment);

    return 1.0 - (finalAdjustment);
}

int32_t audioBufferSize(int32_t inputSampleRate, double contentRefreshRate, unsigned bufferSizeInVideoFrames) {
    double maxLatency = std::max((bufferSizeInVideoFrames / contentRefreshRate) * 1000, 32.0);
    LOGI("Average audio latency set to: %f ms", maxLatency * 0.5);
    double sampleRateDivisor = 500.0 / maxLatency;
    int32_t size = inputSampleRate / sampleRateDivisor;
    return (size / 2) * 2;
}
}
