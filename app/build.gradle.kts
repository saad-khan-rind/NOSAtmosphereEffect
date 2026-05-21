plugins {
    id("com.android.application")
}

android {
    namespace = "com.app.nosatmosphereeffect"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.saad_khan_rind.atmosphere_effect"
        versionName = "6.0.0"
        versionCode = 100600
    }

    flavorDimensions += "apiLevel"

    productFlavors {

        create("v36") {
            dimension = "apiLevel"
            minSdk = 36
            targetSdk = 36
            versionCode = 200600
        }

        create("v33") {
            dimension = "apiLevel"
            minSdk = 33
            targetSdk = 33
            versionCode = 100600
        }

    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.exifinterface)

    // Jetpack Compose Dependencies
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- Dependencies for v36 (API 36) ---
    // These only apply when building the v36 flavor
    "v36Implementation"("androidx.core:core-ktx:1.17.0")
    "v36Implementation"("androidx.lifecycle:lifecycle-service:2.10.0")
    "v36Implementation"("androidx.appcompat:appcompat:1.7.1")
    "v36Implementation"("com.google.android.material:material:1.13.0")

    // --- Dependencies for v33 (API 33) ---
    // These only apply when building the v33 flavor
    "v33Implementation"("androidx.core:core-ktx:1.12.0")
    "v33Implementation"("androidx.lifecycle:lifecycle-service:2.6.2")
    "v33Implementation"("androidx.appcompat:appcompat:1.6.1")
    "v33Implementation"("com.google.android.material:material:1.11.0")
}