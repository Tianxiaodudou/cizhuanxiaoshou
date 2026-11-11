/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/order/DeliveryEntryPage.kt
 * @role 3. 配送送货录入（复用 OrderFormComponent）
 * @exports DeliveryEntryPage
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.android.app.ui.order

import androidx.compose.runtime.Composable
import com.jiancai.android.app.data.AppContainer

/**
 * 3. 配送送货录入（复用 OrderFormComponent）
 *
 * 页面差异只有 [OrderKind]，表单实现集中在 [OrderFormComponent]（禁止复制表单）。
 */
@Composable
fun DeliveryEntryPage(container: AppContainer, onBack: () -> Unit) {
    OrderFormComponent(container = container, kind = OrderKind.DELIVERY, onBack = onBack)
}
