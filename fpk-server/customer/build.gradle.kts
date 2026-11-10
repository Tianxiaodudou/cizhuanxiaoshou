// S04 · customer：客户建档、按电话查询、地址快照来源
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:customer")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / db-core 一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // CustomerDto / CreateCustomerCmd 出现在 Port 签名上，按 api 暴露。
    api(project(":shared"))
    // TxContext / TxRunner 出现在 Port 签名上：客户写入复用调用方事务（铁律 4）。
    api(project(":fpk-server:db-core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
