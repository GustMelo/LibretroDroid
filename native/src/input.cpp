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

#include "input.h"

#include <cmath>

#include "../../libretro-common/include/libretro.h"
#include "netplay.h"

namespace libretrodroid {
int16_t Input::getInputState(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port >= 4) return 0;

    Netplay& netplay = Netplay::getInstance();
    if (netplay.isActive()) {
        if (device != RETRO_DEVICE_JOYPAD) return 0;
        uint16_t mask = netplay.buttons(port);
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<int16_t>(mask);
        return id < 16 ? static_cast<int16_t>((mask >> id) & 1) : 0;
    }
    return getLocalInputState(port, device, index, id);
}

uint16_t Input::joypadMask(unsigned port) const {
    uint16_t mask = 0;
    for (unsigned id = 0; id < 16; id++) {
        if (getLocalInputState(port, RETRO_DEVICE_JOYPAD, 0, id)) mask |= (1u << id);
    }
    return mask;
}

int16_t Input::getLocalInputState(unsigned port, unsigned device, unsigned index, unsigned id) const {
    if (port >= 4) return 0;

    switch (device) {
        case RETRO_DEVICE_JOYPAD: {
            switch (id) {
                case RETRO_DEVICE_ID_JOYPAD_LEFT: {
                    bool axis = pads[port].dpadXAxis == -1;
                    bool buttons = anyPressed(
                        port,
                        RETRO_DEVICE_ID_JOYPAD_LEFT,
                        Input::RETRO_DEVICE_ID_JOYPAD_DOWN_LEFT,
                        Input::RETRO_DEVICE_ID_JOYPAD_UP_LEFT
                    );
                    return axis || buttons;
                }
                case RETRO_DEVICE_ID_JOYPAD_RIGHT: {
                    bool axis = pads[port].dpadXAxis == 1;
                    bool buttons = anyPressed(
                        port,
                        RETRO_DEVICE_ID_JOYPAD_RIGHT,
                        Input::RETRO_DEVICE_ID_JOYPAD_UP_RIGHT,
                        Input::RETRO_DEVICE_ID_JOYPAD_DOWN_RIGHT
                    );
                    return axis || buttons;
                }
                case RETRO_DEVICE_ID_JOYPAD_UP: {
                    bool axis = pads[port].dpadYAxis == -1;
                    bool buttons = anyPressed(
                        port,
                        RETRO_DEVICE_ID_JOYPAD_UP,
                        Input::RETRO_DEVICE_ID_JOYPAD_UP_LEFT,
                        Input::RETRO_DEVICE_ID_JOYPAD_UP_RIGHT
                    );
                    return axis || buttons;
                }
                case RETRO_DEVICE_ID_JOYPAD_DOWN: {
                    bool axis = pads[port].dpadYAxis == 1;
                    bool buttons = anyPressed(
                        port,
                        RETRO_DEVICE_ID_JOYPAD_DOWN,
                        Input::RETRO_DEVICE_ID_JOYPAD_DOWN_LEFT,
                        Input::RETRO_DEVICE_ID_JOYPAD_DOWN_RIGHT
                    );
                    return axis || buttons;
                }
                default:
                    return anyPressed(port, id);
            }
        }

        case RETRO_DEVICE_ANALOG: {
            switch (index) {
                case RETRO_DEVICE_INDEX_ANALOG_LEFT:
                    switch (id) {
                        case RETRO_DEVICE_ID_ANALOG_X:
                            return (int16_t) (pads[port].joypadLeftXAxis * MAX_RANGE_MOTION);
                        case RETRO_DEVICE_ID_ANALOG_Y:
                            return (int16_t) (pads[port].joypadLeftYAxis * MAX_RANGE_MOTION);
                        default:
                            return 0;
                    }
                case RETRO_DEVICE_INDEX_ANALOG_RIGHT:
                    switch (id) {
                        case RETRO_DEVICE_ID_ANALOG_X:
                            return (int16_t) (pads[port].joypadRightXAxis * MAX_RANGE_MOTION);
                        case RETRO_DEVICE_ID_ANALOG_Y:
                            return (int16_t) (pads[port].joypadRightYAxis * MAX_RANGE_MOTION);
                        default:
                            return 0;
                    }
                default:
                    return 0;
            }
        }

        case RETRO_DEVICE_POINTER: {
            if (index > 0) {
                return 0;
            }

            switch (id) {
                case RETRO_DEVICE_ID_POINTER_PRESSED: {
                    bool isXActive = pads[port].pointerScreenXAxis >= 0;
                    bool isYActive = pads[port].pointerScreenYAxis >= 0;
                    return (int16_t) (isXActive && isYActive ? 1 : 0);
                }

                case RETRO_DEVICE_ID_POINTER_X:
                    return (int16_t) (2.0 * (pads[port].pointerScreenXAxis - 0.5f) * MAX_RANGE_MOTION);

                case RETRO_DEVICE_ID_POINTER_Y:
                    return (int16_t) (2.0 * (pads[port].pointerScreenYAxis - 0.5f) * MAX_RANGE_MOTION);

                default:
                    return 0;
            }
        }

        default:
            return 0;
    }
}

void Input::onButton(unsigned int port, int retroId, bool pressed) {
    if (port >= 4) return;
    if (pressed) {
        pads[port].pressedKeys.insert(retroId);
    } else {
        pads[port].pressedKeys.erase(retroId);
    }
}

void Input::onMotionEvent(int port, int motionSource, float xAxis, float yAxis) {
    switch (motionSource) {
        case Input::MOTION_SOURCE_DPAD:
            pads[port].dpadXAxis = (int) round(xAxis);
            pads[port].dpadYAxis = (int) round(yAxis);
            break;

        case Input::MOTION_SOURCE_ANALOG_LEFT:
            pads[port].joypadLeftXAxis = xAxis;
            pads[port].joypadLeftYAxis = yAxis;
            break;

        case Input::MOTION_SOURCE_ANALOG_RIGHT:
            pads[port].joypadRightXAxis = xAxis;
            pads[port].joypadRightYAxis = yAxis;
            break;

        case Input::MOTION_SOURCE_POINTER:
            pads[port].pointerScreenXAxis = xAxis;
            pads[port].pointerScreenYAxis = yAxis;
            break;
    }
}

template<typename... T>
bool Input::anyPressed(unsigned int port, unsigned int id, T &... args) const {
    return anyPressed(port, id) || anyPressed(port, args...);
}

bool Input::anyPressed(unsigned int port, unsigned int id) const {
    return pads[port].pressedKeys.count(id) > 0;
}
}
