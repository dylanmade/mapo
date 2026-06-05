package com.mapo.di

import android.content.Context
import com.mapo.data.steam.EncryptedPrefsSteamCredentialStore
import com.mapo.steam.auth.JavaSteamAuthRepository
import com.mapo.steam.auth.SteamAuthRepository
import com.mapo.steam.auth.SteamCredentialStore
import com.mapo.steam.library.JavaSteamLibraryRepository
import com.mapo.steam.library.SteamLibraryRepository
import com.mapo.steam.session.JavaSteamSessionFactory
import com.mapo.steam.session.SteamSessionFactory
import com.mapo.steam.workshop.JavaSteamWorkshopRepository
import com.mapo.steam.workshop.SteamWorkshopRepository
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
