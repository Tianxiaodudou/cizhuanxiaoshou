// S08 · order：订单抬头/明细、幂等、乐观锁、改当日单、应收派生
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:order")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / db-core / 其它业务模块一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // OrderDto / CreateOrderCmd / UpdateOrderCmd / OrderQuery / Money 出现在 Port 签名上，按 api 暴露。
    api(project(":shared"))
    // TxContext / TxRunner 出现在 Port 签名上：订单写入必须复用调用方事务（铁律 4）。
    api(project(":fpk-server:db-core"))
    // S07：单号只能经 SerialPort 获取（铁律 8）。
    api(project(":fpk-server:serial"))
    // S06：单价只能经 PricePort.resolve 解析；PriceChangeListener 是价格重算的入站 Port。
    api(project(":fpk-server:pricing"))
    // S04 / S05：客户与型号快照只经各自的 Port 读取（铁律 3）。
    api(project(":fpk-server:customer"))
    api(project(":fpk-server:category"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
