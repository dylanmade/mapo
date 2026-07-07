plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
}

// Pure data + AIDL contracts module. Shared between :app (app-process, Hilt-aware)
// and :shizuku-service (shell-uid process, Hilt-free). Keep this module's surface
// MINIMAL: no Hilt, no Compose, no Room, no Coroutines. Just the AIDL interfaces
// and their Parcelable DTOs. Anything bigger lands in the consumer modules.
android {
    namespace = "com.mappo.shizuku"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // AIDL generation runs at build time and emits Java classes for IMappoInputService.Stub
        // and friends. Both :app and :shizuku-service consume those via this module's AAR.
        aidl = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Intentionally empty beyond AGP-provided android.os.* stubs from compileSdk.
    // If this list ever grows, reconsider whether the new dep really belongs HERE
    // or in a consumer module.
}
