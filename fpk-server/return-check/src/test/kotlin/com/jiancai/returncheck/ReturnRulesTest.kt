/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   54 行  类 ReturnRulesTest
 * 第   58 行  函数 batches
 * 第   64 行  函数 available_batches_aggregate_lines_per_batch_and_subtract_returns
 * 第   91 行  函数 fully_returned_batches_disappear_from_the_available_list
 * 第  101 行  函数 different_model_or_unit_is_not_subtracted_from_the_batch
 * 第  131 行  函数 return_orders_and_void_orders_are_not_returnable
 * 第  140 行  函数 voided_return_orders_only_consume_quantity_when_the_policy_says_so
 * 第  161 行  函数 returns_linked_to_another_order_are_not_counted
 * 第  178 行  函数 a_return_linked_to_this_order_is_counted_even_in_strict_mode
 * 第  192 行  函数 strict_mode_ignores_returns_without_a_source_order_link
 * 第  215 行  函数 batch_matching_uses_the_shared_batch_tag_rules
 * 第  230 行  函数 quantity_validation_uses_the_shared_quantity_caliber
 * 第  243 行  函数 returnable_order_types_are_policy_driven
 * 第  259 行  函数 validate_rejects_a_batch_that_is_not_on_the_original_order_with_e3005
 * 第  272 行  函数 validate_rejects_a_fully_returned_batch_with_e3002
 * 第  284 行  函数 validate_rejects_quantities_above_the_returnable_amount
 * 第  296 行  函数 validate_merges_duplicate_batch_lines_before_comparing
 * 第  311 行  函数 validate_rejects_bad_quantities_with_e422
 * 第  323 行  函数 validate_requires_at_least_one_line_and_a_legal_model_id
 * 第  334 行  函数 too_many_lines_are_rejected_with_e413
 * 第  348 行  函数 validate_rejects_voided_originals_with_e3004_and_return_orders_with_e3002
 * 第  361 行  函数 validate_echoes_server_side_batch_wording_and_quantity_caliber
 * 第  378 行  函数 remaining_quantity_is_computed_per_batch_across_lines
 * 第  393 行  函数 a_batch_fully_used_by_this_request_reports_zero_remaining
 */

package com.jiancai.returncheck

import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.ReturnableBatchDto
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PricingUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 规则单测（无数据库）：[ReturnRules] 是纯函数。
 *
 * 覆盖 1.1.0 契约接入：
 * - 数量口径统一走 shared 的 `Quantity`（`WireFormat.QUANTITY_MAX_SCALE` = 3、去尾零、> 0）；
 * - 批号口径统一走 shared 的 `BatchTagRules`（trim + ASCII 大小写不敏感）；
 * - 错误码按新段位分流：`E3004` 作废原单、`E3005` 批次与原单不符、`E413` 数据量超限。
 *
 * 「规则可独立升级」的证据：换 [ReturnCheckPolicy] 就换行为，不需要动 [ReturnCheckService]、
 * [ReturnCheckPort] 或任何读取路径。
 */
class ReturnRulesTest {

    private val rules = ReturnRules(ReturnCheckPolicy())

    private fun batches(original: OrderDto, returns: List<OrderDto> = emptyList()): List<ReturnableBatchDto> =
        rules.availableBatches(original, rules.returnedQuantities(returns, original))

    // —— 可退数量计算 ——

    @Test
    fun available_batches_aggregate_lines_per_batch_and_subtract_returns() {
        val original = orderDto(
            lines = listOf(
                lineDto(quantity = "3", seq = 1),
                lineDto(quantity = "2", seq = 2),
                lineDto(batchTag = "B", quantity = "5", seq = 3),
            ),
        )
        val returns = listOf(
            orderDto(
                orderNo = "PT202611110001",
                orderType = OrderType.PURCHASE_RETURN,
                lines = listOf(lineDto(quantity = "1.5", seq = 1)),
            ),
        )

        val result = batches(original, returns)

        assertEquals(2, result.size, "同型号同批号的两行要聚合成一个批次")
        assertEquals("5", result[0].orderedQuantity)
        assertEquals("3.5", result[0].returnableQuantity, "5 − 1.5")
        assertEquals("1.5", result[0].returnedQuantity)
        assertEquals("5", result[1].returnableQuantity, "B 批次没退过，可退 5")
        assertTrue(result.all { it.orderNo == original.orderNo })
    }

