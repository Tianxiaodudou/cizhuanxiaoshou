// S12 · print：80mm 打印渲染（五列双行、装箱换算、打印次数）
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:print")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / db-core / 其它业务模块一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // PrintDocumentDto 里出现 Money / DocType / LineDto 等契约类型，按 api 暴露。
    api(project(":shared"))
    // TxContext / TxRunner 出现在 Port 签名上：打印次数与渲染必须复用调用方事务（铁律 4）。
    api(project(":fpk-server:db-core"))
    // S08：订单只经 OrderPort 读取，禁止直接访问订单表（铁律 3 / 10）。
    api(project(":fpk-server:order"))
    // S01：商家名称只经 ConfigPort 读取。
    api(project(":fpk-server:config"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
