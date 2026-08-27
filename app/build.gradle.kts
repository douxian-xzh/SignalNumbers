plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xinsu.signalnumbers"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xinsu.signalnumbers"
        minSdk = 31
        targetSdk = 36
        versionCode = 39
        versionName = "1.0.38"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    compileOnly(project(":xposed-stubs"))
}
