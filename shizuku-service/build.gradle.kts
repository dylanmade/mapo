plugins {
    alias(libs.plugins.android.library)
}

// The Shizuku UserService class lives here. At runtime the class loads in a
// SEPARATE process under shell UID (Shizuku spawns it), so this module:
//
//   - MUST NOT apply Hilt — process boot needs to be fast and Hilt's graph
//     init would deadlock in shell-context anyway.
//   - MUST NOT apply Compose or Room — same reason; nothing UI-shaped runs here.
//   - MAY depend on :input-shared (AIDL + DTOs) only.
//
// The class IS bundled into :app's APK (Shizuku loads it from there). The
// manifest declaration here is merged into :app's manifest automatically.
android {
    namespace = "com.mapo.shizuku.service"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":input-shared"))

    // Brick E: HiddenApiBypass is required so the shell-uid service process can
    // reflect on `android.view.InputEvent#setDisplayId(int)` for multi-display
    // routing. Shell UID hidden-API enforcement varies across vendors / Android
    // versions — installing the exemption explicitly is the only portable way.
    // Single small jar; doesn't pull other deps.
    implementation(libs.hidden.api.bypass)

    // Reconsider before adding anything else here: every dep ships in the
    // shell-uid process and slows its cold start.
}
