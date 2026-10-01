package com.libretrodroid.player

import kotlinx.atomicfu.atomic
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.GameController.GCController
import platform.GameController.GCControllerDidConnectNotification
import platform.GameController.GCExtendedGamepad
import platform.GameController.GCKeyboard
import platform.GameController.GCKeyboardDidConnectNotification
import platform.darwin.NSObjectProtocol

/**
 * Turns physical input into RetroPad buttons for the player. Gamepads report which of their buttons are held as a
 * physical mask (bit order of [RetroInputMapper]); keyboards report keys by USB HID usage. Without a mapper the
 * classic layout applies: the bottom face button is the console's B.
 */
interface RetroInputMapper {
    /** Console buttons for gamepad [player] holding [physical]: A, B, X, Y, L1, R1, L2, R2, L3, R3, Start, Select, Home, up, down, left, right. */
    fun gamepad(player: Int, physical: Int): Int

    /** A keyboard key went down or up; returns the console buttons the keyboard holds now. */
    fun keyboard(hidUsage: Int, pressed: Boolean): Int
}

internal class GamepadInput {
    private val mask = atomic(0)
    private val keyboardMask = atomic(0)
    /** Each controller's buttons, in connection order, for players sharing the device. */
    private val masks = List(MAX_PLAYERS) { atomic(0) }
    private val observers = mutableListOf<NSObjectProtocol>()

    @kotlin.concurrent.Volatile var mapper: RetroInputMapper? = null

    val buttons: Int get() = mask.value or keyboardMask.value

    fun buttons(player: Int): Int = (masks.getOrNull(player)?.value ?: 0) or if (player == 0) keyboardMask.value else 0

    fun start() {
        GCController.controllers().forEach { attach(it as GCController) }
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(GCControllerDidConnectNotification, null, NSOperationQueue.mainQueue) { notification ->
            (notification?.`object` as? GCController)?.let(::attach)
        }
        observers += center.addObserverForName(GCKeyboardDidConnectNotification, null, NSOperationQueue.mainQueue) { _ -> attachKeyboard() }
        attachKeyboard()
    }

    fun stop() {
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers.clear()
        GCController.controllers().forEach { (it as GCController).extendedGamepad?.valueChangedHandler = null }
        GCKeyboard.coalescedKeyboard?.keyboardInput?.keyChangedHandler = null
        mask.value = 0
        keyboardMask.value = 0
        masks.forEach { it.value = 0 }
    }

    private fun attach(controller: GCController) {
        controller.extendedGamepad?.valueChangedHandler = { gamepad, _ ->
            gamepad?.let { pad ->
                val index = GCController.controllers().indexOf(controller)
                val physical = pad.physical()
                val bits = mapper?.gamepad(index.coerceAtLeast(0), physical) ?: classic(physical)
                mask.value = bits
                if (index in masks.indices) masks[index].value = bits
            }
        }
    }

    private fun attachKeyboard() {
        GCKeyboard.coalescedKeyboard?.keyboardInput?.keyChangedHandler = { _, _, keyCode, pressed ->
            mapper?.let { keyboardMask.value = it.keyboard(keyCode.toInt(), pressed) }
        }
    }

    private fun GCExtendedGamepad.physical(): Int {
        val stick = leftThumbstick
        var bits = 0
        fun set(bit: Int, pressed: Boolean) { if (pressed) bits = bits or (1 shl bit) }
        set(P_A, buttonA.pressed)
        set(P_B, buttonB.pressed)
        set(P_X, buttonX.pressed)
        set(P_Y, buttonY.pressed)
        set(P_L1, leftShoulder.pressed)
        set(P_R1, rightShoulder.pressed)
        set(P_L2, leftTrigger.pressed)
        set(P_R2, rightTrigger.pressed)
        set(P_L3, leftThumbstickButton?.pressed == true)
        set(P_R3, rightThumbstickButton?.pressed == true)
        set(P_START, buttonMenu.pressed)
        set(P_SELECT, buttonOptions?.pressed == true)
        set(P_HOME, buttonHome?.pressed == true)
        set(P_UP, dpad.up.pressed || stick.yAxis.value > STICK)
        set(P_DOWN, dpad.down.pressed || stick.yAxis.value < -STICK)
        set(P_LEFT, dpad.left.pressed || stick.xAxis.value < -STICK)
        set(P_RIGHT, dpad.right.pressed || stick.xAxis.value > STICK)
        return bits
    }

    /** The layout before bindings existed: bottom button B, right A, left Y, top X. */
    private fun classic(physical: Int): Int {
        var bits = 0
        fun map(from: Int, to: Int) { if (physical and (1 shl from) != 0) bits = bits or (1 shl to) }
        map(P_A, B); map(P_B, A); map(P_X, Y); map(P_Y, X)
        map(P_L1, L); map(P_R1, R); map(P_L2, L2); map(P_R2, R2)
        map(P_START, START); map(P_SELECT, SELECT)
        map(P_UP, UP); map(P_DOWN, DOWN); map(P_LEFT, LEFT); map(P_RIGHT, RIGHT)
        return bits
    }

    private companion object {
        const val MAX_PLAYERS = 4
        const val STICK = 0.5f

        const val P_A = 0
        const val P_B = 1
        const val P_X = 2
        const val P_Y = 3
        const val P_L1 = 4
        const val P_R1 = 5
        const val P_L2 = 6
        const val P_R2 = 7
        const val P_L3 = 8
        const val P_R3 = 9
        const val P_START = 10
        const val P_SELECT = 11
        const val P_HOME = 12
        const val P_UP = 13
        const val P_DOWN = 14
        const val P_LEFT = 15
        const val P_RIGHT = 16

        const val B = 0
        const val Y = 1
        const val SELECT = 2
        const val START = 3
        const val UP = 4
        const val DOWN = 5
        const val LEFT = 6
        const val RIGHT = 7
        const val A = 8
        const val X = 9
        const val L = 10
        const val R = 11
        const val L2 = 12
        const val R2 = 13
    }
}
