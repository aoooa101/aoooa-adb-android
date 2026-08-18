plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aoooa.webadb"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aoooa.webadb"
        minSdk = 24
        targetSdk = 35
        // CI 可通过 VERSION_CODE 环境变量覆盖（epoch 秒），保证单调递增
        versionCode = (System.getenv("VERSION_CODE")?.toIntOrNull()) ?: 21
        versionName = "0.1.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // assets 目录映射（网页 UI + ADB 库 bundle）
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
