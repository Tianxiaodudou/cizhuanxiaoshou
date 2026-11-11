/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   44 行  对象 SelectOrderPage
 * 第   48 行  函数 search
 * 第   62 行  函数 select
 * 第   66 行  函数 screen
 * 第   82 行  对象 NoteRoute
 * 第   94 行  函数 create
 * 第  120 行  函数 void
 * 第  127 行  函数 screen
 * 第  142 行  函数 invalid
 */

package com.jiancai.android.note

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiPaths
import com.jiancai.android.network.ApiResult
import com.jiancai.android.ui.AppRoutes
import com.jiancai.android.ui.ButtonAction
import com.jiancai.android.ui.ButtonSpec
import com.jiancai.android.ui.FieldSpec
import com.jiancai.android.ui.FieldType
import com.jiancai.android.ui.RouteEntry
import com.jiancai.android.ui.ScreenSpec
import com.jiancai.android.ui.UiButton
import com.jiancai.shared.dto.CreateNoteCmd
import com.jiancai.shared.dto.DEFAULT_PAGE_SIZE
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.PageDto
import com.jiancai.shared.dto.VoidNoteCmd
import com.jiancai.shared.enums.DiffType
import com.jiancai.shared.errors.ErrorCode

/**
 * 选择原单页（C09 的子页）：按电话找到要关联的订单。
 *
 * 与 C07 的区别：这里**只读选择**，不展示金额编辑；选定后把原单号交给 [NoteRoute]。
 */
object SelectOrderPage {

    /** 按电话搜索候选原单（服务端聚合接口）。 */
    @UiButton(id = "select_order_search", label = "查找原单")
    fun search(client: ApiClient, phone: String): ApiResult<PageDto<OrderDto>> {
        val normalized = phone.trim()
        if (normalized.isEmpty()) {
            return ApiResult.Failure(
                wireCode = ErrorCode.E422.name,
                code = ErrorCode.E422,
                message = "请输入客户电话",
                httpStatus = 422,
            )
        }
        return client.call(ApiPaths.orders(OrderQuery(phone = normalized, pageSize = DEFAULT_PAGE_SIZE)))
    }

    /** 从结果里选中一张单（不存在返回 null，由界面提示）。 */
    fun select(page: PageDto<OrderDto>, orderNo: String): OrderDto? =
        page.items.firstOrNull { it.orderNo == orderNo }

    /** 选择页规格。 */
    fun screen(): ScreenSpec = ScreenSpec(
        id = "${AppRoutes.NOTE}/select-order",
        title = "选择原单",
        fields = listOf(FieldSpec("phone", "客户电话", FieldType.PHONE)),
        buttons = listOf(ButtonSpec("select_order_search", "查找原单", ButtonAction.Submit)),
    )
}

/**
 * C09 差异备注单。
 *
 * 硬边界（验收「备注单正确关联原单」+「不修改原单」）：
 * - 备注单只**引用**原单号（`CreateNoteCmd.sourceOrderNo`），本模块没有任何改单调用；
 * - 差异金额是用户填写的对账事实（服务端校验格式并落库），客户端只做格式搬运，不做加总；
 * - 作废只影响备注单自身，原单不被触碰。
 */
object NoteRoute {

    /** 路由登记。 */
    val entry: RouteEntry = RouteEntry(id = AppRoutes.NOTE, title = "差异备注")

    /**
     * 创建备注单。
     *
     * @param diffAmountText 用户输入的差异金额（可正可负，两位小数）；服务端规范化后落库
     * @param clientRequestId 幂等键（同一次提交重试复用它）
     */
    @UiButton(id = "note_submit", label = "提交备注单")
    fun create(
        client: ApiClient,
        sourceOrderNo: String,
        diffType: DiffType,
        diffAmountText: String,
        remark: String,
        clientRequestId: String,
    ): ApiResult<NoteDto> {
        if (sourceOrderNo.isBlank()) return invalid("请先选择原单")
        if (remark.isBlank()) return invalid("请填写差异说明")
        val amount = runCatching { Money.ofPlain(diffAmountText) }.getOrElse { return invalid("差异金额格式非法") }
        return client.call(
            ApiPaths.createNote(
                cmd = CreateNoteCmd(
                    sourceOrderNo = sourceOrderNo.trim(),
                    diffType = diffType,
                    diffAmount = amount,
                    remark = remark.trim(),
                ),
                clientRequestId = clientRequestId,
            ),
        )
    }

    /** 作废备注单（只作废、不删除、不改原单）。 */
    @UiButton(id = "note_void", label = "作废备注单")
    fun void(client: ApiClient, noteNo: String, reason: String): ApiResult<NoteDto> {
        if (noteNo.isBlank()) return invalid("缺少备注单号")
        if (reason.isBlank()) return invalid("请填写作废原因")
        return client.call(ApiPaths.voidNote(VoidNoteCmd(noteNo = noteNo.trim(), reason = reason.trim())))
    }

    /** 备注单屏规格。 */
    fun screen(): ScreenSpec = ScreenSpec(
        id = entry.id,
        title = entry.title,
        fields = listOf(
            FieldSpec("source_order_no", "原单号", FieldType.READONLY, readOnly = true, required = false),
            FieldSpec("diff_type", "差异类型", FieldType.SELECT),
            FieldSpec("diff_amount", "差异金额", FieldType.DECIMAL),
            FieldSpec("remark", "差异说明", FieldType.MULTILINE),
        ),
        buttons = listOf(
            ButtonSpec("note_submit", "提交备注单", ButtonAction.Submit),
            ButtonSpec("note_void", "作废备注单", ButtonAction.Submit),
        ),
    )

    private fun invalid(message: String): ApiResult.Failure = ApiResult.Failure(
        wireCode = ErrorCode.E422.name,
        code = ErrorCode.E422,
        message = message,
        httpStatus = 422,
    )
}
