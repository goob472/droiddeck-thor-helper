// Test-only stand-in for DroidDeck, used on the emulator (never released). It installs under
// DroidDeck's package name and exposes its app folder through a documents provider the same way
// DroidDeck's AppFilesProvider does, seeded with fake Steam installs, so the helper's folder-picker
// access, game detection, apply and revert can be tested end to end without the real DroidDeck.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.droiddeck.fake"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.droiddeck.launcher"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "fake-for-tests"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
