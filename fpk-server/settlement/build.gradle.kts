// S11 · settlement：结算记录、当场判定、审核、已收/未收读模型
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:settlement")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / db-core / 其它业务模块一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // SettleDto / SubmitSettleCmd / AuditSettleCmd / ReceivableDto 出现在 Port 签名上，按 api 暴露。
    api(project(":shared"))
    // TxContext / TxRunner 出现在 Port 签名上：结算写入必须复用调用方事务（铁律 4）。
    api(project(":fpk-server:db-core"))
    // S08：订单应收 / 订单快照只经 OrderPort 读取，禁止直连订单表（铁律 3 / 10）。
    api(project(":fpk-server:order"))
    // S03：操作者存在性只经 AccountAdminPort 校验（不判权限，权限由 S13 拦截）。
    api(project(":fpk-server:account"))
    // S07：结算单号只能经 SerialPort 取号（铁律 8：不自行拼接前缀 / 序号）。
    api(project(":fpk-server:serial"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
