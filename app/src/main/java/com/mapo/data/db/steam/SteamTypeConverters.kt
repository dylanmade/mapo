package com.mapo.data.db.steam

import androidx.room.TypeConverter
import com.mapo.data.model.steam.ActivatorType
import com.mapo.data.model.steam.BindingMode
import com.mapo.data.model.steam.BindingOutputType
import com.mapo.data.model.steam.ControllerType
import com.mapo.data.model.steam.InputSource

class SteamTypeConverters {

    @TypeConverter fun controllerTypeToString(value: ControllerType): String = value.name
    @TypeConverter fun stringToControllerType(value: String): ControllerType = ControllerType.valueOf(value)

    @TypeConverter fun bindingModeToString(value: BindingMode): String = value.name

    /**
     * Read-side BindingMode with Phase-7-rename legacy migration. Old DB
     * entries written under the pre-2026-05-27 names map to their successors;
     * unknown legacy values fall back to [BindingMode.DEVICE_DEFAULT] so a
     * truly broken row doesn't crash app startup.
     */
    @TypeConverter fun stringToBindingMode(value: String): BindingMode = when (value) {
        "UNBOUND" -> BindingMode.DEVICE_DEFAULT
        "MOUSE_JOYSTICK" -> BindingMode.JOYSTICK_MOUSE
        "JOYSTICK_CAMERA" -> BindingMode.JOYSTICK_MOUSE // camera tuning becomes a settings preset
        "ABSOLUTE_MOUSE" -> BindingMode.MOUSE_REGION
        "TWO_D_SCROLL" -> BindingMode.SCROLL_WHEEL // closest equivalent; user re-configures if it matters
        else -> runCatching { BindingMode.valueOf(value) }.getOrDefault(BindingMode.DEVICE_DEFAULT)
    }

    @TypeConverter fun activatorTypeToString(value: ActivatorType): String = value.name
    @TypeConverter fun stringToActivatorType(value: String): ActivatorType = ActivatorType.valueOf(value)

    @TypeConverter fun bindingOutputTypeToString(value: BindingOutputType): String = value.name

    /**
     * Read-side BindingOutputType with defensive fallback. `MODE_SHIFT` was a
     * short-lived Brick B output type (2026-05-27 → 2026-05-28); any survivor
     * row from that window maps to [BindingOutputType.UNBOUND] so the
     * activator's binding becomes inert rather than crashing app startup.
     * Mode shifts are now configured on the source via `SourceModeShift`.
     */
    @TypeConverter fun stringToBindingOutputType(value: String): BindingOutputType = when (value) {
        "MODE_SHIFT" -> BindingOutputType.UNBOUND
        else -> runCatching { BindingOutputType.valueOf(value) }.getOrDefault(BindingOutputType.UNBOUND)
    }

    @TypeConverter fun inputSourceToString(value: InputSource): String = value.name
    @TypeConverter fun stringToInputSource(value: String): InputSource = InputSource.valueOf(value)
}
