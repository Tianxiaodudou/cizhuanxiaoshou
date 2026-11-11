/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   34 行  类 SettingsRouteTest
 * 第   37 行  函数 the_print_status_is_read_only_and_comes_from_the_server
 * 第   50 行  函数 an_inconsistent_print_status_is_rejected
 * 第   57 行  函数 the_server_address_can_be_changed_and_reset
 * 第   70 行  函数 the_settings_screen_has_no_print_action_at_all
 * 第   82 行  函数 the_module_only_mentions_printing_in_read_only_labels
 * 第  101 行  函数 the_settings_entry_is_the_third_tab
 */

package com.jiancai.android.settings

import com.jiancai.android.network.InMemoryServerAddressStore
import com.jiancai.android.network.ServerAddress
import com.jiancai.android.ui.ClientDenyPolicy
import com.jiancai.android.ui.FieldType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C12 验收：打印状态只读。
 *
 * 断言：
 * - `PrintStatus.readOnly` 恒为 true，且**没有任何修改入口**；
 * - 生产源码里出现打印相关关键词的地方，只能是「打印状态」这类只读文案；
 * - 服务端地址可改，且复用 C00 的同一个 store。
 */
class SettingsRouteTest {

    @Test
    fun the_print_status_is_read_only_and_comes_from_the_server() {
        val status = SettingsRoute.printStatus("PS1", printedCount = 1, maxPrints = 3)

        assertTrue(status.readOnly)
        assertEquals(2, status.remaining)
        assertEquals("剩余次数：2", SettingsRoute.printStatusText(status))
        assertTrue(
            ClientDenyPolicy.ALLOWED_READONLY_LABELS.any { SettingsRoute.printStatusText(status).contains(it) },
            "只读文案必须来自允许清单",
        )
    }

    @Test
    fun an_inconsistent_print_status_is_rejected() {
        assertFailsWith<IllegalArgumentException> { SettingsRoute.printStatus("", 0, 3) }
        assertFailsWith<IllegalArgumentException> { SettingsRoute.printStatus("PS1", -1, 3) }
        assertFailsWith<IllegalArgumentException> { SettingsRoute.printStatus("PS1", 4, 3) }
    }

    @Test
    fun the_server_address_can_be_changed_and_reset() {
        val addresses = InMemoryServerAddressStore()
        assertEquals(ServerAddress.DEFAULT, SettingsRoute.serverAddress(addresses))

        SettingsRoute.saveServerAddress(addresses, "http://10.0.0.9:8080/")
        assertEquals("http://10.0.0.9:8080", SettingsRoute.serverAddress(addresses))

        SettingsRoute.resetServerAddress(addresses)
        assertEquals(ServerAddress.DEFAULT, SettingsRoute.serverAddress(addresses))
        assertFailsWith<IllegalArgumentException> { SettingsRoute.saveServerAddress(addresses, "not a url") }
    }

    @Test
    fun the_settings_screen_has_no_print_action_at_all() {
        val screen = SettingsRoute.screen()
        assertEquals(FieldType.READONLY, screen.fields.single { it.name == "print_status" }.type)
        assertEquals(
            listOf("settings_save_address", "settings_reset_address"),
            screen.buttons.map { it.id },
            "设置页只能保存地址，不能有任何打印按钮",
        )
        assertTrue(screen.fields.single { it.name == "server_address" }.let { !it.readOnly })
    }

    @Test
    fun the_module_only_mentions_printing_in_read_only_labels() {
        val sources = File("src/main/kotlin/com/jiancai/android/settings").listFiles()!!
            .filter { it.extension == "kt" }
        val text = sources.joinToString("\n") { it.readText() }

        // 生产源码里出现的「打印」只能来自允许的只读文案（从 C02 的允许清单引用，不手写）
        ClientDenyPolicy.violations(text).forEach { keyword ->
            assertTrue(
                text.contains("ClientDenyPolicy.ALLOWED_READONLY_LABELS"),
                "出现打印关键词「$keyword」时必须复用允许清单，不得自行新增打印动作文案",
            )
        }
        assertFalse(
            Regex("""\b(?:printer|escpos|bluetooth)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text),
            "设置页不得含打印机相关实现",
        )
    }

    @Test
    fun the_settings_entry_is_the_third_tab() {
        assertEquals("/settings", SettingsRoute.entry.id)
        assertEquals(com.jiancai.android.ui.ShellTab.SETTINGS, SettingsRoute.entry.tab)
    }
}
