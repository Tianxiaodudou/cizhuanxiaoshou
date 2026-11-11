/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   33 行  类 PriceChanged
 * 第   51 行  接口 PriceChangeListener
 * 第   52 行  函数 onPriceChanged
 */

package com.jiancai.pricing

import com.jiancai.shared.dto.Money

/**
 * 价格变更事件（S06 产出，S08 order 消费）。
 *
 * 语义（验收「PriceChanged 触发重算」）：
 * - **只在已有价格真的发生变化时发出**：首次定价、同价重复提交都不发；
 * - 事件在**同一个 db-core 事务内**、改价与留痕写入成功之后同步派发；
 * - 监听方（S08）收到事件后重算受影响的当日订单金额；监听方抛异常会让本次改价整体回滚，
 *   因此监听实现必须幂等、不得做不可回滚的外部副作用；
 * - 事件不是跨端线格式，两端契约中没有它；它只是服务端模块间的内存事件。
 *
 * @property priceId `price` 行主键
 * @property customerId 客户 id（S04）
 * @property categoryId 型号 id（S05）
 * @property batchTag 规范化后的批号类型；空串表示该型号未使用批号
 * @property oldPrice 变更前单价
 * @property newPrice 变更后单价
 * @property adminId 操作管理员 id（仅用于留痕 / 重算触发，不用于权限判断）
 * @property reason 管理员填写的改价原因
 * @property changedAt 服务端产出的变更时间（`WireFormat.TIMESTAMP`）
 */
data class PriceChanged(
    val priceId: Long,
    val customerId: Long,
    val categoryId: Long,
    val batchTag: String,
    val oldPrice: Money,
    val newPrice: Money,
    val adminId: Long,
    val reason: String,
    val changedAt: String,
)

/**
 * 价格变更监听器（S08 order 实现，组合根注入）。
 *
 * 这是一个**出站 Port**：S06 不依赖 S08，只依赖本接口；S08 通过实现它触发订单重算。
 * 多个监听器按注入顺序同步调用；任一监听器抛异常都会让当前改价事务回滚（见 [PriceChanged]）。
 */
fun interface PriceChangeListener {
    fun onPriceChanged(event: PriceChanged)
}