    @Test
    fun fully_returned_batches_disappear_from_the_available_list() {
        val original = orderDto(lines = listOf(lineDto(quantity = "4")))
        val returns = listOf(
            orderDto(orderType = OrderType.PURCHASE_RETURN, lines = listOf(lineDto(quantity = "4"))),
        )

        assertTrue(batches(original, returns).isEmpty(), "已退满的批次不应再出现在可退列表")
    }

    @Test
    fun different_model_or_unit_is_not_subtracted_from_the_batch() {
        val original = orderDto(
            lines = listOf(
                lineDto(categoryId = 101, quantity = "10"),
                lineDto(categoryId = 202, quantity = "6"),
                lineDto(categoryId = 101, unit = PricingUnit.BAG, quantity = "3", seq = 3),
            ),
        )
        val returns = listOf(
            orderDto(
                orderType = OrderType.PURCHASE_RETURN,
                lines = listOf(
                    lineDto(categoryId = 202, quantity = "6"),
                    lineDto(categoryId = 101, unit = PricingUnit.BAG, quantity = "1", batchTag = "A"),
                ),
            ),
        )

        val result = batches(original, returns)

        assertEquals(2, result.size, "202 型号已退满，101 型号（吨 + 袋）仍可退")
        val ton = result.single { it.categoryId == 101L && it.unit == PricingUnit.TON }
        assertEquals("10", ton.returnableQuantity, "别型号 / 别单位的退货不能扣这个批次")
        val bag = result.single { it.categoryId == 101L && it.unit == PricingUnit.BAG }
        assertEquals("2", bag.returnableQuantity, "同型号同批号但单位不同要分开算：3 − 1")
    }

    // —— 单据类型规则 ——

    @Test
    fun return_orders_and_void_orders_are_not_returnable() {
        assertTrue(rules.canReturn(orderDto()), "采购单可退")
        assertTrue(rules.canReturn(orderDto(orderType = OrderType.DIRECT)), "直送单可退")
        assertFalse(rules.canReturn(orderDto(orderType = OrderType.PURCHASE_RETURN)), "退货单不可再退")
        assertFalse(rules.canReturn(orderDto(orderType = OrderType.DIRECT_RETURN)), "退货单不可再退")
        assertFalse(rules.canReturn(orderDto(status = OrderStatus.VOID)), "作废单不可退")
    }

    @Test
    fun voided_return_orders_only_consume_quantity_when_the_policy_says_so() {
        val original = orderDto(lines = listOf(lineDto(quantity = "10")))
        val voidedReturn = orderDto(
            status = OrderStatus.VOID,
            orderType = OrderType.PURCHASE_RETURN,
            lines = listOf(lineDto(quantity = "4")),
        )

        assertEquals("10", batches(original, listOf(voidedReturn)).single().returnableQuantity)

        val countingVoid = ReturnRules(ReturnCheckPolicy(countVoidReturnOrders = true))
        val counted = countingVoid.availableBatches(
            original,
            countingVoid.returnedQuantities(listOf(voidedReturn), original),
        )
        assertEquals("6", counted.single().returnableQuantity, "策略要求作废单也占额度时应扣减")
    }

    // —— 1.1.0：退货单与原单的精确归属 ——

    @Test
    fun returns_linked_to_another_order_are_not_counted() {
        val original = orderDto(orderNo = "PS202611110001", lines = listOf(lineDto(quantity = "10")))
        val otherOrderReturn = orderDto(
            orderNo = "PT202611110001",
            orderType = OrderType.PURCHASE_RETURN,
            sourceOrderNo = "PS202611110999",
            lines = listOf(lineDto(quantity = "4")),
        )

        assertEquals(
            "10",
            batches(original, listOf(otherOrderReturn)).single().returnableQuantity,
            "sourceOrderNo 指向别的原单时，不能扣这张单的可退量",
        )
    }

    @Test
    fun a_return_linked_to_this_order_is_counted_even_in_strict_mode() {
        val original = orderDto(orderNo = "PS202611110001", lines = listOf(lineDto(quantity = "10")))
        val linkedReturn = orderDto(
            orderType = OrderType.PURCHASE_RETURN,
            sourceOrderNo = " PS202611110001 ",
            lines = listOf(lineDto(quantity = "4")),
        )
        val strict = ReturnRules(ReturnCheckPolicy(requireSourceOrderLink = true))

        val result = strict.availableBatches(original, strict.returnedQuantities(listOf(linkedReturn), original))
        assertEquals("6", result.single().returnableQuantity)
    }

