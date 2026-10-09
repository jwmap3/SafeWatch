plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.safewatch.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.safewatch.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // The Claude key built into the download, so family phones have it without typing it. It is read from the
        // CLAUDE_KEY build secret (never kept in the code); when the secret is unset the field is empty.
        val claudeKey = System.getenv("CLAUDE_KEY") ?: (project.findProperty("CLAUDE_KEY") as String?) ?: ""
        buildConfigField("String", "CLAUDE_KEY", "\"$claudeKey\"")
    }

    // The test build is always signed with the key kept in this folder, so a
    // newer download installs over the one already on the phone.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    // Two apps from the same code: edenOS itself, and CleanChrome, its browser on its own.
    flavorDimensions += "app"
    productFlavors {
        create("edenos") { dimension = "app" }
        create("cleanchrome") {
            dimension = "app"
            applicationId = "com.safewatch.cleanchrome"
            // Phones only (64-bit ARM, as every recent phone is), so the download is a third the size.
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    buildFeatures { buildConfig = true }

    // The video-checking engine's library is stored compressed, so the download is much smaller.
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
