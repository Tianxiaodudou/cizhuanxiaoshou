// S10 · note：差异备注单（关联原单、不改原单、作废）
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:note")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / db-core / 其它业务模块一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // NoteDto / CreateNoteCmd / VoidNoteCmd 出现在 Port 签名上，按 api 暴露。
    api(project(":shared"))
    // TxContext / TxRunner 出现在 Port 签名上：备注单写入必须复用调用方事务（铁律 4）。
    api(project(":fpk-server:db-core"))
    // S08：原单只经 OrderPort 读取（校验存在 + 客户快照），禁止直连订单表（铁律 3 / 10）。
    api(project(":fpk-server:order"))
    // S07：备注单号只能经 SerialPort 取号（铁律 8：不自行拼接前缀 / 序号）。
    api(project(":fpk-server:serial"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
