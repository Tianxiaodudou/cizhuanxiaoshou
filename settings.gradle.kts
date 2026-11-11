// 建材进销存 —— 正式 Gradle 根工程
//
// 目录即模块，每个目录都有自己的 build.gradle.kts；插件版本只在根 build.gradle.kts 声明。
//
//   shared/           SHARED 契约层（两端唯一真源：DTO / 枚举 / 错误码 / 单号前缀 / 单位）
//   fpk-server/       服务端聚合工程（只聚合，不含代码）
//   fpk-server/*      服务端各模块：db-core(S00) · config(S01) · auth(S02) · account(S03) · serial(S07)
//   fpk-server/app    组合根（唯一装配点：合并迁移 + 账号存储适配器 + 启动自检）

pluginManagement {
    repositories {
        // Kotlin 插件及其依赖从插件门户取，其余依赖走各模块声明的 mavenCentral。
        gradlePluginPortal()
        mavenCentral()
        // Android Gradle Plugin 只在 google() 仓库里。
        google()
    }
}

rootProject.name = "jiancai"

include(":shared")

include(":fpk-server")
include(":fpk-server:db-core")
include(":fpk-server:config")
include(":fpk-server:auth")
include(":fpk-server:account")
include(":fpk-server:category")
include(":fpk-server:serial")
include(":fpk-server:customer")
include(":fpk-server:pricing")
include(":fpk-server:order")
include(":fpk-server:return-check")
include(":fpk-server:note")
include(":fpk-server:settlement")
include(":fpk-server:print")
include(":fpk-server:observability")
include(":fpk-server:api-gateway")
include(":fpk-server:app")

// 客户端（阶段 4）：本机无 Android SDK，按任务卡允许的方式退化为 JVM 模块 + JVM 单测。
include(":android-app")
include(":android-app:core-network")
include(":android-app:core-session")
include(":android-app:core-ui")
include(":android-app:app-shell")
include(":android-app:feature-login")
include(":android-app:feature-home")
include(":android-app:feature-order-entry")
include(":android-app:feature-query")
include(":android-app:feature-today")
include(":android-app:feature-note")
include(":android-app:feature-settle")
include(":android-app:feature-admin")
include(":android-app:feature-settings")
include(":android-app:app")

// czxs v1.1 新服务端（FPK appname=czxs，网关 /app/czxs）
include(":fpk-server:czxs")
