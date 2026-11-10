// S05 · category：品类 / 型号库 CRUD（仅管理员，权限由 S13 api-gateway 拦截）+ 批号类型
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:category")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / db-core 一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // CategoryDto / CategoryCmd / PackingSpec / PricingUnit / 错误码出现在 Port 签名上，按 api 暴露。
    api(project(":shared"))
    // TxContext / TxRunner 出现在 Port 签名上：写操作必须复用调用方事务（铁律 4）。
    api(project(":fpk-server:db-core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
