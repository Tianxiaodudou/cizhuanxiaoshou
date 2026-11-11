/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/theme/Theme.kt
 * @role Material 3 主题：颜色/排版/形状统一从这里取，页面不写死颜色。
 * @exports JiancaiTheme
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.android.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 品牌色：建材门店用高对比蓝 + 中性灰，深色模式单独一套。 */
private val BrandBlue = Color(0xFF1565C0)
private val BrandBlueDark = Color(0xFF90CAF9)
private val Danger = Color(0xFFC62828)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001C3B),
    secondary = Color(0xFF455A64),
    error = Danger,
    background = Color(0xFFF7F8FA),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF004A80),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFB0BEC5),
    error = Color(0xFFFF8A80),
    background = Color(0xFF101418),
    surface = Color(0xFF161C22),
)

/** Material 3 主题：颜色/排版/形状统一从这里取，页面不写死颜色。 */
@Composable
fun JiancaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
