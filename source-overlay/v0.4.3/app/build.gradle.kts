plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cybx.os"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cybx.os"
        minSdk = 26
        targetSdk = 28
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 12
        versionName = "0.4.3"
    }
    buildFeatures { compose = true }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libproot.so"
        }
    }
    signingConfigs {
        create("cybxRelease") {
            val keyStorePath = System.getenv("CYBX_KEYSTORE_FILE")
            if (!keyStorePath.isNullOrBlank()) {
                storeFile = file(keyStorePath)
                storePassword = System.getenv("CYBX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CYBX_KEY_ALIAS")
                keyPassword = System.getenv("CYBX_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            val keyStorePath = System.getenv("CYBX_KEYSTORE_FILE")
            if (!keyStorePath.isNullOrBlank()) signingConfig = signingConfigs.getByName("cybxRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.termux.termux-app:terminal-view:0.118.0")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("com.google.guava:listenablefuture:1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
