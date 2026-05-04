package com.mapo.di

import android.content.Context
import com.mapo.data.db.AppDatabase
import com.mapo.data.db.AppProfileBindingDao
import com.mapo.data.db.GamepadMappingDao
import com.mapo.data.db.KeyboardTemplateDao
import com.mapo.data.db.LayoutDao
import com.mapo.data.db.ProfileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideLayoutDao(db: AppDatabase): LayoutDao = db.layoutDao()

    @Provides
    @Singleton
    fun provideProfileDao(db: AppDatabase): ProfileDao = db.profileDao()

    @Provides
    @Singleton
    fun provideGamepadMappingDao(db: AppDatabase): GamepadMappingDao = db.gamepadMappingDao()

    @Provides
    @Singleton
    fun provideAppProfileBindingDao(db: AppDatabase): AppProfileBindingDao = db.appProfileBindingDao()

    @Provides
    @Singleton
    fun provideKeyboardTemplateDao(db: AppDatabase): KeyboardTemplateDao = db.keyboardTemplateDao()
}
