/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/common/Components.kt
 * @role 统一页面骨架：标题栏（可返回）+ 滚动内容 + 可选底栏。
 * @exports PageScaffold, KeyValueRow, SectionLabel, AppTextField, LoadingRow, ErrorText, MoneyText, display, EmptyHint, PageColumn
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.android.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 统一页面骨架：标题栏（可返回）+ 滚动内容 + 可选底栏。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { inner ->
        val modifier = Modifier
            .fillMaxWidth()
            .padding(inner)
            .verticalScroll(rememberScrollState())
        content(modifier)
    }
}

/** 一行“标签 + 值”，用于客户、金额、只读信息。 */
@Composable
fun KeyValueRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** 标题小字。 */
@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** 统一样式输入框（带说明 / 错误）。 */
@Composable
fun AppTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    readOnly: Boolean = false,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        readOnly = readOnly,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 加载中。 */
@Composable
fun LoadingRow(message: String = "加载中…") {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Text(message, modifier = Modifier.padding(start = 12.dp))
    }
}

/** 错误提示（服务端文案原样展示）。 */
@Composable
fun ErrorText(message: String) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** 金额展示：只拼接服务端字符串，不做任何换算。 */
@Composable
fun MoneyText(amount: String, sign: String = "", emphasize: Boolean = true) {
    Text(
        "$sign$amount",
        style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** 把 null 安全的服务端字段转成展示串。 */
fun display(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "—"

/** 简单空态。 */
@Composable
fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

/** 页面内边距（统一 16dp）。 */
val PagePadding = 16.dp

/** 供页面使用的列容器。 */
@Composable
fun PageColumn(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(modifier = modifier.padding(PagePadding), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}
