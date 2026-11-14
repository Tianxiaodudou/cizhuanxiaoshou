/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/theme/Theme.kt
 * @role Material 3 主题：青玉主色 + 暖石灰背景，与网页端视觉一致；颜色/形状统一从这里取。
 * @exports JiancaiTheme
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.android.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 品牌色：青玉（主）+ 陶土（辅）+ 暖石灰背景，避免默认模板感。 */
private val Jade = Color(0xFF0F766E)
private val JadeLight = Color(0xFF5FD3C8)
private val Clay = Color(0xFFB4532A)
private val Danger = Color(0xFFB42318)

private val LightColors = lightColorScheme(
    primary = Jade,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F0EC),
    onPrimaryContainer = Color(0xFF07332F),
    secondary = Clay,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBEDE6),
    onSecondaryContainer = Color(0xFF3F1C0C),
    tertiary = Color(0xFFB08A4F),
    error = Danger,
    background = Color(0xFFFAF8F5),
    onBackground = Color(0xFF16181C),
    surface = Color(0xFFFFFDF9),
    onSurface = Color(0xFF16181C),
    surfaceVariant = Color(0xFFF1ECE3),
    onSurfaceVariant = Color(0xFF3D4450),
    outline = Color(0xFFD6CEC2),
    outlineVariant = Color(0xFFE7E1D8),
)

private val DarkColors = darkColorScheme(
    primary = JadeLight,
    onPrimary = Color(0xFF00332E),
    primaryContainer = Color(0xFF0B4F49),
    onPrimaryContainer = Color(0xFFD7F0EC),
    secondary = Color(0xFFE7A98A),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE8E6E1),
    surface = Color(0xFF161C22),
    onSurface = Color(0xFFE8E6E1),
    surfaceVariant = Color(0xFF2A3138),
    outline = Color(0xFF4A545C),
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
)

/** Material 3 主题：颜色/排版/形状统一从这里取，页面不写死颜色。 */
@Composable
fun JiancaiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        typography = Typography(),
        content = content,
    )
}
