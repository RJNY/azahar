// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.settings.utils

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.features.hotkeys.Hotkey
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.model.view.InputBindingSetting
import org.citra.citra_emu.utils.Log
import org.ini4j.Wini

object AndroidControlsIniHandler {

    private const val SECTION = Settings.SECTION_ANDROID_CONTROLS

    private val context: Context get() = CitraApplication.appContext
    private val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(context)

    private val allBindingKeys: List<String> by lazy {
        Settings.buttonKeys + Settings.triggerKeys +
            Settings.circlePadKeys + Settings.cStickKeys + Settings.dPadAxisKeys +
            Settings.dPadButtonKeys + Settings.hotKeys
    }

    private data class ButtonMapping(val settingKey: String, val hostKeyCode: Int, val buttonCode: Int)
    private data class AxisMapping(val settingKey: String, val axis: Int, val guestButton: Int, val orientation: Int, val inverted: Boolean)

    private val defaultButtonMappings = listOf(
        ButtonMapping(Settings.KEY_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, NativeLibrary.ButtonType.BUTTON_A),
        ButtonMapping(Settings.KEY_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A, NativeLibrary.ButtonType.BUTTON_B),
        ButtonMapping(Settings.KEY_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, NativeLibrary.ButtonType.BUTTON_X),
        ButtonMapping(Settings.KEY_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_X, NativeLibrary.ButtonType.BUTTON_Y),
        ButtonMapping(Settings.KEY_BUTTON_L, KeyEvent.KEYCODE_BUTTON_L1, NativeLibrary.ButtonType.TRIGGER_L),
        ButtonMapping(Settings.KEY_BUTTON_R, KeyEvent.KEYCODE_BUTTON_R1, NativeLibrary.ButtonType.TRIGGER_R),
        ButtonMapping(Settings.KEY_BUTTON_ZL, KeyEvent.KEYCODE_BUTTON_L2, NativeLibrary.ButtonType.BUTTON_ZL),
        ButtonMapping(Settings.KEY_BUTTON_ZR, KeyEvent.KEYCODE_BUTTON_R2, NativeLibrary.ButtonType.BUTTON_ZR),
        ButtonMapping(Settings.KEY_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_SELECT, NativeLibrary.ButtonType.BUTTON_SELECT),
        ButtonMapping(Settings.KEY_BUTTON_START, KeyEvent.KEYCODE_BUTTON_START, NativeLibrary.ButtonType.BUTTON_START),
        ButtonMapping(Settings.HOTKEY_SCREEN_SWAP, KeyEvent.KEYCODE_BUTTON_THUMBL, Hotkey.SWAP_SCREEN.button),
        ButtonMapping(Settings.HOTKEY_CYCLE_LAYOUT, KeyEvent.KEYCODE_BUTTON_THUMBR, Hotkey.CYCLE_LAYOUT.button)
    )

    private val defaultAxisMappings = listOf(
        AxisMapping(Settings.KEY_CIRCLEPAD_AXIS_HORIZONTAL, MotionEvent.AXIS_X, NativeLibrary.ButtonType.STICK_LEFT, 0, false),
        AxisMapping(Settings.KEY_CIRCLEPAD_AXIS_VERTICAL, MotionEvent.AXIS_Y, NativeLibrary.ButtonType.STICK_LEFT, 1, false),
        AxisMapping(Settings.KEY_CSTICK_AXIS_HORIZONTAL, MotionEvent.AXIS_Z, NativeLibrary.ButtonType.STICK_C, 0, false),
        AxisMapping(Settings.KEY_CSTICK_AXIS_VERTICAL, MotionEvent.AXIS_RZ, NativeLibrary.ButtonType.STICK_C, 1, false),
        AxisMapping(Settings.KEY_DPAD_AXIS_HORIZONTAL, MotionEvent.AXIS_HAT_X, NativeLibrary.ButtonType.DPAD, 0, false),
        AxisMapping(Settings.KEY_DPAD_AXIS_VERTICAL, MotionEvent.AXIS_HAT_Y, NativeLibrary.ButtonType.DPAD, 1, false)
    )

    fun writeButtonMapping(settingKey: String, hostKeyCode: Int) {
        modifyIni { writer ->
            writer.put(SECTION, settingKey, hostKeyCode.toString())
        }
    }

    fun writeAxisMapping(settingKey: String, axis: Int, guestButton: Int, orientation: Int, inverted: Boolean) {
        modifyIni { writer ->
            writer.put(SECTION, settingKey, formatAxisValue(axis, guestButton, orientation, inverted))
        }
    }

    fun removeMapping(settingKey: String) {
        modifyIni { writer ->
            val section = writer[SECTION] ?: return@modifyIni
            section.remove(settingKey)
        }
    }

    fun clearAllBindings() {
        clearSharedPreferencesBindings()
        modifyIni { writer ->
            val section = writer[SECTION] ?: return@modifyIni
            for (key in allBindingKeys) {
                section.remove(key)
            }
        }
    }

    private fun clearSharedPreferencesBindings() {
        val editor = preferences.edit()
        for (key in allBindingKeys) {
            val inputKey = preferences.getString(buildReverseKey(key), "")
            editor.remove(key)
            editor.remove(buildReverseKey(key))
            if (!inputKey.isNullOrEmpty()) {
                editor.remove(inputKey)
                editor.remove(inputKey + InputBindingSetting.SUFFIX_GUEST_ORIENTATION)
                editor.remove(inputKey + InputBindingSetting.SUFFIX_GUEST_BUTTON)
                editor.remove(inputKey + InputBindingSetting.SUFFIX_INVERTED)
            }
        }
        editor.apply()
    }

