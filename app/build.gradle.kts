plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.chihiro.tasklinker"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["appLabel"] = "TaskClient"
    }
    buildFeatures {
        aidl = true
    }

    // 3 个 flavor = 3 个 applicationId 不同的 App，可同时安装、同时连接服务端，
    // 用于验证"多客户端并发连接 + 服务端广播到全部客户端"
    flavorDimensions += "client"
    productFlavors {
        create("clientA") {
            dimension = "client"
            applicationId = "com.tasklinker.clienta"
            manifestPlaceholders["appLabel"] = "TaskClient A"
        }
        create("clientB") {
            dimension = "client"
            applicationId = "com.tasklinker.clientb"
            manifestPlaceholders["appLabel"] = "TaskClient B"
        }
        create("clientC") {
            dimension = "client"
            applicationId = "com.tasklinker.clientc"
            manifestPlaceholders["appLabel"] = "TaskClient C"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":tasklinker-api"))
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
}
