/*
 * @file android-app/core-network/src/main/kotlin/com/jiancai/android/network/ServerAddress.kt
 * @role 服务端地址（客户端可改，见 C04 登录页）。
 * @exports ServerAddress, ServerAddressStore, InMemoryServerAddressStore
 * @depends （无）
 * @calledBy android-app/app/src/main/kotlin/com/jiancai/android/app/data/AppContainer.kt, android-app/app-shell/src/main/kotlin/com/jiancai/android/shell/NavGraph.kt, android-app/core-network/src/main/kotlin/com/jiancai/android/network/HttpApiClient.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.android.network

/**
 * 服务端地址（客户端可改，见 C04 登录页）。
 *
 * 归一化规则：必须是 `http(s)://host[:port][/prefix]`，去掉首尾空白与末尾 `/`；
 * 不做任何「自动补 http://」这类猜测（猜错的地址会让门店以为服务端挂了）。
 *
 * @property value 归一化后的地址（不带末尾 `/`）
 */
@JvmInline
value class ServerAddress private constructor(val value: String) {

    /** 拼上接口路径后的完整 URL 前缀。 */
    fun base(): String = value

    override fun toString(): String = value

    companion object {
        /** 默认地址（门店内网部署时在登录页改成实际地址）。 */
        const val DEFAULT: String = "http://127.0.0.1:8080"

        private val PATTERN = Regex("""^https?://[^\s/]+(?::\d{1,5})?(/[^\s]*)?$""")

        /** 解析并归一化；非法地址抛 [IllegalArgumentException]（由设置页就地提示）。 */
        fun parse(raw: String): ServerAddress {
            val trimmed = raw.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "服务端地址不能为空" }
            require(PATTERN.matches(trimmed)) { "服务端地址格式非法：$raw" }
            return ServerAddress(trimmed)
        }
    }
}

/**
 * 服务端地址存储：接口在这里，实现由组合根决定（Android 侧用 DataStore / SharedPreferences）。
 *
 * C00 只读 [baseUrl]；只有 C04 设置页通过 [update] 修改。
 */
interface ServerAddressStore {
    /** 当前地址（一定是归一化后的）。 */
    fun baseUrl(): String

    /** 保存新地址并返回归一化结果；非法地址抛 [IllegalArgumentException]。 */
    fun update(raw: String): ServerAddress
}

/**
 * 进程内实现（测试 / 预览用；真实 Android 用持久化实现替换）。
 */
class InMemoryServerAddressStore(
    initial: String = ServerAddress.DEFAULT,
) : ServerAddressStore {

    @Volatile
    private var current: ServerAddress = ServerAddress.parse(initial)

    override fun baseUrl(): String = current.base()

    override fun update(raw: String): ServerAddress {
        val parsed = ServerAddress.parse(raw)
        current = parsed
        return parsed
    }
}
