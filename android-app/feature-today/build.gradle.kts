// 客户端模块 feature-today（阶段 4）：本机无 Android SDK，按任务卡允许的降级方式用 JVM 单测验证（见 module.md）
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":android-app:feature-today")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / 服务端模块一致：JVM 17 字节码（真实 Android 工程会换成 android library 插件）。
    jvmToolchain(17)
}

dependencies {
    api(project(":shared"))
    api(project(":android-app:core-network"))
    api(project(":android-app:core-ui"))
    api(project(":android-app:feature-order-entry"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
