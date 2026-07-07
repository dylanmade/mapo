package com.mappo.di

import android.content.Context
import com.mappo.data.steam.EncryptedPrefsSteamCredentialStore
import com.mappo.steam.auth.JavaSteamAuthRepository
import com.mappo.steam.auth.SteamAuthRepository
import com.mappo.steam.auth.SteamCredentialStore
import com.mappo.steam.library.JavaSteamLibraryRepository
import com.mappo.steam.library.SteamLibraryRepository
import com.mappo.steam.session.JavaSteamSessionFactory
import com.mappo.steam.session.SteamSessionFactory
import com.mappo.steam.workshop.JavaSteamWorkshopRepository
import com.mappo.steam.workshop.SteamWorkshopRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SteamModule {

    @Provides
    @Singleton
    fun provideSteamAuthRepository(): SteamAuthRepository = JavaSteamAuthRepository()

    @Provides
    @Singleton
    fun provideSteamCredentialStore(
        @ApplicationContext context: Context,
    ): SteamCredentialStore = EncryptedPrefsSteamCredentialStore(context)

    @Provides
    @Singleton
    fun provideSteamSessionFactory(
        credentialStore: SteamCredentialStore,
    ): SteamSessionFactory = JavaSteamSessionFactory(credentialStore)

    @Provides
    @Singleton
    fun provideSteamLibraryRepository(
        sessionFactory: SteamSessionFactory,
    ): SteamLibraryRepository = JavaSteamLibraryRepository(sessionFactory)

    @Provides
    @Singleton
    fun provideSteamWorkshopRepository(
        sessionFactory: SteamSessionFactory,
    ): SteamWorkshopRepository = JavaSteamWorkshopRepository(sessionFactory)
}
