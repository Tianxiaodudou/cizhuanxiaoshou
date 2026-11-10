// 建材进销存 —— 根构建脚本
//
// 只做三件事：声明插件版本、统一坐标、给所有模块挂仓库。
// 具体编译 / 依赖 / 测试配置都留在各模块自己的 build.gradle.kts 里（目录即模块）。

plugins {
    // 版本只在这里写一次；模块侧只 `kotlin("jvm")` / `kotlin("plugin.serialization")`，不带版本。
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

allprojects {
    group = "com.jiancai"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}
