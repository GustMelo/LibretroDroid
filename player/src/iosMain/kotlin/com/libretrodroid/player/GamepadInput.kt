package com.libretrodroid.player

import kotlinx.atomicfu.atomic
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.GameController.GCController
import platform.GameController.GCControllerDidConnectNotification
import platform.GameController.GCExtendedGamepad
import platform.darwin.NSObjectProtocol

internal class GamepadInput {
    private val mask = atomic(0)
    private var observer: NSObjectProtocol? = null

    val buttons: Int get() = mask.value

    fun start() {
        GCController.controllers().forEach { attach(it as GCController) }
        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            GCControllerDidConnectNotification, null, NSOperationQueue.mainQueue,
        ) { notification -> (notification?.`object` as? GCController)?.let(::attach) }
    }

    fun stop() {
        observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observer = null
        GCController.controllers().forEach { (it as GCController).extendedGamepad?.valueChangedHandler = null }
        mask.value = 0
    }

    private fun attach(controller: GCController) {
        controller.extendedGamepad?.valueChangedHandler = { gamepad, _ -> gamepad?.let { mask.value = it.retroPad() } }
    }

    private fun GCExtendedGamepad.retroPad(): Int {
        val stick = leftThumbstick
        var bits = 0
        fun set(id: Int, pressed: Boolean) { if (pressed) bits = bits or (1 shl id) }
        set(B, buttonA.pressed)
        set(A, buttonB.pressed)
        set(Y, buttonX.pressed)
        set(X, buttonY.pressed)
        set(L, leftShoulder.pressed)
        set(R, rightShoulder.pressed)
        set(L2, leftTrigger.pressed)
        set(R2, rightTrigger.pressed)
        set(START, buttonMenu.pressed)
        set(SELECT, buttonOptions?.pressed == true)
        set(UP, dpad.up.pressed || stick.yAxis.value > STICK)
        set(DOWN, dpad.down.pressed || stick.yAxis.value < -STICK)
        set(LEFT, dpad.left.pressed || stick.xAxis.value < -STICK)
        set(RIGHT, dpad.right.pressed || stick.xAxis.value > STICK)
        return bits
    }

    private companion object {
        const val STICK = 0.5f

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
