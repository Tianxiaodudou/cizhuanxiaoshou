// S09 · return-check：可退数量计算、批次校验（规则独立、只读、不写任何表）
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:return-check")。
plugins {
    kotlin("jvm")
    // 注意：本模块不再声明 @Serializable —— 线格式 DTO 自 1.1.0 起全部由 shared 持有，
    // 因此不需要 serialization 编译插件（运行时随 shared 的 api 依赖传递）。
}

kotlin {
    // 与 shared / db-core / 其它业务模块一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // OrderDto / OrderLineCmd / Quantity / BatchTagRules / ReturnCheck*Dto / ErrorCode 全在 shared（唯一真源）。
    api(project(":shared"))
    // TxContext / TxRunner 出现在 Port 签名上：本模块的读必须能并入调用方事务（铁律 4）。
    api(project(":fpk-server:db-core"))
    // S08 是本模块唯一的业务依赖：原单、退货单只经 OrderPort 读取（铁律 3）。
    // 禁止再加 S04 / S05 / S06 / S07：批次与型号口径由 S08 的 DTO 带来，本模块不再回查主数据。
    api(project(":fpk-server:order"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
