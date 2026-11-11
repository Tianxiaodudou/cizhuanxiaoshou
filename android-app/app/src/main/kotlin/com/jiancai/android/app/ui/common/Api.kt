/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/common/Api.kt
 * @role 失败统一文案：错误码 + 服务端消息（客户端不另编提示）。
 * @exports callIo, display
 * @depends core-network
 * @calledBy （无）
 * @sideEffects 网络请求（HTTP）
 */

package com.jiancai.android.app.ui.common

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiRequestSpec
import com.jiancai.android.network.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 把 C00 的阻塞调用切到 IO 线程（页面用 rememberCoroutineScope 调）。 */
suspend fun <T> ApiClient.callIo(spec: ApiRequestSpec<T>): ApiResult<T> =
    withContext(Dispatchers.IO) { call(spec) }

/** 失败统一文案：错误码 + 服务端消息（客户端不另编提示）。 */
fun ApiResult.Failure.display(): String = buildString {
    append(wireCode ?: "网络错误")
    if (message.isNotBlank()) append("：").append(message)
}