    @Test
    fun strict_mode_ignores_returns_without_a_source_order_link() {
        val original = orderDto(orderNo = "PS202611110001", lines = listOf(lineDto(quantity = "10")))
        val unlinked = orderDto(
            orderType = OrderType.PURCHASE_RETURN,
            sourceOrderNo = null,
            lines = listOf(lineDto(quantity = "4")),
        )

        // 默认（兼容模式）：S08 尚未回填 source_order_no 时仍按客户 + 批次归集。
        assertEquals("6", batches(original, listOf(unlinked)).single().returnableQuantity)

        // 严格模式：只认显式关联，未关联的历史退货单不占额度（等 S08 全量回填后启用）。
        val strict = ReturnRules(ReturnCheckPolicy(requireSourceOrderLink = true))
        assertEquals(
            "10",
            strict.availableBatches(original, strict.returnedQuantities(listOf(unlinked), original))
                .single().returnableQuantity,
        )
    }

    // —— 1.1.0：数量 / 批号口径以 shared 为唯一真源 ——

    @Test
    fun batch_matching_uses_the_shared_batch_tag_rules() {
        val original = orderDto(lines = listOf(lineDto(batchTag = "A", quantity = "10")))
        val paddedLowerCase = orderDto(
            orderType = OrderType.PURCHASE_RETURN,
            lines = listOf(lineDto(batchTag = "  a  ", quantity = "4")),
        )

        assertEquals(
            "6",
            batches(original, listOf(paddedLowerCase)).single().returnableQuantity,
            "BatchTagRules：trim + ASCII 大小写不敏感，与 S08 的 COLLATE NOCASE 一致",
        )
    }

    @Test
    fun quantity_validation_uses_the_shared_quantity_caliber() {
        // 3 位小数允许（WireFormat.QUANTITY_MAX_SCALE），归一化去尾零。
        assertEquals("1.005", rules.quantity("1.005").display())
        assertEquals("1.5", rules.quantity("1.500").display())
        assertEquals("10", rules.quantity("10.0").display())

        // 4 位小数 / 0 / 负数 / 非十进制 → E422（由 Quantity 的 IllegalArgumentException 翻译而来）。
        listOf("1.0001", "0", "-1", "abc", "", "1e3").forEach { raw ->
            assertReturnError(ErrorCode.E422) { rules.quantity(raw) }
        }
    }

    @Test
    fun returnable_order_types_are_policy_driven() {
        val permissive = ReturnRules(
            ReturnCheckPolicy(
                returnableOrderTypes = setOf(OrderType.PURCHASE, OrderType.DIRECT, OrderType.PURCHASE_RETURN),
            ),
        )
        assertTrue(permissive.canReturn(orderDto(orderType = OrderType.PURCHASE_RETURN)))
        assertFalse(
            ReturnRules(ReturnCheckPolicy()).canReturn(orderDto(orderType = OrderType.PURCHASE_RETURN)),
            "默认口径下退货单不可再退",
        )
    }

    // —— validate ——

    @Test
    fun validate_rejects_a_batch_that_is_not_on_the_original_order_with_e3005() {
        val original = orderDto(lines = listOf(lineDto(batchTag = "A")))
        val failure = assertReturnError(ErrorCode.E3005) {
            rules.validate(
                original,
                listOf(lineCmd(categoryId = 101, quantity = "1", batchTag = "B")),
                batches(original),
            )
        }
        assertTrue(failure.message!!.contains("B"), "错误信息要指出与原单不符的批次")
    }

    @Test
    fun validate_rejects_a_fully_returned_batch_with_e3002() {
        val original = orderDto(lines = listOf(lineDto(quantity = "4")))
        val returns = listOf(
            orderDto(orderType = OrderType.PURCHASE_RETURN, lines = listOf(lineDto(quantity = "4"))),
        )

        assertReturnError(ErrorCode.E3002) {
            rules.validate(original, listOf(lineCmd(categoryId = 101, quantity = "1")), batches(original, returns))
        }
    }

    @Test
    fun validate_rejects_quantities_above_the_returnable_amount() {
        val original = orderDto(lines = listOf(lineDto(quantity = "10")))
        assertReturnError(ErrorCode.E3003) {
            rules.validate(
                original,
                listOf(lineCmd(categoryId = 101, quantity = "10.001")),
                batches(original),
            )
        }
    }

