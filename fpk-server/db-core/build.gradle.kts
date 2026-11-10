// S00 · db-core：唯一 SQLite 连接 / 事务（TxRunner）/ 迁移（Migrator）/ :memory: 测试库
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":fpk-server:db-core")（或聚合工程下的 "db-core"）。
plugins {
    kotlin("jvm")
}

// 驱动版本可被根工程 gradle.properties 的 sqliteJdbcVersion 覆盖，避免各模块各写一套。
val sqliteJdbcVersion: String =
    providers.gradleProperty("sqliteJdbcVersion").getOrElse("3.46.1.3")

kotlin {
    // 与 shared 一致：JVM/Android 都能消费的字节码级别（需要 JDK 17，或根工程配 foojay-resolver）。
    jvmToolchain(17)
}

dependencies {
    // 只有 db-core 认识 SQLite 驱动；其它模块只依赖 Port 接口，运行时由本模块传递。
    runtimeOnly("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
