/*
 * @file fpk-server/return-check/src/main/kotlin/com/jiancai/returncheck/ReturnCheckService.kt
 * @role 退单校验实现（S09）：只做「读 S08 + 交给规则」的编排，不含任何业务口径。
 * @exports ReturnCheckService
 * @depends db-core, order, shared
 * @calledBy fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.returncheck

import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.order.OrderException
import com.jiancai.order.OrderPort
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.ReturnCheckResultDto
import com.jiancai.shared.dto.ReturnableBatchDto
import com.jiancai.shared.errors.ErrorCode

/**
 * 退单校验实现（S09）：只做「读 S08 + 交给规则」的编排，不含任何业务口径。
 *
 * 分工（验收「规则可独立升级」）：
 * - [ReturnCheckService]：事务包裹、经 [OrderPort] 读原单 / 退货单、把读取结果交给规则；
 * - [ReturnRules]：纯函数，口径来自 [ReturnCheckPolicy] + shared 的 `Quantity` / `BatchTagRules`。
 *
 * 读取路径只有两条，都在 S08 的 Port 上：
 * 1. `getReceivable(orderNo)`：复核原单仍存在（不存在 → `E3001`）。S08 的 Port 目前仍没有
 *    「按单号取单」的方法（契约 1.1.0 已给 `OrderQuery.orderNo`，等 S08 实现后本模块可切换）；
 * 2. `getRecent(customerId, orderType, …)`：分页读出该客户的退货单，用来扣减已退数量；
 *    退货单的 `sourceOrderNo` 用于精确归属（见 [ReturnRules.isLinkedTo]）。
 *
 * 本类**不写任何表**、不调 `OrderPort.create / update`：退单本身仍由 S08 建单，
 * 本模块只回答「能不能退、还能退多少」。
 *
 * @param txRunner db-core 事务入口（带 tx 重载复用调用方事务，便捷重载自开只读事务）
 * @param orders S08 订单 Port（本模块唯一的业务依赖）
 * @param policy 退单规则策略，默认 v1.1.0 口径；组合根可注入替换
 */
class ReturnCheckService(
    private val txRunner: TxRunner,
    private val orders: OrderPort,
    policy: ReturnCheckPolicy = ReturnCheckPolicy(),
) : ReturnCheckPort {

    /** 规则开关（不可变，构造后不再变化；升级规则 = 注入新策略）。 */
    val policy: ReturnCheckPolicy = policy

    private val rules = ReturnRules(policy)

    override fun availableBatches(original: OrderDto): List<ReturnableBatchDto> =
        txRunner.run { tx -> availableBatches(tx, original) }

    override fun availableBatches(tx: TxContext, original: OrderDto): List<ReturnableBatchDto> {
        requireUsableOrder(tx, original)
        // 不可退单据（退货单 / 已作废 / 类型不在策略内）返回空列表，让 UI 直接显示「无可退批次」。
        if (!rules.canReturn(original)) return emptyList()
        val returned = rules.returnedQuantities(loadReturnOrders(tx, original.customerId), original)
        return rules.availableBatches(original, returned)
    }

    override fun validate(
        original: OrderDto,
        lines: List<OrderLineCmd>,
    ): ReturnCheckResultDto = txRunner.run { tx -> validate(tx, original, lines) }

    override fun validate(
        tx: TxContext,
        original: OrderDto,
        lines: List<OrderLineCmd>,
    ): ReturnCheckResultDto {
        requireUsableOrder(tx, original)
        // 先算可退批次再逐行判定：作废 / 类型不可退 / 批次不在原单上分别由规则抛 E3004 / E3002 / E3005。
        val returned = rules.returnedQuantities(loadReturnOrders(tx, original.customerId), original)
        val batches = rules.availableBatches(original, returned)
        return rules.validate(original, lines, batches)
    }

    // —— 读取（只走 S08 的 Port）——

    /**
     * 复核原单仍存在，并挡掉明显非法的入参。
     *
     * S08 的 `getReceivable` 在订单不存在时抛 `OrderException(E2001)`，本模块把它翻译成契约里的
     * `E3001`（S09 段位的「原订单不存在」）；它的返回值（应收金额）与校验无关，不使用。
     */
    private fun requireUsableOrder(tx: TxContext, original: OrderDto) {
        val orderNo = original.orderNo.trim()
        if (orderNo.isEmpty()) throw ReturnCheckException(ErrorCode.E422, "原订单号不能为空")
        if (original.customerId <= 0) {
            throw ReturnCheckException(ErrorCode.E422, "客户 id 必须大于 0：${original.customerId}")
        }
        try {
            orders.getReceivable(tx, orderNo)
        } catch (failure: OrderException) {
            if (failure.code == ErrorCode.E2001) {
                throw ReturnCheckException(ErrorCode.E3001, "原订单不存在：$orderNo", failure)
            }
            throw failure
        }
    }

    /**
     * 分页读出该客户全部有效退货单（只通过 `OrderPort.getRecent`）。
     *
     * - 按策略里的退货单类型逐个查，类型集合来自策略，不在代码里写死枚举；
     * - 每页 [ReturnCheckPolicy.pageSize] 条，翻到「本页空 / 已取满 total / 不足一页」为止；
     * - 超过 [ReturnCheckPolicy.maxReturnOrderPages] 页抛 `E413`（数据量超限），避免无限翻页。
     */
    private fun loadReturnOrders(tx: TxContext, customerId: Long): List<OrderDto> {
        val collected = ArrayList<OrderDto>()
        policy.returnOrderTypes.sortedBy { it.ordinal }.forEach { orderType ->
            var page = 1
            var loaded = 0
            while (true) {
                if (page > policy.maxReturnOrderPages) {
                    throw ReturnCheckException(
                        ErrorCode.E413,
                        "退货单查询超过 ${policy.maxReturnOrderPages} 页（客户 $customerId / $orderType），请先核对历史数据",
                    )
                }
                val result = orders.getRecent(
                    tx,
                    OrderQuery(
                        customerId = customerId,
                        orderType = orderType,
                        page = page,
                        pageSize = policy.pageSize,
                    ),
                )
                collected += result.items
                loaded += result.items.size
                if (result.items.isEmpty() || loaded >= result.total || result.items.size < policy.pageSize) {
                    break
                }
                page += 1
            }
        }
        return collected
    }
}
