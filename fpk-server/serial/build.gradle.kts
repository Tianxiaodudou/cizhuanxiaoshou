// S07 · serial：单号生成（前缀 + 年 + 月日 + 全局序号），事务取号 + 唯一约束兜底
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:serial")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / db-core 一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // SerialType（单号前缀唯一真源）出现在 Port 签名上，按 api 暴露。
    api(project(":shared"))
    // TxContext / TxRunner 出现在 Port 签名上：取号必须复用调用方事务。
    api(project(":fpk-server:db-core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
