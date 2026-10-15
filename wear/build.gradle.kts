plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ahmad.wristnote"
    compileSdk = 35

    defaultConfig {
        // Must match the phone app: the Wear Data Layer only talks between
        // apps with the same package name and signing key.
        applicationId = "com.ahmad.wristnote"
        minSdk = 30
        targetSdk = 35
        versionCode = 11
        versionName = "3.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.jks")
            storePassword = "wristnote"
            keyAlias = "wristnote"
            keyPassword = "wristnote"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
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
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // tile + watch-face complication
    implementation("androidx.wear.tiles:tiles:1.4.0")
    implementation("androidx.wear.protolayout:protolayout:1.2.0")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
}
