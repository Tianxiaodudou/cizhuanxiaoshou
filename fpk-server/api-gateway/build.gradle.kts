// S13 路 api-gateway：路由 / 权限拦截 / 错误码映射 / 幂等键校验（对客户端暴露的唯一入口）
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:api-gateway")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / db-core / 其它模块一致：JVM 17 字节码（JDK 自带 com.sun.net.httpserver，无需外部依赖）。
    jvmToolchain(17)
}

dependencies {
    // 网关的线格式（ErrorResponse / ErrorCode / 各 Port 的 DTO 与 Cmd）全部来自 shared。
    api(project(":shared"))
    // TxRunner：改价留痕 / 审核留痕 / 退单校验 + 建单要求同一事务（铁律 4）。
    api(project(":fpk-server:db-core"))
    api(project(":fpk-server:config"))
    api(project(":fpk-server:auth"))
    api(project(":fpk-server:account"))
    api(project(":fpk-server:customer"))
    api(project(":fpk-server:category"))
    api(project(":fpk-server:pricing"))
    api(project(":fpk-server:serial"))
    api(project(":fpk-server:order"))
    api(project(":fpk-server:return-check"))
    api(project(":fpk-server:note"))
    api(project(":fpk-server:settlement"))
    api(project(":fpk-server:print"))
    // S14：改价 / 审核留痕（卡上未列，但「留痕」必须由网关在业务成功后调用；可选依赖，可为 null）。
    api(project(":fpk-server:observability"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
