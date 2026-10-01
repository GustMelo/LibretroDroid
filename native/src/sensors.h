#ifndef LIBRETRODROID_SENSORS_H
#define LIBRETRODROID_SENSORS_H

#include <atomic>
#include <cstdint>

#include "../../libretro-common/include/libretro.h"

namespace libretrodroid {

/**
 * Libretro sensor interface backed by the phone's sensors. The core turns each sensor on and off
 * (mGBA: tilt and gyro cartridges, the Boktai solar sensor, MBC7); the frontend reads [requested] to keep only those
 * hardware sensors running and pushes their latest readings with [set]. Units follow libretro: m/s², rad/s, lux.
 * Readings are written by the sensor thread and read by the emulation thread without locks.
 */
class Sensors {
public:
    enum Kind : uint32_t { ACCELEROMETER = 1, GYROSCOPE = 2, ILLUMINANCE = 4 };

    static Sensors& getInstance() {
        static Sensors instance;
        return instance;
    }

    static bool callbackSetState(unsigned port, enum retro_sensor_action action, unsigned rate) {
        return getInstance().setState(port, action);
    }

    static float callbackGetInput(unsigned port, unsigned id) {
        return getInstance().input(port, id);
    }

    /** The sensors the core has switched on, as a [Kind] mask. */
    uint32_t requested() const { return enabled.load(std::memory_order_relaxed); }

    /** Latest reading of [id] (RETRO_SENSOR_*). */
    void set(unsigned id, float value) {
        if (id < COUNT) values[id].store(value, std::memory_order_relaxed);
    }

    void reset() {
        enabled = 0;
        for (auto& value : values) value.store(0.0f, std::memory_order_relaxed);
    }

private:
    static constexpr unsigned COUNT = RETRO_SENSOR_ILLUMINANCE + 1;
    std::atomic<uint32_t> enabled {0};
    std::atomic<float> values[COUNT] {};

    bool setState(unsigned port, enum retro_sensor_action action) {
        if (port != 0) return false;
        switch (action) {
            case RETRO_SENSOR_ACCELEROMETER_ENABLE: enabled |= ACCELEROMETER; return true;
            case RETRO_SENSOR_ACCELEROMETER_DISABLE: enabled &= ~ACCELEROMETER; return true;
            case RETRO_SENSOR_GYROSCOPE_ENABLE: enabled |= GYROSCOPE; return true;
            case RETRO_SENSOR_GYROSCOPE_DISABLE: enabled &= ~GYROSCOPE; return true;
            case RETRO_SENSOR_ILLUMINANCE_ENABLE: enabled |= ILLUMINANCE; return true;
            case RETRO_SENSOR_ILLUMINANCE_DISABLE: enabled &= ~ILLUMINANCE; return true;
            default: return false;
        }
    }

    float input(unsigned port, unsigned id) const {
        if (port != 0 || id >= COUNT) return 0.0f;
        return values[id].load(std::memory_order_relaxed);
    }
};

}

#endif
