/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/ui/note/NotePage.kt
 * @role 10. 备注单：必须先选原单（通过选择页回填摘要），差异类型 / 金额 / 说明提交服务端。
 * @exports NotePage
 * @depends core-network, shared
 * @calledBy （无）
 * @sideEffects 无（纯计算 / 转发）
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jiancai.android.app.ui.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiancai.android.app.data.AppContainer
import com.jiancai.android.app.ui.common.AppTextField
import com.jiancai.android.app.ui.common.ErrorText
import com.jiancai.android.app.ui.common.KeyValueRow
import com.jiancai.android.app.ui.common.PagePadding
import com.jiancai.android.app.ui.common.SectionLabel
import com.jiancai.android.app.ui.common.callIo
import com.jiancai.android.app.ui.common.display
import com.jiancai.android.network.ApiPaths
import com.jiancai.android.network.ApiResult
import com.jiancai.shared.dto.CreateNoteCmd
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.enums.DiffType
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 10. 备注单：必须先选原单（通过选择页回填摘要），差异类型 / 金额 / 说明提交服务端。
 *
 * 服务端备注单是单条差异模型（`CreateNoteCmd`），暂不做逐行差异（见 docs/specs）。
 */
@Composable
fun NotePage(
    container: AppContainer,
    onPickOriginal: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val original = container.selectedOrder
    var diffType by remember { mutableStateOf(DiffType.PRICE) }
    var amount by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<NoteDto?>(null) }
    var diffMenu by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("备注单") }) }) { inner ->
        Column(
            Modifier.fillMaxWidth().padding(inner).padding(PagePadding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionLabel("原单")
            OutlinedButton(onClick = onPickOriginal, modifier = Modifier.fillMaxWidth()) {
                Text(if (original == null) "选择原单" else "重新选择原单")
            }
            original?.let { order ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        KeyValueRow("单号", order.orderNo)
                        KeyValueRow("客户", order.customerName)
                        KeyValueRow("类型", order.orderType.label)
                        KeyValueRow("合计", order.totalAmount.display())
                    }
                }
            }

            SectionLabel("差异")
            OutlinedButton(onClick = { diffMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(diffType.label)
            }
            DropdownMenu(expanded = diffMenu, onDismissRequest = { diffMenu = false }) {
                DiffType.entries.forEach { type ->
                    DropdownMenuItem(text = { Text(type.label) }, onClick = {
                        diffMenu = false
                        diffType = type
                    })
                }
            }
            AppTextField("差异金额（两位小数，可负）", amount, { amount = it })
            AppTextField("差异说明", remark, { remark = it })

            error?.let { ErrorText(it) }
            created?.let { note ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("备注单已创建", style = MaterialTheme.typography.titleSmall)
                        KeyValueRow("备注单号", note.noteNo)
                        KeyValueRow("原单", note.sourceOrderNo)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("取 消") }
                Button(
                    onClick = {
                        val order = original ?: run {
                            error = "请先选择原单"
                            return@Button
                        }
                        if (remark.isBlank()) {
                            error = "请填写差异说明"
                            return@Button
                        }
                        val money = runCatching { Money.ofPlain(amount) }.getOrElse {
                            error = "差异金额格式非法"
                            return@Button
                        }
                        error = null
                        loading = true
                        scope.launch {
                            val result = container.client.callIo(
                                ApiPaths.createNote(
                                    cmd = CreateNoteCmd(
                                        sourceOrderNo = order.orderNo,
                                        diffType = diffType,
                                        diffAmount = money,
                                        remark = remark,
                                    ),
                                    clientRequestId = "android-note-" + UUID.randomUUID(),
                                ),
                            )
                            loading = false
                            when (result) {
                                is ApiResult.Success -> created = result.value
                                is ApiResult.Failure -> error = result.display()
                            }
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.weight(1f),
                ) { Text(if (loading) "提交中…" else "提交打印") }
            }
        }
    }
}
