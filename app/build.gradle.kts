plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.mappo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mappo"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "com.mappo.HiltTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // MaterialKolor — seed-based M3 expressive color generation (SPEC_2025). Feeds MappoTheme's
    // base color scheme from a seed instead of a hand-authored palette.
    implementation(libs.materialkolor)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)

    // Gson
    implementation(libs.gson)

    // Kotlinx immutable collections (stable Compose params)
    implementation(libs.kotlinx.collections.immutable)

    // Hidden API bypass — required so InputAccessibilityService can route injected
    // KeyEvents to a non-default display (Thor's bottom screen). See onCreate in
    // MappoApplication for the exemption call.
    implementation(libs.hidden.api.bypass)

    // Shizuku (Phase 6 analog modes). The `provider` artifact ships a ContentProvider
    // that publishes Mappo's binder to the Shizuku Manager app for permission routing;
    // declared in AndroidManifest.xml. The `api` artifact is the public surface
    // (state listeners, permission requests, UserService binding helpers). All
    // Shizuku.* references go through `service/shizuku/ShizukuFacade.kt` so missing
    // Shizuku Manager surfaces as a clean state rather than a class-load crash.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Theme Studio (in-tree dev tool; will move to debugImplementation once
    // release builds need slimming, or extract to a standalone repo)
    implementation(project(":themestudio"))

    // Shizuku UserService + shared AIDL contracts. :input-shared holds the AIDL
    // and Parcelable DTOs; :shizuku-service holds the UserService implementation
    // class that Shizuku launches under shell UID. :app bundles both into its
    // APK (Shizuku loads the UserService class from there).
    implementation(project(":input-shared"))
    implementation(project(":shizuku-service"))

    // Steam Input config import + browse (Phase 8). :steam-client wraps
    // JavaSteam for Steam Guard QR login + (later) IPublishedFile.QueryFiles
    // + CDN downloads.
    implementation(project(":steam-client"))

    // EncryptedSharedPreferences — backs the Steam credentials store
    // (refresh token + account name persisted across launches).
    implementation(libs.androidx.security.crypto)

    // ZXing — QR rendering for the Steam Guard login screen.
    implementation(libs.zxing.core)

    // JVM unit tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    // Compose UI tests on JVM via Robolectric (the AYN Thor's dual-display
    // behavior immediately backgrounds test-launched activities, so on-device
    // Compose UI tests don't work; Robolectric sidesteps this entirely).
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)

    // Instrumented tests (Room, Compose UI, Hilt)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // mockk-android intentionally not on androidTest classpath: its JVM TI
    // agent (libmockkjvmtiagent.so) interferes with Compose's UI test
    // instrumentation. Add back per-test if instrumented mocking is ever needed.
    androidTestImplementation(libs.androidx.room.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
