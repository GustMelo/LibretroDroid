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

#include <AudioToolbox/AudioToolbox.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <vector>

#include "audio.h"
#include "audio_tap.h"
#include "log.h"
#include "resamplers/linearresampler.h"

namespace libretrodroid {
namespace {
class SampleFifo {
public:
    explicit SampleFifo(size_t capacitySamples) : buffer(capacitySamples), capacity(capacitySamples) {}

    void write(const int16_t *data, size_t samples) {
        size_t w = writeIndex.load(std::memory_order_relaxed);
        size_t r = readIndex.load(std::memory_order_acquire);
        size_t room = capacity - (w - r);
        samples = std::min(samples, room);
        for (size_t i = 0; i < samples; i++) buffer[(w + i) % capacity] = data[i];
        writeIndex.store(w + samples, std::memory_order_release);
    }

    void readNow(int16_t *out, size_t samples) {
        size_t r = readIndex.load(std::memory_order_relaxed);
        size_t available = writeIndex.load(std::memory_order_acquire) - r;
        size_t count = std::min(samples, available);
        for (size_t i = 0; i < count; i++) out[i] = buffer[(r + i) % capacity];
        for (size_t i = count; i < samples; i++) out[i] = 0;
        readIndex.store(r + count, std::memory_order_release);
    }

    double capacityFrames() const { return capacity / 2.0; }

    double availableFrames() const {
        return (writeIndex.load(std::memory_order_acquire) - readIndex.load(std::memory_order_acquire)) / 2.0;
    }

private:
    std::vector<int16_t> buffer;
    size_t capacity;
    std::atomic<size_t> writeIndex {0};
    std::atomic<size_t> readIndex {0};
};

constexpr double OUTPUT_SAMPLE_RATE = 48000.0;

std::mutex tapLock;
AudioTap tap = nullptr;
void *tapContext = nullptr;
std::atomic<bool> tapOnly {false};
constexpr unsigned LOW_LATENCY_VIDEO_FRAMES = 4;
}

struct Audio::Impl {
    Impl(int32_t sampleRate, double refreshRate)
        : fifo(audioBufferSize(sampleRate, refreshRate, LOW_LATENCY_VIDEO_FRAMES)),
          temporary(audioBufferSize(sampleRate, refreshRate, LOW_LATENCY_VIDEO_FRAMES)),
          baseConversionFactor(sampleRate / OUTPUT_SAMPLE_RATE) {
        LOGI("Audio initialization has been called with input sample rate %d", sampleRate);

        AudioComponentDescription description {};
        description.componentType = kAudioUnitType_Output;
        description.componentSubType = kAudioUnitSubType_RemoteIO;
        description.componentManufacturer = kAudioUnitManufacturer_Apple;
        AudioComponent component = AudioComponentFindNext(nullptr, &description);
        if (!component || AudioComponentInstanceNew(component, &unit) != noErr) {
            LOGE("AudioUnit RemoteIO unavailable");
            unit = nullptr;
            return;
        }

        AudioStreamBasicDescription format {};
        format.mSampleRate = OUTPUT_SAMPLE_RATE;
        format.mFormatID = kAudioFormatLinearPCM;
        format.mFormatFlags = kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked;
        format.mChannelsPerFrame = 2;
        format.mBitsPerChannel = 16;
        format.mBytesPerFrame = 4;
        format.mFramesPerPacket = 1;
        format.mBytesPerPacket = 4;
        AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &format, sizeof(format));

        AURenderCallbackStruct callback { &Impl::render, this };
        AudioUnitSetProperty(unit, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0, &callback, sizeof(callback));

        if (AudioUnitInitialize(unit) != noErr) {
            LOGE("AudioUnitInitialize falhou");
            AudioComponentInstanceDispose(unit);
            unit = nullptr;
        }
    }

    ~Impl() {
        if (!unit) return;
        AudioOutputUnitStop(unit);
        AudioUnitUninitialize(unit);
        AudioComponentInstanceDispose(unit);
    }

    static OSStatus render(void *self, AudioUnitRenderActionFlags *, const AudioTimeStamp *, UInt32, UInt32 frames, AudioBufferList *io) {
        static_cast<Impl *>(self)->fill(static_cast<int16_t *>(io->mBuffers[0].mData), static_cast<int32_t>(frames));
        return noErr;
    }

    void fill(int16_t *output, int32_t numFrames) {
        double dynamicBufferFactor = rateControl.update(fifo.capacityFrames(), fifo.availableFrames(), 0.001 * numFrames);
        double finalConversionFactor = baseConversionFactor * dynamicBufferFactor * playbackSpeed;

        framesToSubmit += numFrames * finalConversionFactor;
        int32_t currentFramesToSubmit = std::round(framesToSubmit);
        framesToSubmit -= currentFramesToSubmit;
        currentFramesToSubmit = std::min<int32_t>(currentFramesToSubmit, temporary.size() / 2);

        fifo.readNow(temporary.data(), currentFramesToSubmit * 2);
        resampler.resample(temporary.data(), currentFramesToSubmit, output, numFrames);

        // Never wait on the render thread: a tap being replaced just skips one buffer.
        std::unique_lock<std::mutex> lock(tapLock, std::try_to_lock);
        if (lock.owns_lock() && tap) tap(tapContext, output, static_cast<size_t>(numFrames));
        if (tapOnly.load(std::memory_order_relaxed)) std::fill_n(output, static_cast<size_t>(numFrames) * 2, int16_t {0});
    }

    AudioComponentInstance unit = nullptr;
    SampleFifo fifo;
    std::vector<int16_t> temporary;
    LinearResampler resampler;
    AudioRateControl rateControl;
    double baseConversionFactor;
    double framesToSubmit = 0.0;
    std::atomic<double> playbackSpeed {1.0};
};

Audio::Audio(int32_t sampleRate, double refreshRate, bool)
    : impl(std::make_unique<Impl>(sampleRate, refreshRate)) {}

Audio::~Audio() = default;

void Audio::start() {
    if (impl->unit) AudioOutputUnitStart(impl->unit);
}

void Audio::stop() {
    if (impl->unit) AudioOutputUnitStop(impl->unit);
}

void Audio::write(const int16_t *data, size_t frames) {
    impl->fifo.write(data, frames * 2);
}

void Audio::setPlaybackSpeed(double newPlaybackSpeed) {
    impl->playbackSpeed = newPlaybackSpeed;
}

void setAudioTap(AudioTap audio, void *context) {
    std::lock_guard<std::mutex> lock(tapLock);
    tap = audio;
    tapContext = context;
}

void setAudioTapOnly(bool onlyTap) { tapOnly = onlyTap; }

int32_t audioTapSampleRate() { return static_cast<int32_t>(OUTPUT_SAMPLE_RATE); }
}
