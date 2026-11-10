// SHARED · 契约层：两端唯一真源（DTO / 枚举 / 错误码 / 单号前缀 / 单位与装箱规格）
// 插件版本由根 build.gradle.kts 统一声明，本模块只 apply、不写版本；
// 根 settings.gradle.kts 需 include(":shared")，两端以 api(project(":shared")) 引用。
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// 允许根工程用 gradle.properties 覆盖序列化运行时版本，避免两端各写一套。
// 最低要求 1.4.0：Money 使用显式 KSerializer，剩余 API（Json / SerialName / PrimitiveSerialDescriptor）均已稳定。
val kotlinxSerializationVersion: String =
    providers.gradleProperty("kotlinxSerializationVersion").getOrElse("1.7.3")

kotlin {
    // Android 与 JVM 服务端都可消费的字节码级别（Android 支持 Java 17）。
    // 需要本机 JDK 17，或由根工程配置 foojay-resolver 自动下载 toolchain。
    jvmToolchain(17)
}

dependencies {
    // 契约层对两者暴露：DTO 的线格式序列化。
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
