plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ghostbroot.app"
    // Spec §1: minSdk 26 (Android 8.0). compileSdk/targetSdk = current stable
    // compatible with AGP 8.5.2 + JDK 17 in CI.
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ghostbroot.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Spec §6: release signing ONLY via CI secrets (env). No keys in repo.
    // Without KEYSTORE_PATH the release build stays unsigned (CI build job);
    // release.yml refuses to publish in that state.
    // v1+v2 signing keeps the APK installable on older OEM parsers too.
    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }
    val hasSigning = !System.getenv("KEYSTORE_PATH").isNullOrBlank()
    if (hasSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(System.getenv("KEYSTORE_PATH"))
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    // Spec §7: multi-ABI, no native libraries required (pure Kotlin/Java).
    // Splits produce one APK per ABI, plus a universal APK so any Android
    // device can install without matching the filename to its ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = false }
}

dependencies {
    // Runtime: deliberately ZERO third-party libraries.
    // HTTP + WebSocket are implemented on java.net.ServerSocket (loopback
    // only) and JSON via org.json (built into Android). This keeps the APK
    // portable across manufacturers, ABI-independent and free of native code.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
