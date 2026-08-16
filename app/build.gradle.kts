plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mapsdroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mapsdroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Self-signed key committed to the repo so CI produces a stable, sideloadable signature and
        // app updates install over previous versions. Fine for a personal project; do NOT reuse this
        // key for anything published to Google Play.
        create("sideload") {
            storeFile = rootProject.file("keystore/sideload.jks")
            storePassword = "sideload"
            keyAlias = "sideload"
            keyPassword = "sideload"
        }
    }

    buildTypes {
        release {
            // Minification off so the personal sideload APK works reliably without R8 stripping
            // MapLibre/Compose/serialization internals. Re-enable with tested keep rules for a store build.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
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
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.play.services.location)

    // Android Auto (Android for Cars App Library)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)

    // Offline base map + Android Auto surface fallback renderer (Phase 6)
    implementation(libs.maplibre)

    implementation(libs.okhttp)
    implementation(libs.androidx.webkit)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
