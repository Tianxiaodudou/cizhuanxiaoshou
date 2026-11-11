// 根构建脚本：只声明插件版本、统一坐标、给所有模块挂仓库；具体配置留在各模块。
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("com.android.application") version "8.7.3" apply false
}

allprojects {
    group = "com.jiancai"
    version = rootProject.file("VERSION").readText().trim()

    repositories {
        mavenCentral()
        google()
    }
}