    private fun hasSharedPreferencesBindings(): Boolean =
        allBindingKeys.any { !preferences.getString(it, "").isNullOrEmpty() }

    fun loadBindingsFromIniIfNeeded(): Boolean {
        if (hasSharedPreferencesBindings()) return false
        return loadBindingsFromIni()
    }

    fun syncBindingsFromIni(): Boolean {
        clearSharedPreferencesBindings()
        return loadBindingsFromIni()
    }

    fun applyDefaultBindings(): Boolean {
        val editor = preferences.edit()

        for (m in defaultButtonMappings) {
            applyButtonToPrefs(editor, m.settingKey, m.hostKeyCode, m.buttonCode)
        }

        for (m in defaultAxisMappings) {
            applyAxisToPrefs(editor, m.settingKey, m.axis, m.guestButton, m.orientation, m.inverted)
        }

        editor.apply()
        writeDefaultMappingsToIni()
        return true
    }

    private fun writeDefaultMappingsToIni() {
        modifyIni { writer ->
            for (m in defaultButtonMappings) {
                writer.put(SECTION, m.settingKey, m.hostKeyCode.toString())
            }
            for (m in defaultAxisMappings) {
                writer.put(SECTION, m.settingKey, formatAxisValue(m.axis, m.guestButton, m.orientation, m.inverted))
            }
        }
    }

    private fun loadBindingsFromIni(): Boolean {
        try {
            val ini = SettingsFile.getSettingsFile(SettingsFile.FILE_NAME_CONFIG)
            val reader = context.contentResolver.openInputStream(ini.uri)?.use { Wini(it) }
                ?: return false
            val section = reader[SECTION] ?: return false
            if (section.isEmpty()) return false

            val editor = preferences.edit()
            var loaded = false

            for (settingKey in section.keys) {
                val value = section[settingKey] ?: continue
                loaded = if (value.contains(","))
                    loadAxisBinding(editor, settingKey, value) || loaded
                else
                    loadButtonBinding(editor, settingKey, value) || loaded
            }

            if (loaded) {
                editor.apply()
                Log.info("[AndroidControlsIniHandler] Loaded bindings from config.ini")
            }
            return loaded
        } catch (e: Exception) {
            Log.error("[AndroidControlsIniHandler] Failed to load from INI: ${e.message}")
            return false
        }
    }

    private fun loadButtonBinding(
        editor: SharedPreferences.Editor,
        settingKey: String,
        value: String
    ): Boolean {
        val hostKeyCode = value.toIntOrNull() ?: return false
        val buttonCode = InputBindingSetting.settingKeyToButtonCode(settingKey) ?: return false
        applyButtonToPrefs(editor, settingKey, hostKeyCode, buttonCode)
        return true
    }

    private fun loadAxisBinding(
        editor: SharedPreferences.Editor,
        settingKey: String,
        value: String
    ): Boolean {
        val params = parseParams(value)
        val axis = params["axis"]?.toIntOrNull() ?: return false
        val guestButton = params["guest"]?.toIntOrNull() ?: return false
        val orientation = params["orientation"]?.toIntOrNull() ?: return false
        val inverted = params["inverted"]?.toBooleanStrictOrNull() ?: return false
        applyAxisToPrefs(editor, settingKey, axis, guestButton, orientation, inverted)
        return true
    }

    private fun applyButtonToPrefs(
        editor: SharedPreferences.Editor,
        settingKey: String,
        hostKeyCode: Int,
        buttonCode: Int
    ) {
        val inputKey = InputBindingSetting.getInputButtonKey(hostKeyCode)
        editor.putInt(inputKey, buttonCode)
        editor.putString(buildReverseKey(settingKey), inputKey)
        editor.putString(settingKey, "Button $hostKeyCode")
    }

    private fun applyAxisToPrefs(
        editor: SharedPreferences.Editor,
        settingKey: String,
        axis: Int,
        guestButton: Int,
        orientation: Int,
        inverted: Boolean
    ) {
        val axisDir = if (orientation == 0) '+' else '-'
        val inputKey = InputBindingSetting.getInputAxisKey(axis)
        editor.putInt(InputBindingSetting.getInputAxisOrientationKey(axis), orientation)
        editor.putInt(InputBindingSetting.getInputAxisButtonKey(axis), guestButton)
        editor.putBoolean(InputBindingSetting.getInputAxisInvertedKey(axis), inverted)
        editor.putString(buildReverseKey(settingKey), inputKey)
        editor.putString(settingKey, "Axis $axis$axisDir")
    }

    private fun formatAxisValue(axis: Int, guestButton: Int, orientation: Int, inverted: Boolean): String =
        "axis:$axis,guest:$guestButton,orientation:$orientation,inverted:$inverted"

    private fun parseParams(value: String): Map<String, String> =
        value.split(",").mapNotNull {
            val parts = it.split(":", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()

    private fun buildReverseKey(settingKey: String): String =
        InputBindingSetting.buildReverseKey(settingKey)

    private inline fun modifyIni(block: (Wini) -> Unit) {
        try {
            val ini = SettingsFile.getSettingsFile(SettingsFile.FILE_NAME_CONFIG)
            val writer = context.contentResolver.openInputStream(ini.uri)?.use { Wini(it) }
                ?: return
            block(writer)
            context.contentResolver.openOutputStream(ini.uri, "wt")?.use { output ->
                writer.store(output)
                output.flush()
            }
        } catch (e: Exception) {
            Log.error("[AndroidControlsIniHandler] INI write failed: ${e.message}")
        }
    }
}
