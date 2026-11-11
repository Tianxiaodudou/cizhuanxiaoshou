// 组合根：唯一装配 S00–S07 各模块的地方（不属于任何业务模块，也不含业务规则）。
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:app")。
plugins {
    kotlin("jvm")
    application
}

kotlin {
    // 与 shared / db-core 一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // 对外暴露的 Port 类型（ConfigPort / AuthPort / AccountAdminPort / SerialPort / AccountStorePort）
    // 出现在 App 的签名上，按 api 暴露；调用方（S13）只需依赖本模块。
    api(project(":shared"))
    api(project(":fpk-server:db-core"))
    api(project(":fpk-server:config"))
    api(project(":fpk-server:auth"))
    api(project(":fpk-server:account"))
    api(project(":fpk-server:serial"))
    // 阶段二 / 阶段三模块：组合根把它们装配成常驻 HTTP 服务（GatewayMain）。
    api(project(":fpk-server:customer"))
    api(project(":fpk-server:category"))
    api(project(":fpk-server:pricing"))
    api(project(":fpk-server:order"))
    api(project(":fpk-server:return-check"))
    api(project(":fpk-server:note"))
    api(project(":fpk-server:settlement"))
    api(project(":fpk-server:print"))
    api(project(":fpk-server:observability"))
    api(project(":fpk-server:api-gateway"))
    testImplementation(kotlin("test"))
}

application {
    // 启动自检入口（迁移 + 装配 + 首次管理员引导）；HTTP 常驻服务由 S13 提供。
    mainClass.set("com.jiancai.app.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// 常驻 HTTP 网关入口（真机 / 客户端联调用；一次性自检仍是 :fpk-server:app:run）
tasks.register<JavaExec>("runGateway") {
    group = "application"
    description = "启动 S13 常驻 HTTP 网关（0.0.0.0:8080，可用 JIANCAI_HTTP_HOST/PORT 覆盖）"
    mainClass.set("com.jiancai.app.GatewayMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}
