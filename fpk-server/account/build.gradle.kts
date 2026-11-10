// S03 · account：账号 CRUD（仅管理员，权限由 S13 api-gateway 拦截）
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:account")。
plugins {
    kotlin("jvm")
}

kotlin {
    // 与 shared / db-core / auth 一致：JVM 17 字节码。
    jvmToolchain(17)
}

dependencies {
    // AccountDto / CreateAccountCmd / UpdateAccountCmd 出现在 Port 签名上，按 api 暴露。
    api(project(":shared"))
    // TxContext / TxRunner 出现在 Port 签名上：账号写操作复用调用方事务（铁律 4）。
    api(project(":fpk-server:db-core"))
    // 注意：本模块**不**编译期依赖 S02 auth。账号表归 S02，S03 只声明自己需要的
    // AccountStore 能力，由组合根的适配器接到 S02 的 Port（见 module.md「与 S02 的分工」）。
    // 这样既不会出现 S03 → auth → S03 的循环，也不可能从本模块写出 auth_account 的 SQL。
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
