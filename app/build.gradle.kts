import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aoooa.adb"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aoooa.adb"
        minSdk = 24
        targetSdk = 35
        // 可通过 VERSION_CODE 环境变量覆盖版本号
        versionCode = (System.getenv("VERSION_CODE")?.toIntOrNull()) ?: 2
        versionName = "2.6.2"

        // 仅构建 arm64-v8a 架构
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags("")
                abiFilters("arm64-v8a")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                val kFile = file(keystorePath)
                if (kFile.exists() && kFile.length() > 500) {
                    storeFile = kFile
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")

                    // 仅启用 APK Signature Scheme v2 与 v3
                    enableV1Signing = false
                    enableV2Signing = true
                    enableV3Signing = true
                    enableV4Signing = false
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        compose = true
    }
}

// Release/Debug 产物命名：aoooa-adb-<versionName>-arm64-v8a.apk
// CI 仍按 app/build/outputs/apk/**/*.apk 通配上传，无需改 workflow。
android.applicationVariants.configureEach {
    val ver = versionName ?: android.defaultConfig.versionName ?: "0"
    outputs.configureEach {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
            .outputFileName = "aoooa-adb-${ver}-arm64-v8a.apk"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.conscrypt:conscrypt-android:2.6.0")

    // Shizuku API
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Unit & Dynamic Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
