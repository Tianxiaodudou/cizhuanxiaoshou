/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   34 行  类 UiKitTest
 * 第   37 行  函数 the_same_screen_component_describes_three_different_features
 * 第   67 行  函数 duplicate_button_ids_are_rejected
 * 第   81 行  函数 a_read_only_field_cannot_be_required
 * 第   88 行  函数 the_ui_button_annotation_is_retained_at_runtime_for_the_scan_script
 * 第   97 行  函数 print_related_keywords_are_denied_everywhere
 * 第  108 行  函数 the_theme_centralises_spacing_and_typography
 * 第  115 行  函数 the_component_library_does_not_import_any_business_module
 * 第  129 行  函数 a_screen_spec_can_carry_a_route_entry_for_the_shell
 * 第  137 行  类 SampleScreen
 * 第  139 行  函数 submit
 */

package com.jiancai.android.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C02 验收：组件可复用。
 *
 * 判定标准：
 * 1. 同一套 `ScreenSpec` 能描述不同业务的屏（本测试用「下单 / 查询 / 设置」三种屏证明）；
 * 2. 组件库不 import 任何业务模块；
 * 3. 打印相关关键词全局 deny（[ClientDenyPolicy] 是唯一关键词真源）。
 */
class UiKitTest {

    @Test
    fun the_same_screen_component_describes_three_different_features() {
        val orderEntry = ScreenSpec(
            id = "/orders/new",
            title = "采购下单",
            buttons = listOf(
                ButtonSpec("submit", "提交", ButtonAction.Submit),
                ButtonSpec("cancel", "取消", ButtonAction.Cancel),
            ),
            fields = listOf(
                FieldSpec("phone", "客户电话", FieldType.PHONE),
                FieldSpec("amount", "金额", FieldType.READONLY, readOnly = true, required = false),
            ),
        )
        val query = ScreenSpec(
            id = "/query",
            title = "历史查询",
            buttons = listOf(ButtonSpec("search", "查询", ButtonAction.Submit)),
        )
        val settings = ScreenSpec(
            id = "/settings",
            title = "设置",
            buttons = listOf(ButtonSpec("save", "保存", ButtonAction.Submit)),
        )

        assertEquals(3, listOf(orderEntry, query, settings).map { it.id }.distinct().size)
        assertTrue(orderEntry.fields.single { it.name == "amount" }.readOnly, "服务端产出字段必须只读")
        assertEquals(ShellTab.entries.size, 3, "底栏固定 3 个 tab")
    }

    @Test
    fun duplicate_button_ids_are_rejected() {
        assertFailsWith<IllegalArgumentException> {
            ScreenSpec(
                id = "/x",
                title = "x",
                buttons = listOf(
                    ButtonSpec("same", "A", ButtonAction.Submit),
                    ButtonSpec("same", "B", ButtonAction.Submit),
                ),
            )
        }
    }

    @Test
    fun a_read_only_field_cannot_be_required() {
        assertFailsWith<IllegalArgumentException> {
            FieldSpec("total", "总额", FieldType.READONLY, readOnly = true)
        }
    }

    @Test
    fun the_ui_button_annotation_is_retained_at_runtime_for_the_scan_script() {
        assertEquals(AnnotationRetention.RUNTIME, UiButton::class.java.getAnnotation(Retention::class.java)!!.value)
        assertEquals(
            "submit",
            SampleScreen::class.java.getDeclaredMethod("submit").getAnnotation(UiButton::class.java).id,
        )
    }

    @Test
    fun print_related_keywords_are_denied_everywhere() {
        listOf("打印小票", "重新打印", "Print preview", "补打", "escpos").forEach { text ->
            assertTrue(ClientDenyPolicy.violations(text).isNotEmpty(), "应命中：$text")
        }
        listOf("提交订单", "历史查询", "结算上传", "今日改单").forEach { text ->
            assertTrue(ClientDenyPolicy.violations(text).isEmpty(), "不应命中：$text")
        }
        assertFailsWith<IllegalArgumentException> { ClientDenyPolicy.requireClean("打印小票", "测试页") }
    }

    @Test
    fun the_theme_centralises_spacing_and_typography() {
        assertTrue(Theme.BUTTON_HEIGHT_DP >= 48, "按钮不能低于通用可点高度")
        assertTrue(Theme.SPACING_SMALL_DP < Theme.SPACING_NORMAL_DP)
        assertTrue(Theme.FONT_HINT_SP < Theme.FONT_BODY_SP && Theme.FONT_BODY_SP < Theme.FONT_TITLE_SP)
    }

    @Test
    fun the_component_library_does_not_import_any_business_module() {
        val sources = File("src/main/kotlin/com/jiancai/android/ui").listFiles()!!
            .filter { it.extension == "kt" }
        assertTrue(sources.isNotEmpty())

        sources.forEach { source ->
            val imports = source.readLines().filter { it.trim().startsWith("import ") }.joinToString("\n")
            assertFalse(imports.contains("com.jiancai.shared.dto"), "${source.name} 不得耦合业务 DTO")
            assertFalse(imports.contains("com.jiancai.android.network"), "${source.name} 不得依赖网络层")
            assertFalse(imports.contains("feature"), "${source.name} 不得依赖任何 feature")
        }
    }

    @Test
    fun a_screen_spec_can_carry_a_route_entry_for_the_shell() {
        val entry = RouteEntry(id = "/orders/new", title = "采购下单", tab = null)
        assertTrue(entry.requiresLogin, "业务页默认需要登录")
        assertEquals(ShellTab.TODAY.label, "今日")
    }
}

/** 只为验证注解保留策略的样例屏。 */
private class SampleScreen {
    @UiButton(id = "submit", label = "提交")
    fun submit() = Unit
}
