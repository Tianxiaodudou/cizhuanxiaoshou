// 客户端 Android 应用：Jetpack Compose + Material 3（页面与规范见 docs/specs/android-compose.md）
plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 版本号来自仓库根 VERSION（与 FPK 同一份，发版时由 workflow 递增）
val appVersion: String = rootProject.file("VERSION").readText().trim()
val appVersionCode: Int = appVersion.split('.').let { parts ->
    (parts.getOrNull(0)?.toIntOrNull() ?: 1) * 10000 +
        (parts.getOrNull(1)?.toIntOrNull() ?: 0) * 100 +
        (parts.getOrNull(2)?.toIntOrNull() ?: 0)
}

val localKeystore = rootProject.file(".tools/android-user/debug.keystore")
android {
    namespace = "com.jiancai.android.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jiancai.android.app"
        minSdk = 26
        targetSdk = 34
        versionName = appVersion
        versionCode = appVersionCode
    }

    signingConfigs {
        // 固定发布签名：CI 通过环境变量注入 keystore（GitHub Secrets），本机不保存密钥。
        val relStore = System.getenv("CZXS_KEYSTORE")
        if (!relStore.isNullOrBlank()) {
            create("release") {
                storeFile = file(relStore)
                storeType = "PKCS12"
                storePassword = System.getenv("CZXS_STORE_PASSWORD")
                keyAlias = System.getenv("CZXS_KEY_ALIAS")
                keyPassword = System.getenv("CZXS_KEY_PASSWORD")
            }
        }
        // 本机 sandbox 不允许写 ~/.android；仓库内 debug keystore 存在时用它。
        if (localKeystore.exists()) {
            create("localDebug") {
                storeFile = localKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val relStore = System.getenv("CZXS_KEYSTORE")
            if (!relStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (localKeystore.exists()) signingConfig = signingConfigs.getByName("localDebug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":android-app:core-network"))
    implementation(project(":android-app:core-session"))
    implementation(project(":android-app:core-ui"))
    implementation(project(":android-app:feature-order-entry"))

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
