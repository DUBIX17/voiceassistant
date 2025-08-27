plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.voiceassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.voiceassistant"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // URLs via BuildConfig
        buildConfigField("String", "WAKE_WS_URL", "\"wss://yourwakeurl\"")
        buildConfigField("String", "STT_WS_URL", "\"wss://yourstturl\"")
        buildConfigField("String", "AI_URL", "\"https://youraiurl\"")
        buildConfigField("String", "TTS_URL", "\"https://yourttsurl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
