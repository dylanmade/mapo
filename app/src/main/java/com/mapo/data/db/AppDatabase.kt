package com.mapo.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mapo.data.db.steam.ActionLayerDao
import com.mapo.data.db.steam.ActionSetDao
import com.mapo.data.db.steam.ActivatorDao
import com.mapo.data.db.steam.BindingDao
import com.mapo.data.db.steam.BindingGroupDao
import com.mapo.data.db.steam.ControllerProfileDao
import com.mapo.data.db.steam.GameActionDao
import com.mapo.data.db.steam.GroupInputDao
import com.mapo.data.db.steam.LayerPresetBindingDao
import com.mapo.data.db.steam.PresetBindingDao
import com.mapo.data.db.steam.SourceModeShiftDao
import com.mapo.data.db.steam.SteamTypeConverters
import com.mapo.data.model.AppProfileBinding
import com.mapo.data.model.KeyLayout
import com.mapo.data.model.KeyboardTemplate
import com.mapo.data.model.Profile
import com.mapo.data.model.steam.ActionLayer
import com.mapo.data.model.steam.ActionSet
import com.mapo.data.model.steam.Activator
import com.mapo.data.model.steam.Binding
import com.mapo.data.model.steam.BindingGroup
import com.mapo.data.model.steam.ControllerProfile
import com.mapo.data.model.steam.GameAction
import com.mapo.data.model.steam.GroupInput
import com.mapo.data.model.steam.LayerPresetBinding
import com.mapo.data.model.steam.PresetBinding
import com.mapo.data.model.steam.SourceModeShift

@Database(
    entities = [
        KeyLayout::class,
        Profile::class,
        AppProfileBinding::class,
        KeyboardTemplate::class,
        ControllerProfile::class,
        ActionSet::class,
        ActionLayer::class,
        GameAction::class,
        BindingGroup::class,
        GroupInput::class,
        Activator::class,
        Binding::class,
        PresetBinding::class,
        LayerPresetBinding::class,
        SourceModeShift::class,
    ],
    version = 15,
    exportSchema = false
)
@TypeConverters(SteamTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun layoutDao(): LayoutDao
    abstract fun profileDao(): ProfileDao
    abstract fun appProfileBindingDao(): AppProfileBindingDao
    abstract fun keyboardTemplateDao(): KeyboardTemplateDao

    abstract fun controllerProfileDao(): ControllerProfileDao
    abstract fun actionSetDao(): ActionSetDao
    abstract fun actionLayerDao(): ActionLayerDao
    abstract fun gameActionDao(): GameActionDao
    abstract fun bindingGroupDao(): BindingGroupDao
    abstract fun groupInputDao(): GroupInputDao
    abstract fun activatorDao(): ActivatorDao
    abstract fun bindingDao(): BindingDao
    abstract fun presetBindingDao(): PresetBindingDao
    abstract fun layerPresetBindingDao(): LayerPresetBindingDao
    abstract fun sourceModeShiftDao(): SourceModeShiftDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val seedCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL(
                    "INSERT INTO profiles (name, isDefault) " +
                    "SELECT 'Default', 1 " +
                    "WHERE NOT EXISTS (SELECT 1 FROM profiles WHERE isDefault = 1)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mapo.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .addCallback(seedCallback)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
