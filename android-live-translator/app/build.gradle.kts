plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.muna.livetranslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.muna.livetranslate"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        // Only our JNI library; skip the command-line tools bundled with third-party projects.
        externalNativeBuild { cmake { targets += "nativecore" } }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Optimised native speech engine, signed with the local debug key for easy sideloading.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // Offline translation
    implementation("com.google.mlkit:translate:17.0.3")
}
