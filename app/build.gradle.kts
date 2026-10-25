plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.biliuvc.live"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.biliuvc.live"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "1.0.12"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // The bundled keystore only makes the release APK installable for testing.
            // Replace it with your own keystore before publishing.
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")

    // Vendored UVC stack: saki4510t UVCCamera + libusb + libuvc + libjpeg-turbo (arm64-v8a / armeabi-v7a).
    // libuvc.aar has RUNTIME dependencies on libuvccommon.aar (HandlerThreadHandler/BuildCheck/...)
    // and com.elvishew:xlog (used by com.serenegiant.utils.XLogWrapper); they must be packaged too,
    // otherwise USBMonitor() throws NoClassDefFoundError and no USB camera is ever detected.
    implementation(files("libs/libuvc.aar"))
    implementation(files("libs/libuvccommon.aar"))
    implementation("com.elvishew:xlog:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
