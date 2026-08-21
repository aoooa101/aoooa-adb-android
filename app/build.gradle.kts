import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aoooa.webadb"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aoooa.webadb"
        minSdk = 24
        targetSdk = 35
        // CI 可通过 VERSION_CODE 环境变量覆盖（epoch 秒），保证单调递增
        versionCode = (System.getenv("VERSION_CODE")?.toIntOrNull()) ?: 22
        versionName = "2.0.1"

        // NDK 限制：专门针对现代 64 位手机适配，只生成 arm64-v8a 架构
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
            initWith(getByName("debug"))
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                val kFile = file(keystorePath)
                if (kFile.exists() && kFile.length() > 500) {
                    storeFile = kFile
                    storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "aoooa101@123Aa"
                    keyAlias = System.getenv("KEY_ALIAS") ?: "aoooa"
                    keyPassword = System.getenv("KEY_PASSWORD") ?: "aoooa101@123Aa"
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

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Compose（原生 UI）
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.conscrypt:conscrypt-android:2.6.0")
}
