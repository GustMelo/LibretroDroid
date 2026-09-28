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

#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>

#include <oboe/Oboe.h>
#include <oboe/FifoBuffer.h>

#include "audio.h"
#include "audio_tap.h"
#include "log.h"
#include "resamplers/linearresampler.h"

namespace libretrodroid {
namespace {
std::mutex tapLock;
AudioTap tap = nullptr;
void *tapContext = nullptr;
// The device's output rate: Oboe plays at its native rate and the resampler above converts to it.
std::atomic<int32_t> tapSampleRate {48000};
}

struct Audio::Impl : public oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback {
    struct AudioLatencySettings {
        unsigned bufferSizeInVideoFrames;
        bool useLowLatencyStream;
    };

    const AudioLatencySettings DEFAULT_LATENCY_SETTINGS { 8, false };
    const AudioLatencySettings LOW_LATENCY_SETTINGS { 4, true };

    Impl(int32_t sampleRate, double refreshRate, bool preferLowLatencyAudio) {
        LOGI("Audio initialization has been called with input sample rate %d", sampleRate);

        contentRefreshRate = refreshRate;
        inputSampleRate = sampleRate;
        audioLatencySettings = findBestLatencySettings(preferLowLatencyAudio);
        initializeStream();
    }

    bool initializeStream() {
        LOGI("Using low latency stream: %d", audioLatencySettings.useLowLatencyStream);

        int32_t bufferSize = audioBufferSize(inputSampleRate, contentRefreshRate, audioLatencySettings.bufferSizeInVideoFrames);

        oboe::AudioStreamBuilder builder;
        builder.setChannelCount(2);
        builder.setDirection(oboe::Direction::Output);
        builder.setFormat(oboe::AudioFormat::I16);
        builder.setDataCallback(this);
        builder.setErrorCallback(this);

        if (audioLatencySettings.useLowLatencyStream) {
            builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        } else {
            builder.setFramesPerCallback(bufferSize / 10);
        }

        oboe::Result result = builder.openManagedStream(stream);
        if (result == oboe::Result::OK) {
            baseConversionFactor = (double) inputSampleRate / stream->getSampleRate();
            tapSampleRate = stream->getSampleRate();
            fifoBuffer = std::make_unique<oboe::FifoBuffer>(2, bufferSize);
            temporaryAudioBuffer = std::unique_ptr<int16_t[]>(new int16_t[bufferSize]);
            latencyTuner = std::make_unique<oboe::LatencyTuner>(*stream);
            return true;
        } else {
            LOGE("Failed to create stream. Error: %s", oboe::convertToText(result));
            stream = nullptr;
            latencyTuner = nullptr;
            return false;
        }
    }

    AudioLatencySettings findBestLatencySettings(bool preferLowLatencyAudio) {
        if (oboe::AudioStreamBuilder::isAAudioRecommended() && preferLowLatencyAudio) {
            return LOW_LATENCY_SETTINGS;
        } else {
            return DEFAULT_LATENCY_SETTINGS;
        }
    }

    void start() {
        startRequested = true;
        if (stream != nullptr)
            stream->requestStart();
    }

    void stop() {
        startRequested = false;
        if (stream != nullptr)
            stream->requestStop();
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        double dynamicBufferFactor = rateControl.update(
            fifoBuffer->getBufferCapacityInFrames(), fifoBuffer->getFullFramesAvailable(), 0.001 * numFrames);
        double finalConversionFactor = baseConversionFactor * dynamicBufferFactor * playbackSpeed;

        framesToSubmit += numFrames * finalConversionFactor;
        int32_t currentFramesToSubmit = std::round(framesToSubmit);
        framesToSubmit -= currentFramesToSubmit;

        fifoBuffer->readNow(temporaryAudioBuffer.get(), currentFramesToSubmit * 2);

        auto outputArray = reinterpret_cast<int16_t *>(audioData);
        resampler.resample(temporaryAudioBuffer.get(), currentFramesToSubmit, outputArray, numFrames);

        latencyTuner->tune();

        // Never wait on the audio thread: a tap being replaced just skips one buffer.
        std::unique_lock<std::mutex> lock(tapLock, std::try_to_lock);
        if (lock.owns_lock() && tap) tap(tapContext, outputArray, static_cast<size_t>(numFrames));

        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream* oldStream, oboe::Result result) override {
        AudioStreamErrorCallback::onErrorAfterClose(oldStream, result);
        LOGI("Stream error in oboe::onErrorAfterClose %s", oboe::convertToText(result));

        if (result != oboe::Result::ErrorDisconnected)
            return;

        initializeStream();
        if (startRequested) {
            start();
        }
    }

    LinearResampler resampler;
    AudioRateControl rateControl;
    std::unique_ptr<oboe::FifoBuffer> fifoBuffer = nullptr;
    std::unique_ptr<int16_t[]> temporaryAudioBuffer = nullptr;

    oboe::ManagedStream stream = nullptr;
    std::unique_ptr<oboe::LatencyTuner> latencyTuner = nullptr;

    bool startRequested = false;
    int32_t inputSampleRate;
    double contentRefreshRate = 60.0;

    double baseConversionFactor = 1.0;
    double framesToSubmit = 0.0;
    double playbackSpeed = 1.0;

    AudioLatencySettings audioLatencySettings {};
};

Audio::Audio(int32_t sampleRate, double refreshRate, bool preferLowLatencyAudio)
    : impl(std::make_unique<Impl>(sampleRate, refreshRate, preferLowLatencyAudio)) {}

Audio::~Audio() = default;

void Audio::start() { impl->start(); }

void Audio::stop() { impl->stop(); }

void Audio::write(const int16_t *data, size_t frames) {
    impl->fifoBuffer->write(data, frames * 2);
}

void Audio::setPlaybackSpeed(const double newPlaybackSpeed) {
    impl->playbackSpeed = newPlaybackSpeed;
}

void setAudioTap(AudioTap audio, void *context) {
    std::lock_guard<std::mutex> lock(tapLock);
    tap = audio;
    tapContext = context;
}

int32_t audioTapSampleRate() { return tapSampleRate; }
}
