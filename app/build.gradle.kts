plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pannulabs.schoolprayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pannulabs.schoolprayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // One fixed key for every build, so a new APK installs as an update over the old one
    // (Android refuses updates signed with a different key).
    signingConfigs {
        create("school") {
            storeFile = file("school-prayer.keystore")
            storePassword = "schoolprayer"
            keyAlias = "schoolprayer"
            keyPassword = "schoolprayer"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("school")
        }
        release {
            signingConfig = signingConfigs.getByName("school")
            isMinifyEnabled = false
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}
