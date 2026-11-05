plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.koike.plamotranslate"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.koike.plamotranslate"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.7"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DLLAMA_CPP_DIR=${file("../../llama.cpp").absolutePath}"
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
        }
    }

    signingConfigs {
        create("plamotranslate") {
            storeFile = file("${rootDir}/plamotranslate.keystore")
            storePassword = "plamotranslate"
            keyAlias = "plamotranslate"
            keyPassword = "plamotranslate"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("plamotranslate")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("plamotranslate")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
