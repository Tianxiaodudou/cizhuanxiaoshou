// 客户端 Android 应用（真机验证用）：纯系统控件实现，不引入 AndroidX / Compose，降低构建风险。
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.jiancai.android.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jiancai.android.app"
        minSdk = 26
        targetSdk = 34
        // 版本号来自仓库根 VERSION（与 FPK 同一份，发版时由 workflow 递增）
        versionName = rootProject.file("VERSION").readText().trim()
        versionCode = versionName.split('.').let { parts ->
            (parts[0].toIntOrNull() ?: 1) * 10000 +
                (parts.getOrNull(1)?.toIntOrNull() ?: 0) * 100 +
                (parts.getOrNull(2)?.toIntOrNull() ?: 0)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation(project(":shared"))
    implementation(project(":android-app:core-network"))
    implementation(project(":android-app:core-session"))
    implementation(project(":android-app:core-ui"))
    implementation(project(":android-app:feature-order-entry"))
}
