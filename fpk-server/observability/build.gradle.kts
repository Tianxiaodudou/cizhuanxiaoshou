// S14 路 observability：日志 / 审计留痕（改价、审核），只追加、不参与业务决策
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:observability")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / db-core / 其它业务模块一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // PriceChangeLog / SettleDto / WireFormat / ErrorCode 全在 shared（唯一真源）。
    api(project(":shared"))
    // TxContext / TxRunner / Migration 出现在 Port 与迁移签名上：留痕必须并回调用方事务（铁律 4）。
    api(project(":fpk-server:db-core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
