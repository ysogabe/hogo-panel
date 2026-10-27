plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ysogabe.hogo.panel"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ysogabe.hogo.panel"
        minSdk = 30
        targetSdk = 35
        versionCode = 8
        versionName = "0.5.0"
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
    testImplementation("junit:junit:4.13.2")
}
