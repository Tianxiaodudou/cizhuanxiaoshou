/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/data/AppContainer.kt
 * @role 应用级依赖容器（组合根）：服务端地址、HTTP 客户端、登录态。
 * @exports AppContainer
 * @depends core-network, core-session, shared
 * @calledBy android-app/app/src/main/kotlin/com/jiancai/android/app/MainActivity.kt, android-app/app/src/main/kotlin/com/jiancai/android/app/navigation/AppNav.kt, android-app/app/src/main/kotlin/com/jiancai/android/app/ui/home/HomePage.kt
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jiancai.android.app.AndroidTransport
import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.HttpApiClient
import com.jiancai.android.network.ServerAddress
import com.jiancai.android.network.ServerAddressStore
import com.jiancai.android.network.TokenProvider
import com.jiancai.android.session.SessionGate
import com.jiancai.shared.dto.AccountDto

/**
 * 应用级依赖容器（组合根）：服务端地址、HTTP 客户端、登录态。
 *
 * - 地址持久化在 SharedPreferences（改完立即生效，重启保留）；
 * - token 只由 [SessionGate] 持有，页面拿不到（C01 单点）；
 * - 页面只通过 [client] + [ApiPaths] 调服务端（C00 统一注入 token / 解析错误码）。
 */
class AppContainer(context: Context) {

    private val prefs = context.getSharedPreferences("jiancai", Context.MODE_PRIVATE)

    val addresses: ServerAddressStore = PrefsAddressStore(prefs)

    private val bridge = TokenBridge()

    val client: ApiClient = HttpApiClient(addresses, bridge, AndroidTransport())

    val session: SessionGate = SessionGate(client).also { bridge.session = it }

    /** 当前登录账号（未登录为 null），只用于界面展示。 */
    fun account(): AccountDto? = (session.state() as? com.jiancai.android.session.SessionState.Authenticated)?.account

    /** 选原单页回填（页面间只传这一份 UI 状态，不进网络层）。 */
    var selectedOrder: com.jiancai.shared.dto.OrderDto? by androidx.compose.runtime.mutableStateOf(null)
}

/** C00 需要的 token 提供者，只把请求转给 C01。 */
private class TokenBridge : TokenProvider {
    var session: SessionGate? = null
    override fun token(): String? = session?.token()
}

/** 服务端地址存储（SharedPreferences，键 `server_address`）。 */
private class PrefsAddressStore(private val prefs: android.content.SharedPreferences) : ServerAddressStore {

    override fun baseUrl(): String = prefs.getString(KEY, null) ?: ServerAddress.DEFAULT

    override fun update(raw: String): ServerAddress {
        val parsed = ServerAddress.parse(raw)
        prefs.edit().putString(KEY, parsed.value).apply()
        return parsed
    }

    private companion object {
        const val KEY = "server_address"
    }
}
