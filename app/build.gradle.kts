plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.app.nosatmosphereeffect"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.app.nosatmosphereeffect"
        minSdk = 34 // Android 14+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Versions
    val coreKtxVersion = "1.13.1"
    val lifecycleVersion = "2.8.0"
    val appcompatVersion = "1.7.0"
    val materialVersion = "1.12.0"

    // Core Android
    implementation("androidx.core:core-ktx:$coreKtxVersion")
    implementation("androidx.lifecycle:lifecycle-service:$lifecycleVersion")

    implementation("androidx.appcompat:appcompat:$appcompatVersion")
    implementation("com.google.android.material:material:$materialVersion")
}