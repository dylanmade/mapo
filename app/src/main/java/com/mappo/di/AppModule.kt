package com.mappo.di

import android.content.Context
import com.mappo.data.db.AppDatabase
import com.mappo.data.db.AppProfileBindingDao
import com.mappo.data.db.KeyboardTemplateDao
import com.mappo.data.db.LayoutDao
import com.mappo.data.db.OverlayElementDao
import com.mappo.data.db.ProfileDao
import com.mappo.data.db.steam.ActionLayerDao
import com.mappo.data.db.steam.ActionSetDao
import com.mappo.data.db.steam.ActivatorDao
import com.mappo.data.db.steam.BindingDao
import com.mappo.data.db.steam.BindingGroupDao
import com.mappo.data.db.steam.ControllerProfileDao
import com.mappo.data.db.steam.GameActionDao
import com.mappo.data.db.steam.GroupInputDao
import com.mappo.data.db.steam.LayerPresetBindingDao
import com.mappo.data.db.steam.PresetBindingDao
import com.mappo.data.db.steam.SourceModeShiftDao
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

    @Provides
    @Singleton
    fun provideOverlayElementDao(db: AppDatabase): OverlayElementDao = db.overlayElementDao()

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

    @Provides @Singleton
    fun provideSourceModeShiftDao(db: AppDatabase): SourceModeShiftDao = db.sourceModeShiftDao()
}
