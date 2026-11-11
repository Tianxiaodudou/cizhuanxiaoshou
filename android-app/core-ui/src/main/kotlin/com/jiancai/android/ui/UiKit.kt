/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   36 行  类 UiButton
 * 第   41 行  接口 ButtonAction
 * 第   44 行  类 Navigate
 * 第   47 行  对象 Submit
 * 第   50 行  对象 Cancel
 * 第   53 行  对象 Refresh
 * 第   64 行  类 ButtonSpec
 * 第   76 行  类 ScreenSpec
 * 第   92 行  类 FieldType
 * 第   99 行  类 FieldSpec
 * 第  115 行  类 ShellTab
 * 第  129 行  类 RouteEntry
 * 第  163 行  对象 ClientDenyPolicy
 * 第  210 行  函数 violations
 * 第  216 行  函数 requireClean
 * 第  228 行  对象 AppRoutes
 * 第  242 行  函数 orderEntry
 */

package com.jiancai.android.ui

/**
 * 按钮注解：给「界面上的可点区域」一个稳定的 id 与文案，供按钮扫描脚本（SCAN）核对。
 *
 * 为什么用注解而不是散落的字符串：扫描脚本要能**静态**列出全部按钮，并且发现
 * 「打印 / 重新打印」这类客户端禁止的动作（铁律 6）。
 *
 * @property id 稳定 id（扫描与埋点用，禁止重复）
 * @property label 中文文案（展示用）
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class UiButton(val id: String, val label: String)

/**
 * 按钮动作（客户端只有这几种「意图」，真正的业务结果一律由服务端返回）。
 */
sealed interface ButtonAction {

    /** 跳到某个路由（路由 id 见 C03 的 `Routes`）。 */
    data class Navigate(val routeId: String) : ButtonAction

    /** 提交当前表单（调聚合接口）。 */
    data object Submit : ButtonAction

    /** 取消 / 返回。 */
    data object Cancel : ButtonAction

    /** 重新拉取（列表刷新）。 */
    data object Refresh : ButtonAction
}

/**
 * 按钮规格（组件库的最小单元）。
 *
 * @property id 稳定 id（同一屏内唯一）
 * @property label 文案
 * @property action 点击意图
 * @property enabled 是否可点（禁用态由调用方按服务端状态决定，客户端不做业务判断）
 */
data class ButtonSpec(
    val id: String,
    val label: String,
    val action: ButtonAction,
    val enabled: Boolean = true,
)

/**
 * 一屏的规格：标题 + 按钮集合。
 *
 * 组件库只描述「长什么样、点了去哪里」，不 import 任何业务模块（验收「组件可复用」）。
 */
data class ScreenSpec(
    val id: String,
    val title: String,
    val buttons: List<ButtonSpec> = emptyList(),
    val fields: List<FieldSpec> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "ScreenSpec.id 不能为空" }
        val duplicates = buttons.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "同一屏按钮 id 重复：$duplicates" }
    }
}

/**
 * 输入框类型（客户端只负责键盘 / 校验提示，不负责业务换算）。
 */
enum class FieldType { TEXT, PHONE, DECIMAL, DATE, SELECT, MULTILINE, READONLY }

/**
 * 输入框规格。
 *
 * @property readOnly 只读（展示服务端产出的金额 / 单号时使用，铁律 5）
 */
data class FieldSpec(
    val name: String,
    val label: String,
    val type: FieldType,
    val readOnly: Boolean = false,
    val required: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "FieldSpec.name 不能为空" }
        require(!(readOnly && required)) { "只读字段不应标记必填：$name" }
    }
}

/**
 * 底栏三个 tab（验收「底栏 3 tab」的唯一真源；C03 与各 feature 共用）。
 */
enum class ShellTab(val label: String) {
    HOME("首页"),
    TODAY("今日"),
    SETTINGS("设置"),
}

/**
 * 一条路由的登记信息（各 feature 暴露，C03 汇总；feature 之间不互相 import UI）。
 *
 * @property id 路由 id（如 `/orders/new`）
 * @property title 标题
 * @property tab 归属的底栏 tab；null = 不在底栏
 * @property requiresLogin 是否需要登录（真正的判断由 C01 的 `SessionGate.destination()` 做）
 */
