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

#include <android/input.h>
#include <android/keycodes.h>

#include "input.h"
#include "libretro.h"

namespace libretrodroid {
int Input::convertAndroidToLibretroKey(int keyCode) const {
    switch (keyCode) {
        case AKEYCODE_BUTTON_START:
            return RETRO_DEVICE_ID_JOYPAD_START;
        case AKEYCODE_BUTTON_SELECT:
            return RETRO_DEVICE_ID_JOYPAD_SELECT;
        case AKEYCODE_BUTTON_A:
            return RETRO_DEVICE_ID_JOYPAD_A;
        case AKEYCODE_BUTTON_X:
            return RETRO_DEVICE_ID_JOYPAD_X;
        case AKEYCODE_BUTTON_Y:
            return RETRO_DEVICE_ID_JOYPAD_Y;
        case AKEYCODE_BUTTON_B:
            return RETRO_DEVICE_ID_JOYPAD_B;
        case AKEYCODE_BUTTON_L1:
            return RETRO_DEVICE_ID_JOYPAD_L;
        case AKEYCODE_BUTTON_L2:
            return RETRO_DEVICE_ID_JOYPAD_L2;
        case AKEYCODE_BUTTON_R1:
            return RETRO_DEVICE_ID_JOYPAD_R;
        case AKEYCODE_BUTTON_R2:
            return RETRO_DEVICE_ID_JOYPAD_R2;
        case AKEYCODE_BUTTON_THUMBL:
            return RETRO_DEVICE_ID_JOYPAD_L3;
        case AKEYCODE_BUTTON_THUMBR:
            return RETRO_DEVICE_ID_JOYPAD_R3;
        case AKEYCODE_DPAD_UP:
            return RETRO_DEVICE_ID_JOYPAD_UP;
        case AKEYCODE_DPAD_DOWN:
            return RETRO_DEVICE_ID_JOYPAD_DOWN;
        case AKEYCODE_DPAD_LEFT:
            return RETRO_DEVICE_ID_JOYPAD_LEFT;
        case AKEYCODE_DPAD_RIGHT:
            return RETRO_DEVICE_ID_JOYPAD_RIGHT;
        case AKEYCODE_DPAD_UP_RIGHT:
            return Input::RETRO_DEVICE_ID_JOYPAD_UP_RIGHT;
        case AKEYCODE_DPAD_UP_LEFT:
            return Input::RETRO_DEVICE_ID_JOYPAD_UP_LEFT;
        case AKEYCODE_DPAD_DOWN_RIGHT:
            return Input::RETRO_DEVICE_ID_JOYPAD_DOWN_RIGHT;
        case AKEYCODE_DPAD_DOWN_LEFT:
            return Input::RETRO_DEVICE_ID_JOYPAD_DOWN_LEFT;
        default:
            return UNKNOWN_KEY;
    }
}

void Input::onKeyEvent(unsigned int port, int action, int keyCode) {
    int retroKeyCode = convertAndroidToLibretroKey(keyCode);
    if (retroKeyCode == UNKNOWN_KEY) {
        return;
    }

    if (action == AKEY_EVENT_ACTION_DOWN) {
        onButton(port, retroKeyCode, true);
    } else if (action == AKEY_EVENT_ACTION_UP) {
        onButton(port, retroKeyCode, false);
    }
}
}
