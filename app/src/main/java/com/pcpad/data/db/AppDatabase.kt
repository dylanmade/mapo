package com.pcpad.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pcpad.data.model.KeyLayout
import com.pcpad.data.model.Profile

@Database(entities = [KeyLayout::class, Profile::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun layoutDao(): LayoutDao
    abstract fun profileDao(): ProfileDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val seedCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                db.execSQL("INSERT INTO profiles (name, isDefault) VALUES ('Default', 1)")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pcpad.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .addCallback(seedCallback)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