    @Test
    fun validate_merges_duplicate_batch_lines_before_comparing() {
        val original = orderDto(lines = listOf(lineDto(quantity = "6")))
        assertReturnError(ErrorCode.E3003) {
            rules.validate(
                original,
                listOf(
                    lineCmd(categoryId = 101, quantity = "4"),
                    lineCmd(categoryId = 101, quantity = "4"),
                ),
                batches(original),
            )
        }
    }

    @Test
    fun validate_rejects_bad_quantities_with_e422() {
        val original = orderDto(lines = listOf(lineDto(quantity = "10")))
        val available = batches(original)

        listOf("0", "-1", "abc", "1.0001", "", "1e3").forEach { raw ->
            assertReturnError(ErrorCode.E422) {
                rules.validate(original, listOf(lineCmd(categoryId = 101, quantity = raw)), available)
            }
        }
    }

    @Test
    fun validate_requires_at_least_one_line_and_a_legal_model_id() {
        val original = orderDto(lines = listOf(lineDto(quantity = "10")))
        val available = batches(original)

        assertReturnError(ErrorCode.E422) { rules.validate(original, emptyList(), available) }
        assertReturnError(ErrorCode.E422) {
            rules.validate(original, listOf(lineCmd(categoryId = 0, quantity = "1")), available)
        }
    }

    @Test
    fun too_many_lines_are_rejected_with_e413() {
        val original = orderDto(lines = listOf(lineDto(quantity = "10")))
        val single = ReturnRules(ReturnCheckPolicy(maxLines = 1))

        assertReturnError(ErrorCode.E413) {
            single.validate(
                original,
                listOf(lineCmd(categoryId = 101, quantity = "1"), lineCmd(categoryId = 101, quantity = "1")),
                single.availableBatches(original, emptyMap()),
            )
        }
    }

    @Test
    fun validate_rejects_voided_originals_with_e3004_and_return_orders_with_e3002() {
        val voidOrder = orderDto(status = OrderStatus.VOID, lines = listOf(lineDto()))
        assertReturnError(ErrorCode.E3004) {
            rules.validate(voidOrder, listOf(lineCmd(categoryId = 101, quantity = "1")), emptyList())
        }

        val returnOrder = orderDto(orderType = OrderType.PURCHASE_RETURN, lines = listOf(lineDto()))
        assertReturnError(ErrorCode.E3002) {
            rules.validate(returnOrder, listOf(lineCmd(categoryId = 101, quantity = "1")), emptyList())
        }
    }

    @Test
    fun validate_echoes_server_side_batch_wording_and_quantity_caliber() {
        val original = orderDto(lines = listOf(lineDto(batchTag = "A", quantity = "10")))
        val result = rules.validate(
            original,
            listOf(lineCmd(categoryId = 101, quantity = "2.500", batchTag = " a ")),
            batches(original),
        )

        val line = result.lines.single()
        assertEquals(original.orderNo, result.orderNo, "result.orderNo 就是建退货单时要填的 sourceOrderNo")
        assertEquals("A", line.batchTag, "批号写法取原单快照，不采信客户端大小写 / 空白")
        assertEquals("2.5", line.requestedQuantity, "数量去尾零后下发")
        assertEquals("10", line.returnableQuantity)
        assertEquals("7.5", line.remainingQuantity)
    }

    @Test
    fun remaining_quantity_is_computed_per_batch_across_lines() {
        val original = orderDto(lines = listOf(lineDto(batchTag = "A", quantity = "10")))
        val result = rules.validate(
            original,
            listOf(
                lineCmd(categoryId = 101, quantity = "3"),
                lineCmd(categoryId = 101, quantity = "2"),
            ),
            batches(original),
        )

        assertEquals(listOf("7", "5"), result.lines.map { it.remainingQuantity })
    }

    @Test
    fun a_batch_fully_used_by_this_request_reports_zero_remaining() {
        val original = orderDto(lines = listOf(lineDto(quantity = "2")))
        val result = rules.validate(original, listOf(lineCmd(categoryId = 101, quantity = "2")), batches(original))

        assertEquals("0", result.lines.single().remainingQuantity, "剩余量可以为 0（Quantity 只接受 > 0，0 按契约写法输出）")
    }
}
