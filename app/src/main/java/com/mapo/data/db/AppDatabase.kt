package com.mapo.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mapo.data.model.GamepadMapping
import com.mapo.data.model.KeyLayout
import com.mapo.data.model.Profile

@Database(entities = [KeyLayout::class, Profile::class, GamepadMapping::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun layoutDao(): LayoutDao
    abstract fun profileDao(): ProfileDao
    abstract fun gamepadMappingDao(): GamepadMappingDao

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
