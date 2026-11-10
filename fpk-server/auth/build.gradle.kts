// S02 · auth：登录、token 签发与校验、账号密码（密码只存 PBKDF2 哈希）
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:auth")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / db-core 一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // LoginCmd / LoginResultDto / AccountDto / 错误码出现在 Port 签名上，按 api 暴露。
    api(project(":shared"))
    // TxContext / TxRunner 出现在 Port 签名上：登录写操作复用调用方事务。
    api(project(":fpk-server:db-core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
