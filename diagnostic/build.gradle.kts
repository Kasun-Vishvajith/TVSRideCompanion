plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sharedBackend by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java")) {
        include(
            "dev/tvsopen/protocol/**",
            "dev/tvsopen/log/**",
            "dev/tvsopen/transport/ScooterBleService.kt",
            "dev/tvsopen/transport/ScooterScanner.kt",
            "dev/tvsopen/transport/GattWritePolicy.kt",
            "dev/tvsopen/nav/NavigationBridge.kt",
            "dev/tvsopen/nav/NavInstruction.kt",
            "dev/tvsopen/nav/RowChars.kt",
            "dev/tvsopen/nav/NavShortCodes.kt",
            "dev/tvsopen/nav/ManeuverReading.kt",
            "dev/tvsopen/session/Session.kt",
            "dev/tvsopen/prefs/RidePrefs.kt",
        )
    }
    into(layout.buildDirectory.dir("generated/sharedBackend"))
}

android {
    namespace = "dev.tvsopen.diagnostic"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.tvsopen.diagnostic"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    sourceSets.named("main") {
        kotlin.srcDir(sharedBackend)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.runtime:runtime")
}