data class RouteEntry(
    val id: String,
    val title: String,
    val tab: ShellTab? = null,
    val requiresLogin: Boolean = true,
)

/**
 * 主题（间距 / 字号 / 颜色都集中在这里，界面不得就地写魔法数字）。
 */object Theme {
    const val PRIMARY_COLOR: String = "#1565C0"
    const val DANGER_COLOR: String = "#C62828"
    const val SURFACE_COLOR: String = "#FFFFFF"

    const val FONT_TITLE_SP: Int = 20
    const val FONT_BODY_SP: Int = 16
    const val FONT_HINT_SP: Int = 13

    const val SPACING_SMALL_DP: Int = 8
    const val SPACING_NORMAL_DP: Int = 16
    const val SPACING_LARGE_DP: Int = 24

    /** 按钮最小可点高度（门店戴手套操作，不能太小）。 */
    const val BUTTON_HEIGHT_DP: Int = 56
}

/**
 * 客户端全局 deny 策略：**打印相关关键词一律禁止出现在客户端**（验收「打印关键词 deny」）。
 *
 * 依据：铁律 6「打印只在服务端（S12）」。客户端只能展示「已打印 / 次数」这类只读状态，
 * 不能出现打印按钮、打印预览、补打入口，也不能直接或间接连打印机。
 *
 * 这里同时是 SCAN 扫描脚本的唯一关键词真源（脚本读这个文件，不另写一份列表）。
 */
object ClientDenyPolicy {

    /** 禁止出现在客户端源码 / 文案里的关键词（大小写不敏感）。 */
    val FORBIDDEN_KEYWORDS: List<String> = listOf(
        "打印",
        "补打",
        "重打",
        "打印预览",
        "打印设置",
        "print",
        "printer",
        "reprint",
        "escpos",
        "bluetooth printer",
    )

    /** 允许的「只读打印状态」文案（这些是展示，不是动作）。 */
    val ALLOWED_READONLY_LABELS: List<String> = listOf(
        "打印状态",
        "已打印次数",
        "剩余次数",
    )

    /**
     * 允许出现的**只读标识符**（服务端渲染票据的读取与打印次数展示）。
     *
     * 扫描脚本会先剔除这些标识符，再检查关键词；因此「printTicket / PrintStatus」这类
     * 只读取数代码不算违规，而「打印小票 / printer / escpos」会被拦下。
     */
    val ALLOWED_IDENTIFIERS: List<String> = listOf(
        "/print/",
        "printTicket",
        "printStatusText",
        "printStatus",
        "PrintStatus",
        "printedCount",
        "printCount",
        "maxPrints",
        "printedAt",
        "printableWidthMm",
    )

    /**
     * 检查一段文本里出现的违规关键词。
     *
     * @return 命中的关键词（空列表 = 干净）
     */
    fun violations(text: String): List<String> {
        val lowered = text.lowercase()
        return FORBIDDEN_KEYWORDS.filter { keyword -> lowered.contains(keyword.lowercase()) }
    }

    /** 违规即抛（测试 / 构建期扫描用）。 */
    fun requireClean(text: String, where: String = "客户端") {
        val hits = violations(text)
        require(hits.isEmpty()) { "$where 出现被禁止的打印相关关键词：$hits（打印只在服务端 S12）" }
    }
}

/**
 * 路由 id 目录（**id 的唯一真源**）：feature 只引用这里的常量，C03 把它们装配成导航图。
 *
 * 为什么不放在 C03：feature 引用 C03 会形成环（C03 依赖全部 feature）。
 * C02 是纯粹的路由 id 目录，不含任何装配逻辑，因此两端都能引用。
 */
object AppRoutes {
    const val LOGIN: String = "/login"
    const val HOME: String = "/home"
    const val QUERY: String = "/query"
    const val TODAY: String = "/today"
    const val NOTE: String = "/note"
    const val SETTLE: String = "/settle"
    const val ADMIN: String = "/admin"
    const val SETTINGS: String = "/settings"

    /** 四类下单录入页共用同一个路由，只带不同的 `orderType`。 */
    const val ORDER_ENTRY: String = "/orders/new"

    /** 录入页路由 + 单据类型（`orderType` 是 `shared` 枚举的线值）。 */
    fun orderEntry(orderTypeWire: String): String = "$ORDER_ENTRY?type=$orderTypeWire"
}
