// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.settings.utils

import android.content.Context
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

    // Runtime lookup maps - the in-memory authority for bindings.
    // Bindings are loaded into memory once at startup. External edits to config.ini
    // (e.g., manual editing or backup restore) take effect when the user opens
    // Settings > Controls, or on full app restart.
    private val buttonMappings = mutableMapOf<Int, Int>()        // hostKeyCode -> guestButtonCode
    private val axisMappings = mutableMapOf<Int, AxisMapping>()   // hostAxis -> AxisMapping

    // Reverse maps: settingKey -> host input info (for display and removal)
    private val buttonSettingToHost = mutableMapOf<String, Int>()  // settingKey -> hostKeyCode
    private val axisSettingToHost = mutableMapOf<String, Int>()    // settingKey -> hostAxis

    data class AxisMapping(
        val settingKey: String,
        val guestButton: Int,
        val orientation: Int,
        val inverted: Boolean
    )

    private val allBindingKeys: List<String> by lazy {
        Settings.buttonKeys + Settings.triggerKeys +
            Settings.circlePadKeys + Settings.cStickKeys + Settings.dPadAxisKeys +
            Settings.dPadButtonKeys + Settings.hotKeys
    }

    private data class ButtonMappingDef(val settingKey: String, val hostKeyCode: Int, val buttonCode: Int)
    private data class AxisMappingDef(val settingKey: String, val axis: Int, val guestButton: Int, val orientation: Int, val inverted: Boolean)

    private val defaultButtonMappings = listOf(
        ButtonMappingDef(Settings.KEY_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, NativeLibrary.ButtonType.BUTTON_A),
        ButtonMappingDef(Settings.KEY_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A, NativeLibrary.ButtonType.BUTTON_B),
        ButtonMappingDef(Settings.KEY_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, NativeLibrary.ButtonType.BUTTON_X),
        ButtonMappingDef(Settings.KEY_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_X, NativeLibrary.ButtonType.BUTTON_Y),
        ButtonMappingDef(Settings.KEY_BUTTON_L, KeyEvent.KEYCODE_BUTTON_L1, NativeLibrary.ButtonType.TRIGGER_L),
        ButtonMappingDef(Settings.KEY_BUTTON_R, KeyEvent.KEYCODE_BUTTON_R1, NativeLibrary.ButtonType.TRIGGER_R),
        ButtonMappingDef(Settings.KEY_BUTTON_ZL, KeyEvent.KEYCODE_BUTTON_L2, NativeLibrary.ButtonType.BUTTON_ZL),
        ButtonMappingDef(Settings.KEY_BUTTON_ZR, KeyEvent.KEYCODE_BUTTON_R2, NativeLibrary.ButtonType.BUTTON_ZR),
        ButtonMappingDef(Settings.KEY_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_SELECT, NativeLibrary.ButtonType.BUTTON_SELECT),
        ButtonMappingDef(Settings.KEY_BUTTON_START, KeyEvent.KEYCODE_BUTTON_START, NativeLibrary.ButtonType.BUTTON_START),
        ButtonMappingDef(Settings.HOTKEY_SCREEN_SWAP, KeyEvent.KEYCODE_BUTTON_THUMBL, Hotkey.SWAP_SCREEN.button),
        ButtonMappingDef(Settings.HOTKEY_CYCLE_LAYOUT, KeyEvent.KEYCODE_BUTTON_THUMBR, Hotkey.CYCLE_LAYOUT.button)
    )

    private val defaultAxisMappings = listOf(
        AxisMappingDef(Settings.KEY_CIRCLEPAD_AXIS_HORIZONTAL, MotionEvent.AXIS_X, NativeLibrary.ButtonType.STICK_LEFT, 0, false),
        AxisMappingDef(Settings.KEY_CIRCLEPAD_AXIS_VERTICAL, MotionEvent.AXIS_Y, NativeLibrary.ButtonType.STICK_LEFT, 1, false),
        AxisMappingDef(Settings.KEY_CSTICK_AXIS_HORIZONTAL, MotionEvent.AXIS_Z, NativeLibrary.ButtonType.STICK_C, 0, false),
        AxisMappingDef(Settings.KEY_CSTICK_AXIS_VERTICAL, MotionEvent.AXIS_RZ, NativeLibrary.ButtonType.STICK_C, 1, false),
        AxisMappingDef(Settings.KEY_DPAD_AXIS_HORIZONTAL, MotionEvent.AXIS_HAT_X, NativeLibrary.ButtonType.DPAD, 0, false),
        AxisMappingDef(Settings.KEY_DPAD_AXIS_VERTICAL, MotionEvent.AXIS_HAT_Y, NativeLibrary.ButtonType.DPAD, 1, false)
    )

    fun getButtonMapping(hostKeyCode: Int): Int =
        buttonMappings[hostKeyCode] ?: hostKeyCode

    fun getAxisMapping(axis: Int): AxisMapping? =
        axisMappings[axis]

    fun getDisplayString(settingKey: String): String {
        buttonSettingToHost[settingKey]?.let { hostKeyCode ->
            return "Button $hostKeyCode"
        }
        axisSettingToHost[settingKey]?.let { hostAxis ->
            val mapping = axisMappings[hostAxis] ?: return ""
            val direction = if (mapping.orientation == 0) '+' else '-'
            return "Axis $hostAxis$direction"
        }
        return ""
    }

    fun replaceButtonMapping(settingKey: String, hostKeyCode: Int): Boolean {
        val buttonCode = InputBindingSetting.settingKeyToButtonCode(settingKey) ?: return false

        // Remove this setting's old mapping from in-memory maps
        removeFromMaps(settingKey)

        // Evict any other setting currently bound to the same host key
        val evictedKey = evictHostKeyCode(hostKeyCode)

        // Write new mapping to in-memory maps
        buttonMappings[hostKeyCode] = buttonCode
        buttonSettingToHost[settingKey] = hostKeyCode

        // Single INI round-trip: remove old + evict collision + write new
        val success = modifyIni { writer ->
            writer[SECTION]?.remove(settingKey)
            if (evictedKey != null) writer[SECTION]?.remove(evictedKey)
            writer.put(SECTION, settingKey, hostKeyCode.toString())
        }
        if (!success) {
            Log.warning("[AndroidControlsIniHandler] INI write failed for button '$settingKey', binding active for this session only")
        }
        return success
    }

    fun replaceAxisMapping(settingKey: String, axis: Int, guestButton: Int, orientation: Int, inverted: Boolean): Boolean {
        // Remove this setting's old mapping from in-memory maps
        removeFromMaps(settingKey)

        // Evict any other setting currently bound to the same host axis
        val evictedKey = evictHostAxis(axis)

        // Write new mapping to in-memory maps
        axisMappings[axis] = AxisMapping(settingKey, guestButton, orientation, inverted)
        axisSettingToHost[settingKey] = axis

        // Single INI round-trip: remove old + evict collision + write new
        val success = modifyIni { writer ->
            writer[SECTION]?.remove(settingKey)
            if (evictedKey != null) writer[SECTION]?.remove(evictedKey)
            writer.put(SECTION, settingKey, formatAxisValue(axis, guestButton, orientation, inverted))
        }
        if (!success) {
            Log.warning("[AndroidControlsIniHandler] INI write failed for axis '$settingKey', binding active for this session only")
        }
        return success
    }

    fun removeMapping(settingKey: String) {
        removeFromMaps(settingKey)

        modifyIni { writer ->
            val section = writer[SECTION] ?: return@modifyIni
            section.remove(settingKey)
        }
    }

    private fun evictHostKeyCode(hostKeyCode: Int): String? {
        val evicted = buttonSettingToHost.entries.firstOrNull { it.value == hostKeyCode }
            ?: return null
        buttonSettingToHost.remove(evicted.key)
        buttonMappings.remove(hostKeyCode)
        return evicted.key
    }

    private fun evictHostAxis(axis: Int): String? {
        val evicted = axisSettingToHost.entries.firstOrNull { it.value == axis }
            ?: return null
        axisSettingToHost.remove(evicted.key)
        axisMappings.remove(axis)
        return evicted.key
    }

    private fun removeFromMaps(settingKey: String) {
        buttonSettingToHost.remove(settingKey)?.let { hostKeyCode ->
            buttonMappings.remove(hostKeyCode)
        }
        axisSettingToHost.remove(settingKey)?.let { hostAxis ->
            axisMappings.remove(hostAxis)
        }
    }

    fun clearAllBindings() {
        // Clear in-memory maps
        buttonMappings.clear()
        axisMappings.clear()
        buttonSettingToHost.clear()
        axisSettingToHost.clear()

        // Clear INI
        modifyIni { writer ->
            val section = writer[SECTION] ?: return@modifyIni
            for (key in allBindingKeys) {
                section.remove(key)
            }
        }
    }

    fun applyDefaultBindings(): Boolean {
        // Update in-memory maps
        for (m in defaultButtonMappings) {
            buttonMappings[m.hostKeyCode] = m.buttonCode
            buttonSettingToHost[m.settingKey] = m.hostKeyCode
        }
        for (m in defaultAxisMappings) {
            axisMappings[m.axis] = AxisMapping(m.settingKey, m.guestButton, m.orientation, m.inverted)
            axisSettingToHost[m.settingKey] = m.axis
        }

        // Write to INI
        val success = modifyIni { writer ->
            for (m in defaultButtonMappings) {
                writer.put(SECTION, m.settingKey, m.hostKeyCode.toString())
            }
            for (m in defaultAxisMappings) {
                writer.put(SECTION, m.settingKey, formatAxisValue(m.axis, m.guestButton, m.orientation, m.inverted))
            }
        }
        if (!success) {
            Log.warning("[AndroidControlsIniHandler] INI write failed for default bindings, bindings active for this session only")
        }
        return success
    }

    fun loadFromIni(): Boolean {
        clearInMemoryMaps()
        return readIniIntoMaps()
    }

    fun reloadFromIni(): Boolean = loadFromIni()

    fun migrateFromSharedPreferencesIfNeeded(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean("bindings_migrated_to_ini", false)) return false

        // Check if INI already has bindings (user had the dual-write version)
        if (iniHasBindings()) {
            prefs.edit().putBoolean("bindings_migrated_to_ini", true).apply()
            cleanUpSharedPreferencesBindings(prefs)
            return false
        }

        // Check if SharedPreferences has binding data to migrate
        val allPrefs = prefs.all
        val hasOldBindings = allPrefs.keys.any { it.startsWith("InputMapping_HostAxis_") } ||
            allBindingKeys.any { !prefs.getString(it, "").isNullOrEmpty() }

        if (!hasOldBindings) {
            prefs.edit().putBoolean("bindings_migrated_to_ini", true).apply()
            return false
        }

        // Migrate button bindings from SharedPreferences to INI.
        // Old format may include device name prefix: "Xbox Controller: Button 97"
        // or just "Button 97" (from the dual-write interim version).
        var migrated = false
        modifyIni { writer ->
            for (bindingKey in allBindingKeys) {
                val displayValue = prefs.getString(bindingKey, "") ?: continue
                if (displayValue.isBlank()) continue

                // Strip optional device name prefix (e.g., "Xbox Controller: Button 97" -> "Button 97")
                val payload = displayValue.substringAfter(": ", displayValue)

                if (payload.startsWith("Button ")) {
                    val hostKeyCode = payload.removePrefix("Button ").toIntOrNull() ?: continue
                    writer.put(SECTION, bindingKey, hostKeyCode.toString())
                    migrated = true
                } else if (payload.startsWith("Axis ")) {
                    val axisStr = payload.removePrefix("Axis ")
                    val axisNum = axisStr.dropLast(1).toIntOrNull() ?: continue
                    val dir = axisStr.lastOrNull() ?: continue
                    val orientation = if (dir == '+') 0 else 1

                    // Try to recover guest button and inverted from old SharedPreferences keys
                    val guestButton = prefs.getInt("InputMapping_HostAxis_${axisNum}_GuestButton", -1)
                    val inverted = prefs.getBoolean("InputMapping_HostAxis_${axisNum}_Inverted", false)
                    if (guestButton == -1) continue

                    writer.put(SECTION, bindingKey, formatAxisValue(axisNum, guestButton, orientation, inverted))
                    migrated = true
                }
            }
        }

        // Set migration flag
        prefs.edit().putBoolean("bindings_migrated_to_ini", true).apply()

        if (migrated) {
            cleanUpSharedPreferencesBindings(prefs)
            Log.info("[AndroidControlsIniHandler] Migrated bindings from SharedPreferences to INI")
        } else if (hasOldBindings) {
            Log.warning("[AndroidControlsIniHandler] Found old binding keys in SharedPreferences but failed to migrate any")
        }

        return migrated
    }

    private fun cleanUpSharedPreferencesBindings(prefs: android.content.SharedPreferences) {
        val editor = prefs.edit()
        val allKeys = prefs.all.keys.toList()
        for (key in allKeys) {
            if (key.startsWith("InputMapping_") || key in allBindingKeys) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    private fun iniHasBindings(): Boolean {
        try {
            val ini = SettingsFile.getSettingsFile(SettingsFile.FILE_NAME_CONFIG)
            val reader = context.contentResolver.openInputStream(ini.uri)?.use { Wini(it) }
                ?: return false
            val section = reader[SECTION] ?: return false
            return section.keys.any { it in allBindingKeys }
        } catch (e: Exception) {
            return false
        }
    }

    private fun clearInMemoryMaps() {
        buttonMappings.clear()
        axisMappings.clear()
        buttonSettingToHost.clear()
        axisSettingToHost.clear()
    }

    private fun readIniIntoMaps(): Boolean {
        try {
            val ini = SettingsFile.getSettingsFile(SettingsFile.FILE_NAME_CONFIG)
            val reader = context.contentResolver.openInputStream(ini.uri)?.use { Wini(it) }
                ?: return false
            val section = reader[SECTION] ?: return false
            if (section.isEmpty()) return false

            var loaded = false

            for (settingKey in section.keys) {
                val value = section[settingKey] ?: continue
                loaded = if (value.contains(","))
                    loadAxisBinding(settingKey, value) || loaded
                else
                    loadButtonBinding(settingKey, value) || loaded
            }

            if (loaded) {
                Log.info("[AndroidControlsIniHandler] Loaded bindings from config.ini")
            }
            return loaded
        } catch (e: Exception) {
            Log.error("[AndroidControlsIniHandler] Failed to load from INI: ${e.message}")
            return false
        }
    }

    private fun loadButtonBinding(settingKey: String, value: String): Boolean {
        val hostKeyCode = value.toIntOrNull()
        if (hostKeyCode == null) {
            Log.warning("[AndroidControlsIniHandler] Missing/invalid hostKeyCode for '$settingKey': '$value'")
            return false
        }
        val buttonCode = InputBindingSetting.settingKeyToButtonCode(settingKey)
        if (buttonCode == null) {
            Log.warning("[AndroidControlsIniHandler] Unknown setting key '$settingKey'")
            return false
        }
        buttonMappings[hostKeyCode] = buttonCode
        buttonSettingToHost[settingKey] = hostKeyCode
        return true
    }

    private fun loadAxisBinding(settingKey: String, value: String): Boolean {
        val params = parseParams(value)

        val axis = params["axis"]?.toIntOrNull()
        if (axis == null) {
            Log.warning("[AndroidControlsIniHandler] Missing/invalid 'axis' for '$settingKey': '$value'")
            return false
        }
        val guestButton = params["guest"]?.toIntOrNull()
        if (guestButton == null) {
            Log.warning("[AndroidControlsIniHandler] Missing/invalid 'guest' for '$settingKey': '$value'")
            return false
        }
        val orientation = params["orientation"]?.toIntOrNull()
        if (orientation == null) {
            Log.warning("[AndroidControlsIniHandler] Missing/invalid 'orientation' for '$settingKey': '$value'")
            return false
        }
        val inverted = params["inverted"]?.toBooleanStrictOrNull()
        if (inverted == null) {
            Log.warning("[AndroidControlsIniHandler] Missing/invalid 'inverted' for '$settingKey': '$value'")
            return false
        }

        axisMappings[axis] = AxisMapping(settingKey, guestButton, orientation, inverted)
        axisSettingToHost[settingKey] = axis
        return true
    }

    private fun formatAxisValue(axis: Int, guestButton: Int, orientation: Int, inverted: Boolean): String =
        "axis:$axis,guest:$guestButton,orientation:$orientation,inverted:$inverted"

    private fun parseParams(value: String): Map<String, String> =
        value.split(",").mapNotNull {
            val parts = it.split(":", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()

    private inline fun modifyIni(block: (Wini) -> Unit): Boolean {
        return try {
            val ini = SettingsFile.getSettingsFile(SettingsFile.FILE_NAME_CONFIG)
            val writer = context.contentResolver.openInputStream(ini.uri)?.use { Wini(it) }
                ?: return false
            block(writer)
            context.contentResolver.openOutputStream(ini.uri, "wt")?.use { output ->
                writer.store(output)
                output.flush()
            }
            true
        } catch (e: Exception) {
            Log.error("[AndroidControlsIniHandler] INI write failed: ${e.message}")
            false
        }
    }
}
