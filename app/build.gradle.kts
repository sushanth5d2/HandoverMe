plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.handoverme.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.handoverme.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 14
        versionName = "5.3.1"
    }

    // JDK 25 may cause Kotlin to fall back to JVM 22. Explicitly target JVM 17
    // for both Java and Kotlin so Gradle sees identical bytecode targets.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.biometric:biometric:1.1.0")
}
