/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/order/DeliveryReturnPage.kt
 * @role 4. 配送退货录入（复用 OrderFormComponent，批次从可退列表选）
 * @exports DeliveryReturnPage
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.android.app.ui.order

import androidx.compose.runtime.Composable
import com.jiancai.android.app.data.AppContainer

/**
 * 4. 配送退货录入（复用 OrderFormComponent，批次从可退列表选）
 *
 * 页面差异只有 [OrderKind]，表单实现集中在 [OrderFormComponent]（禁止复制表单）。
 */
@Composable
fun DeliveryReturnPage(container: AppContainer, onBack: () -> Unit) {
    OrderFormComponent(container = container, kind = OrderKind.DELIVERY_RETURN, onBack = onBack)
}
