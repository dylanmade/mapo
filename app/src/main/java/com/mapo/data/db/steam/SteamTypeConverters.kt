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
    @TypeConverter fun stringToBindingMode(value: String): BindingMode = BindingMode.valueOf(value)

    @TypeConverter fun activatorTypeToString(value: ActivatorType): String = value.name
    @TypeConverter fun stringToActivatorType(value: String): ActivatorType = ActivatorType.valueOf(value)

    @TypeConverter fun bindingOutputTypeToString(value: BindingOutputType): String = value.name
    @TypeConverter fun stringToBindingOutputType(value: String): BindingOutputType = BindingOutputType.valueOf(value)

    @TypeConverter fun inputSourceToString(value: InputSource): String = value.name
    @TypeConverter fun stringToInputSource(value: String): InputSource = InputSource.valueOf(value)
}
