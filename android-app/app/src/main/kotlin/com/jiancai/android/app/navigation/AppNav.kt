/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/navigation/AppNav.kt
 * @role 路由表（页面与文件映射见 docs/specs/android-compose.md）。
 * @exports Routes, MainBottomBar, AppNav, NoBottomBar
 * @depends core-session
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.android.app.navigation

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jiancai.android.app.data.AppContainer
import com.jiancai.android.app.ui.home.HomePage
import com.jiancai.android.app.ui.login.LoginPage
import com.jiancai.android.app.ui.note.NotePage
import com.jiancai.android.app.ui.note.SelectOrderPage
import com.jiancai.android.app.ui.order.DeliveryEntryPage
import com.jiancai.android.app.ui.order.DeliveryReturnPage
import com.jiancai.android.app.ui.order.PickupEntryPage
import com.jiancai.android.app.ui.order.PickupReturnPage
import com.jiancai.android.app.ui.query.QueryPage
import com.jiancai.android.app.ui.settings.SettingsPage
import com.jiancai.android.app.ui.today.TodayOrderEditPage
import com.jiancai.android.app.ui.today.TodayOrdersPage
import com.jiancai.android.session.LaunchDestination

/** 路由表（页面与文件映射见 docs/specs/android-compose.md）。 */
object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val QUERY = "query"
    const val TODAY = "today"
    const val SETTINGS = "settings"
    const val NOTE = "note"
    const val SELECT_ORDER = "selectOrder"
    const val DELIVERY_ENTRY = "order/delivery"
    const val DELIVERY_RETURN = "order/deliveryReturn"
    const val PICKUP_ENTRY = "order/pickup"
    const val PICKUP_RETURN = "order/pickupReturn"
    const val TODAY_EDIT = "today/edit/{orderNo}"

    fun todayEdit(orderNo: String): String = "today/edit/$orderNo"
}

private val TABS = listOf(
    Triple(Routes.QUERY, "查询", Icons.Filled.Search),
    Triple(Routes.TODAY, "改当日单", Icons.Filled.Edit),
    Triple(Routes.SETTINGS, "设置", Icons.Filled.Settings),
)

/** 底栏三 tab（查询 / 改当日单 / 设置）。 */
@Composable
fun MainBottomBar(currentRoute: String?, nav: NavHostController) {
    NavigationBar(modifier = Modifier.navigationBarsPadding()) {
        TABS.forEach { (route, label, icon) ->
            NavigationBarItem(
                selected = currentRoute == route,
                onClick = {
                    if (currentRoute != route) {
                        nav.navigate(route) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label) },
            )
        }
    }
}

/**
 * 导航入口：登录守卫在这里统一拦截（未登录只进登录页）。
 *
 * 页面之间只传业务 id（单号 / 类型），不传 token；token 由 C01 注入。
 */
@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val start = if (container.session.destination() == LaunchDestination.HOME) Routes.HOME else Routes.LOGIN
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.LOGIN) {
            LoginPage(container) {
                nav.navigate(Routes.HOME) { popUpTo(0) }
            }
        }
        composable(Routes.HOME) {
            HomePage(
                container = container,
                onDeliveryEntry = { nav.navigate(Routes.DELIVERY_ENTRY) },
                onDeliveryReturn = { nav.navigate(Routes.DELIVERY_RETURN) },
                onPickupEntry = { nav.navigate(Routes.PICKUP_ENTRY) },
                onPickupReturn = { nav.navigate(Routes.PICKUP_RETURN) },
                onNote = { nav.navigate(Routes.NOTE) },
                bottomBar = { MainBottomBar(current, nav) },
            )
        }
        composable(Routes.DELIVERY_ENTRY) { DeliveryEntryPage(container, nav::popBackStack) }
        composable(Routes.DELIVERY_RETURN) { DeliveryReturnPage(container, nav::popBackStack) }
        composable(Routes.PICKUP_ENTRY) { PickupEntryPage(container, nav::popBackStack) }
        composable(Routes.PICKUP_RETURN) { PickupReturnPage(container, nav::popBackStack) }
        composable(Routes.QUERY) {
            QueryPage(container, bottomBar = { MainBottomBar(current, nav) })
        }
        composable(Routes.TODAY) {
            TodayOrdersPage(
                container = container,
                onEdit = { orderNo -> nav.navigate(Routes.todayEdit(orderNo)) },
                bottomBar = { MainBottomBar(current, nav) },
            )
        }
        composable(Routes.TODAY_EDIT) { entry ->
            val orderNo = entry.arguments?.getString("orderNo").orEmpty()
            TodayOrderEditPage(container, orderNo, nav::popBackStack)
        }
        composable(Routes.NOTE) {
            NotePage(
                container = container,
                onPickOriginal = { nav.navigate(Routes.SELECT_ORDER) },
                onBack = nav::popBackStack,
            )
        }
        composable(Routes.SELECT_ORDER) {
            SelectOrderPage(
                container = container,
                onSelected = { order ->
                    container.selectedOrder = order
                    nav.popBackStack()
                },
                onBack = nav::popBackStack,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsPage(
                container = container,
                onLoggedOut = { nav.navigate(Routes.LOGIN) { popUpTo(0) } },
                bottomBar = { MainBottomBar(current, nav) },
            )
        }
    }
}

/** 空底栏占位（不需要 tab 的页面用）。 */
@Composable
fun NoBottomBar(modifier: Modifier = Modifier) = Unit
