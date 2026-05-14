package com.mapo.di

import android.content.Context
import com.mapo.data.db.AppDatabase
import com.mapo.data.db.AppProfileBindingDao
import com.mapo.data.db.KeyboardTemplateDao
import com.mapo.data.db.LayoutDao
import com.mapo.data.db.ProfileDao
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
    fun provideAppProfileBindingDao(db: AppDatabase): AppProfileBindingDao = db.appProfileBindingDao()

    @Provides
    @Singleton
    fun provideKeyboardTemplateDao(db: AppDatabase): KeyboardTemplateDao = db.keyboardTemplateDao()

    @Provides @Singleton
    fun provideControllerProfileDao(db: AppDatabase): ControllerProfileDao = db.controllerProfileDao()

    @Provides @Singleton
    fun provideActionSetDao(db: AppDatabase): ActionSetDao = db.actionSetDao()

    @Provides @Singleton
    fun provideActionLayerDao(db: AppDatabase): ActionLayerDao = db.actionLayerDao()

    @Provides @Singleton
    fun provideGameActionDao(db: AppDatabase): GameActionDao = db.gameActionDao()

    @Provides @Singleton
    fun provideBindingGroupDao(db: AppDatabase): BindingGroupDao = db.bindingGroupDao()

    @Provides @Singleton
    fun provideGroupInputDao(db: AppDatabase): GroupInputDao = db.groupInputDao()

    @Provides @Singleton
    fun provideActivatorDao(db: AppDatabase): ActivatorDao = db.activatorDao()

    @Provides @Singleton
    fun provideBindingDao(db: AppDatabase): BindingDao = db.bindingDao()

    @Provides @Singleton
    fun providePresetBindingDao(db: AppDatabase): PresetBindingDao = db.presetBindingDao()

    @Provides @Singleton
    fun provideLayerPresetBindingDao(db: AppDatabase): LayerPresetBindingDao = db.layerPresetBindingDao()
}
