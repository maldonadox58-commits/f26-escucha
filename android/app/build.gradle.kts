plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.f26.escucha"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.f26.escucha"
        minSdk = 24
        targetSdk = 34
        versionCode = 18
        versionName = "18"
    }

    signingConfigs {
        create("f26") {
            storeFile = file("../f26.keystore")
            storePassword = "f26escucha"
            keyAlias = "f26"
            keyPassword = "f26escucha"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("f26")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
