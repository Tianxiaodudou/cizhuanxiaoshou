// S06 · pricing：恒定价格（客户 + 型号 + 批号）、当日可改、跨日锁、管理员改价留痕
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:pricing")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / db-core / customer / category 一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // PriceDto / ChangePriceCmd / PriceChangeLog / Money / ErrorCode 出现在 Port 签名上，按 api 暴露。
    api(project(":shared"))
    // TxContext / TxRunner 出现在 Port 签名上：写操作必须复用调用方事务（铁律 4）。
    api(project(":fpk-server:db-core"))
    // S04 / S05 的 Port 出现在 PriceService 构造参数上；组合根需要它们的类型来装配。
    api(project(":fpk-server:customer"))
    api(project(":fpk-server:category"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
