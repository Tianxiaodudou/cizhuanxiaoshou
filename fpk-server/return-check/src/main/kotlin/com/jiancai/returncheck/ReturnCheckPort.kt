/*
 * @file fpk-server/return-check/src/main/kotlin/com/jiancai/returncheck/ReturnCheckPort.kt
 * @role 退单校验 Port：S09 唯一对外入口（可退批次查询、退单申请校验）。
 * @exports ReturnCheckPort
 * @depends db-core, shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayDeps.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt, fpk-server/return-check/src/main/kotlin/com/jiancai/returncheck/ReturnCheckService.kt
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.returncheck

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.dto.ReturnCheckResultDto
import com.jiancai.shared.dto.ReturnableBatchDto

/**
 * 退单校验 Port：S09 唯一对外入口（可退批次查询、退单申请校验）。
 *
 * 契约口径（contractVersion 1.1.0）：
 * - **只读**：本模块不写任何表（无表、无迁移），也不改订单数据；`validate` 只做判定，不落库；
 * - **只经 S08 读**：原单与退货单全部来自 `OrderPort`（`getRecent` 按客户 + 退货类型分页读、
 *   `getReceivable` 复核原单仍存在），不跨模块访问 `order_header` / `order_line`（铁律 3 / 10）；
 * - **返回 DTO**：[ReturnableBatchDto] / [ReturnCheckResultDto] 等线格式定义自 1.1.0 起由 `shared`
 *   持有唯一真源，本模块只 import（铁律 9）；数量是服务端产出的规范十进制字符串（铁律 5）；
 * - **不判权限**：参数里没有 token / 角色，权限只在 S13 api-gateway 拦截（铁律 7）；
 * - **不生成单号**：退货单号仍由 S08 经 S07 产出（铁律 8），本模块只校验；
 * - **规则独立**：判定口径全在 [ReturnCheckPolicy] + `ReturnRules`（纯函数），可整体替换升级，
 *   换规则不需要改本 Port 的签名；
 * - **数量 / 批号口径**：统一调 shared 的 `Quantity` / `BatchTagRules`，本模块不复制格式规则。
 *
 * 为什么原单以 [OrderDto] 传入：S08 的 Port 目前没有「按单号取单」的方法（契约 1.1.0 已给
 * `OrderQuery.orderNo`，待 S08 实现），本模块也不能改 S08。调用方（S13）本来就要先经
 * `OrderPort.today` / `getRecent` 取到原单再做归属校验，直接把同一个 DTO 传进来即可，避免本模块
 * 为找一张单去翻整个客户的历史订单。本模块仍会用 `OrderPort.getReceivable` 复核该单号确实存在，
 * 不存在抛 `E3001`。
 */
interface ReturnCheckPort {

    /**
     * 原单还能退的批次列表（C06 退货录入页初始化）。
     *
     * 规则：
     * - 只有策略内的可退单据类型才返回批次；**退货单 / 已作废单返回空列表**（不抛异常）；
     * - 按「型号 + 批号 + 单位」聚合原单明细数量，再减去属于该原单的有效退货单里同维度的数量；
     *   退货单带 `sourceOrderNo` 时精确归属（1.1.0 契约字段），为空时按策略兼容归集；
     * - 可退量 ≤ 0 的批次不返回；结果保持原单明细顺序，批号写法取原单快照。
     *
     * @throws ReturnCheckException `E3001` 原单不存在；`E422` 订单号 / 客户非法；`E413` 退货单分页超限
     */
    fun availableBatches(tx: TxContext, original: OrderDto): List<ReturnableBatchDto>

    /** [availableBatches] 的便捷重载：内部开一个只读事务。 */
    fun availableBatches(original: OrderDto): List<ReturnableBatchDto>

    /**
     * 校验一批退货申请（S13 在收到退单请求后、调用 `OrderPort.create` 之前调用）。
     *
     * [lines] 直接复用 S08 的 [OrderLineCmd]（型号 + 批号 + 单位 + 数量），退货单与普通单同构，
     * 因此不需要新的入参 DTO；服务端只认这里回显的批号写法与数量口径。
     *
     * 通过时返回逐行回显（申请量 / 可退量 / 本次剩余）；不通过时抛异常，不返回部分结果：
     * - 原单已作废 → `E3004`；
     * - 单据类型不可退 / 批次在原单上但已退满 → `E3002`；
     * - 退货批次不在原单上 → `E3005`；
     * - 同批次多行累加后超过可退量 → `E3003`；
     * - 数量格式 / 小数位 / 型号 id 非法 → `E422`；明细行数超过策略上限 → `E413`；
     * - 原单不存在 → `E3001`。
     *
     * **建退货单时**：把返回的 [ReturnCheckResultDto.orderNo] 原样填进
     * `CreateOrderCmd.sourceOrderNo`（1.1.0 契约字段），S08 才能把退货单关联回这张原单；
     * 本模块不代建单号、不代写订单（铁律 8）。
     *
     * @throws ReturnCheckException 见上
     */
    fun validate(
        tx: TxContext,
        original: OrderDto,
        lines: List<OrderLineCmd>,
    ): ReturnCheckResultDto

    /** [validate] 的便捷重载：内部开一个只读事务。 */
    fun validate(original: OrderDto, lines: List<OrderLineCmd>): ReturnCheckResultDto
}
