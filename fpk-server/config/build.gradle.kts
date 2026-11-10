// S01 · config：部署级商家名称 / 部署配置（客户端不可传，只能读）
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:config")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / db-core 一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // ConfigDto 属于 shared 契约，不在本模块重复定义。
    api(project(":shared"))
    // ConfigService 的构造器接收 TxRunner（事务 Port），按 api 暴露给组合根。
    api(project(":fpk-server:db-core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
