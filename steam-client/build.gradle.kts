plugins {
    alias(libs.plugins.android.library)
}

// Wraps JavaSteam (Kotlin/Java SteamKit2 port) so the rest of the app
// can speak Steam's client protocol without touching JavaSteam types
// directly. Currently exposes Steam Guard QR login via SteamAuthRepository.
// Workshop QueryFiles + CDN fetch will land here in a follow-up PR.
//
// Module is Android-library shaped (matches :input-shared / :shizuku-service)
// so it can read Build.MODEL for the device label, and so Hilt wiring in
// :app can @Binds the interfaces without a separate JVM-vs-Android split.
android {
    namespace = "com.mapo.steam"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation(libs.javasteam)
    implementation(libs.spongycastle)
    // protobuf-java is needed at compile time because JavaSteam's
    // response body types extend GeneratedMessage / MessageOrBuilder.
    // Exposed via api() so :app sees it too (response types leak into
    // some shared APIs if/when we ever do — currently they're firewalled
    // by our data classes, but the safer default is to expose).
    api(libs.protobuf.java)

    // Coroutines: -core for Flow, -jdk8 for CompletableFuture.await()
    // (used to bridge JavaSteam's CompletableFuture API into suspends).
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)
}
