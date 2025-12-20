plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.app.nosatmosphereeffect"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.app.nosatmosphereeffect"
        minSdk = 36 // Android 14+
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildToolsVersion = "34.0.0"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Versions
    val coreKtxVersion = "1.17.0"
    val lifecycleVersion = "2.10.0"
    val appcompatVersion = "1.7.1"
    val materialVersion = "1.13.0"

    // Core Android
    implementation("androidx.core:core-ktx:$coreKtxVersion")
    implementation("androidx.lifecycle:lifecycle-service:$lifecycleVersion")

    implementation("androidx.appcompat:appcompat:$appcompatVersion")
    implementation("com.google.android.material:material:$materialVersion")
}