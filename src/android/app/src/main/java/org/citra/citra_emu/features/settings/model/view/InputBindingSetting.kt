// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.settings.model.view

import android.content.Context
import android.view.InputDevice
import android.view.InputDevice.MotionRange
import android.view.KeyEvent
import android.widget.Toast
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.features.hotkeys.Hotkey
import org.citra.citra_emu.features.settings.model.AbstractSetting
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.utils.AndroidControlsIniHandler

class InputBindingSetting(
    val abstractSetting: AbstractSetting,
    titleId: Int
) : SettingsItem(abstractSetting, titleId, 0) {
    private val context: Context get() = CitraApplication.appContext

    val value: String
        get() = AndroidControlsIniHandler.getDisplayString(abstractSetting.key ?: "")

    fun isCirclePad(): Boolean =
        when (abstractSetting.key) {
            Settings.KEY_CIRCLEPAD_AXIS_HORIZONTAL,
            Settings.KEY_CIRCLEPAD_AXIS_VERTICAL -> true
            else -> false
        }

    fun isHorizontalOrientation(): Boolean =
        when (abstractSetting.key) {
            Settings.KEY_CIRCLEPAD_AXIS_HORIZONTAL,
            Settings.KEY_CSTICK_AXIS_HORIZONTAL,
            Settings.KEY_DPAD_AXIS_HORIZONTAL -> true
            else -> false
        }

    fun isCStick(): Boolean =
        when (abstractSetting.key) {
            Settings.KEY_CSTICK_AXIS_HORIZONTAL,
            Settings.KEY_CSTICK_AXIS_VERTICAL -> true
            else -> false
        }

    fun isDPad(): Boolean =
        when (abstractSetting.key) {
            Settings.KEY_DPAD_AXIS_HORIZONTAL,
            Settings.KEY_DPAD_AXIS_VERTICAL -> true
            else -> false
        }

    fun isTrigger(): Boolean =
        when (abstractSetting.key) {
            Settings.KEY_BUTTON_L,
            Settings.KEY_BUTTON_R,
            Settings.KEY_BUTTON_ZL,
            Settings.KEY_BUTTON_ZR -> true
            else -> false
        }

    fun isAxisMappingSupported(): Boolean {
        return isCirclePad() || isCStick() || isDPad() || isTrigger()
    }

    fun isButtonMappingSupported(): Boolean {
        return !isAxisMappingSupported() || isTrigger()
    }

    private val buttonCode: Int
        get() = abstractSetting.key?.let { settingKeyToButtonCode(it) } ?: -1

    fun removeOldMapping() {
        val settingKey = abstractSetting.key ?: return
        AndroidControlsIniHandler.removeMapping(settingKey)
    }

    fun onKeyInput(keyEvent: KeyEvent) {
        if (!isButtonMappingSupported()) {
            Toast.makeText(context, R.string.input_message_analog_only, Toast.LENGTH_LONG).show()
            return
        }

        val settingKey = abstractSetting.key ?: return
        val code = translateEventToKeyId(keyEvent)

        AndroidControlsIniHandler.replaceButtonMapping(settingKey, code)
    }

    fun onMotionInput(device: InputDevice, motionRange: MotionRange, axisDir: Char) {
        if (!isAxisMappingSupported()) {
            Toast.makeText(context, R.string.input_message_button_only, Toast.LENGTH_LONG).show()
            return
        }

        val settingKey = abstractSetting.key ?: return
        val button = if (isCirclePad()) {
            NativeLibrary.ButtonType.STICK_LEFT
        } else if (isCStick()) {
            NativeLibrary.ButtonType.STICK_C
        } else if (isDPad()) {
            NativeLibrary.ButtonType.DPAD
        } else {
            buttonCode
        }
        val orientation = if (isHorizontalOrientation()) 0 else 1
        val inverted = if (isHorizontalOrientation()) axisDir == '-' else axisDir == '+'

        AndroidControlsIniHandler.replaceAxisMapping(
            settingKey, motionRange.axis, button, orientation, inverted
        )
    }

    override val type = TYPE_INPUT_BINDING

    companion object {
        fun translateEventToKeyId(event: KeyEvent): Int =
            if (event.keyCode == 0) event.scanCode else event.keyCode

        fun settingKeyToButtonCode(key: String): Int? = when (key) {
            Settings.KEY_BUTTON_A -> NativeLibrary.ButtonType.BUTTON_A
            Settings.KEY_BUTTON_B -> NativeLibrary.ButtonType.BUTTON_B
            Settings.KEY_BUTTON_X -> NativeLibrary.ButtonType.BUTTON_X
            Settings.KEY_BUTTON_Y -> NativeLibrary.ButtonType.BUTTON_Y
            Settings.KEY_BUTTON_L -> NativeLibrary.ButtonType.TRIGGER_L
            Settings.KEY_BUTTON_R -> NativeLibrary.ButtonType.TRIGGER_R
            Settings.KEY_BUTTON_ZL -> NativeLibrary.ButtonType.BUTTON_ZL
            Settings.KEY_BUTTON_ZR -> NativeLibrary.ButtonType.BUTTON_ZR
            Settings.KEY_BUTTON_SELECT -> NativeLibrary.ButtonType.BUTTON_SELECT
            Settings.KEY_BUTTON_START -> NativeLibrary.ButtonType.BUTTON_START
            Settings.KEY_BUTTON_HOME -> NativeLibrary.ButtonType.BUTTON_HOME
            Settings.KEY_BUTTON_UP -> NativeLibrary.ButtonType.DPAD_UP
            Settings.KEY_BUTTON_DOWN -> NativeLibrary.ButtonType.DPAD_DOWN
            Settings.KEY_BUTTON_LEFT -> NativeLibrary.ButtonType.DPAD_LEFT
            Settings.KEY_BUTTON_RIGHT -> NativeLibrary.ButtonType.DPAD_RIGHT
            Settings.HOTKEY_SCREEN_SWAP -> Hotkey.SWAP_SCREEN.button
            Settings.HOTKEY_CYCLE_LAYOUT -> Hotkey.CYCLE_LAYOUT.button
            Settings.HOTKEY_CLOSE_GAME -> Hotkey.CLOSE_GAME.button
            Settings.HOTKEY_PAUSE_OR_RESUME -> Hotkey.PAUSE_OR_RESUME.button
            Settings.HOTKEY_QUICKSAVE -> Hotkey.QUICKSAVE.button
            Settings.HOTKEY_QUICKlOAD -> Hotkey.QUICKLOAD.button
            Settings.HOTKEY_TURBO_LIMIT -> Hotkey.TURBO_LIMIT.button
            else -> null
        }
    }
}
