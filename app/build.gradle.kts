plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.amiri.videoengine"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.amiri.videoengine"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "1.2.0"
        // realme GT3 (and all modern phones) are 64-bit ARM; drop other CPU libraries to keep the APK small.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Personal signing key so every new build installs over the previous one.
        create("amiri") {
            storeFile = file("keystore/amiri-release.jks")
            storePassword = "amiriVideo2026"
            keyAlias = "amiri"
            keyPassword = "amiriVideo2026"
        }
    }

    buildTypes {
        release {
            // R8 removes unused code (the APK was ~3x bigger without it).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("amiri")
        }
        debug {
            signingConfig = signingConfigs.getByName("amiri")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // On-device Persian -> English prompt translation (free, offline after first download).
    implementation("com.google.mlkit:translate:17.0.3")
}
