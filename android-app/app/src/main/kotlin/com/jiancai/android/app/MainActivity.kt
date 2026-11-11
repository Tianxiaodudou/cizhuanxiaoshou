/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/MainActivity.kt
 * @role 单 Activity 入口：只做 edge-to-edge 与 setContent，页面全部由 Compose 导航承载。
 * @exports MainActivity
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jiancai.android.app.data.AppContainer
import com.jiancai.android.app.navigation.AppNav
import com.jiancai.android.app.ui.theme.JiancaiTheme

/** 单 Activity 入口：只做 edge-to-edge 与 setContent，页面全部由 Compose 导航承载。 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = AppContainer(this)
        setContent {
            JiancaiTheme {
                AppNav(container)
            }
        }
    }
}
