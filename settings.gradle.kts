// 建材进销存 —— 正式 Gradle 根工程
//
// 目录即模块，每个目录都有自己的 build.gradle.kts；插件版本只在根 build.gradle.kts 声明。
//
//   shared/           SHARED 契约层（两端唯一真源：DTO / 枚举 / 错误码 / 单号前缀 / 单位）
//   fpk-server/       服务端聚合工程（只聚合，不含代码）
//   fpk-server/*      服务端各模块：db-core(S00) · config(S01) · auth(S02) · account(S03) · serial(S07)

pluginManagement {
    repositories {
        // Kotlin 插件及其依赖从插件门户取，其余依赖走各模块声明的 mavenCentral。
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "jiancai"

include(":shared")

include(":fpk-server")
include(":fpk-server:db-core")
include(":fpk-server:config")
include(":fpk-server:auth")
include(":fpk-server:account")
include(":fpk-server:serial")
