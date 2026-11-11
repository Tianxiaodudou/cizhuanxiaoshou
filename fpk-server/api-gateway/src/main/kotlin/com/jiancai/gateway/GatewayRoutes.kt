/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   71 行  对象 GatewayRoutes
 * 第   74 行  函数 build
 * 第  316 行  函数 isReturn
 * 第  320 行  函数 requireOrder
 * 第  329 行  函数 recordPriceAudit
 * 第  336 行  函数 now
 * 第  340 行  函数 toIdPath
 * 第  344 行  函数 missing
 * 第  347 行  函数 mismatch
 * 第  354 行  函数 mismatchNumber
 */

package com.jiancai.gateway

import com.jiancai.db.TxContext
import com.jiancai.observability.AuditAction
import com.jiancai.observability.AuditQuery
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.AuditEntryDto
import com.jiancai.shared.dto.AuditSettleCmd
import com.jiancai.shared.dto.CategoryCmd
import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.dto.ChangePriceCmd
import com.jiancai.shared.dto.ConfigDto
import com.jiancai.shared.dto.ContractInfoDto
import com.jiancai.shared.dto.CreateCustomerCmd
import com.jiancai.shared.dto.CreateNoteCmd
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.dto.DEFAULT_PAGE
import com.jiancai.shared.dto.DEFAULT_PAGE_SIZE
import com.jiancai.shared.dto.LoginCmd
import com.jiancai.shared.dto.LoginResultDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.MoneySerializer
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.PageDto
import com.jiancai.shared.dto.PriceDto
import com.jiancai.shared.dto.ReceivableDto
import com.jiancai.shared.dto.ReturnCheckResultDto
import com.jiancai.shared.dto.ReturnableBatchDto
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.dto.SubmitSettleCmd
import com.jiancai.shared.dto.UpdateAccountCmd
import com.jiancai.shared.dto.UpdateOrderCmd
import com.jiancai.shared.dto.VoidNoteCmd
import com.jiancai.shared.dto.WireFormat
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.errors.ErrorCode
import kotlinx.serialization.builtins.ListSerializer
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 全部路由（对客户端暴露的唯一入口；C00 只能看到这里的路径）。
 *
 * 命名口径：
 * - 资源用复数名词，动词只出现在不可用资源语义表达的用例（`/void`、`/audit`、`/change`）；
 * - 单号 / id 一律走路径参数，查询条件走 query，写操作入参一律用 `shared` 的 Cmd DTO；
 * - **没有取号接口**：单号由业务模块内部经 S07 产出（铁律 8），客户端不得直接消耗单号。
 *
 * handler 只做三件事：取参数 → 调 Port → 交 DTO。任何计算（金额 / 数量 / 单号 / 日期）都不在这里。
 */
object GatewayRoutes {

    /** 组装路由表（顺序即匹配顺序；同一方法 + 模板不允许重复）。 */
    fun build(deps: GatewayDeps): List<Route> = listOf(
        // —— 契约 / 登录（公开） ——
        Route(HttpMethod.GET, "/contract", RouteAccess.PUBLIC) { ctx ->
            respondJson(
                ContractInfoDto(
                    serverTime = now(deps),
                    merchantName = deps.config?.getConfig()?.merchantName,
                ),
                ContractInfoDto.serializer(),
            )
        },
        Route(HttpMethod.POST, "/auth/login", RouteAccess.PUBLIC) { ctx ->
            respondJson(
                deps.auth.login(ctx.decode(LoginCmd.serializer())),
                LoginResultDto.serializer(),
            )
        },
        Route(HttpMethod.GET, "/me", RouteAccess.AUTHENTICATED) { ctx ->
            respondJson(ctx.caller!!, AccountDto.serializer())
        },

        // —— S03 账号（仅管理员） ——
        Route(HttpMethod.GET, "/accounts", RouteAccess.ADMIN) {
            respondJson(deps.accounts.list(), ListSerializer(AccountDto.serializer()))
        },
        Route(HttpMethod.GET, "/accounts/{id}", RouteAccess.ADMIN) { ctx ->
            respondJson(deps.accounts.get(ctx.path("id").toIdPath("id")), AccountDto.serializer())
        },
        Route(HttpMethod.POST, "/accounts", RouteAccess.ADMIN) { ctx ->
            respondCreated(
                deps.accounts.create(ctx.decode(CreateAccountCmd.serializer())),
                AccountDto.serializer(),
            )
        },
        Route(HttpMethod.PUT, "/accounts/{id}", RouteAccess.ADMIN) { ctx ->
            val id = ctx.path("id").toIdPath("id")
            val cmd = ctx.decode(UpdateAccountCmd.serializer())
            if (cmd.id != id) throw mismatchNumber("id", id, cmd.id)
            respondJson(deps.accounts.update(cmd), AccountDto.serializer())
        },
        Route(HttpMethod.DELETE, "/accounts/{id}", RouteAccess.ADMIN) { ctx ->
            deps.accounts.delete(ctx.path("id").toIdPath("id"))
            respondNoContent()
        },

        // —— S04 客户 ——
        Route(HttpMethod.GET, "/customers", RouteAccess.AUTHENTICATED) { ctx ->
            val phone = ctx.requireQuery("phone")
            val customer = deps.customers.findByPhone(phone)
                ?: throw GatewayException(ErrorCode.E404, "客户不存在")
            respondJson(customer, CustomerDto.serializer())
        },
        Route(HttpMethod.POST, "/customers", RouteAccess.AUTHENTICATED) { ctx ->
            respondCreated(
                deps.customers.create(ctx.decode(CreateCustomerCmd.serializer())),
                CustomerDto.serializer(),
            )
        },

        // —— S05 型号库 ——
        Route(HttpMethod.GET, "/categories", RouteAccess.AUTHENTICATED) { ctx ->
            val enabledOnly = ctx.queryBoolean("enabled_only") ?: false
            respondJson(deps.categories.list(enabledOnly), ListSerializer(CategoryDto.serializer()))
        },
        Route(HttpMethod.GET, "/categories/{id}", RouteAccess.AUTHENTICATED) { ctx ->
            respondJson(deps.categories.get(ctx.path("id").toIdPath("id")), CategoryDto.serializer())
        },
        Route(HttpMethod.POST, "/categories", RouteAccess.ADMIN) { ctx ->
            respondCreated(
                deps.categories.create(ctx.decode(CategoryCmd.serializer())),
                CategoryDto.serializer(),
            )
        },
        Route(HttpMethod.PUT, "/categories/{id}", RouteAccess.ADMIN) { ctx ->
            val id = ctx.path("id").toIdPath("id")
            val cmd = ctx.decode(CategoryCmd.serializer())
            val bodyId = cmd.id
            if (bodyId != null && bodyId != id) throw mismatchNumber("id", id, bodyId)
            respondJson(deps.categories.update(cmd.copy(id = id)), CategoryDto.serializer())
        },
        Route(HttpMethod.DELETE, "/categories/{id}", RouteAccess.ADMIN) { ctx ->
            deps.categories.delete(ctx.path("id").toIdPath("id"))
            respondNoContent()
        },

        // —— S06 定价 ——
        Route(HttpMethod.GET, "/prices", RouteAccess.AUTHENTICATED) { ctx ->
            val customerId = ctx.queryLong("customer_id") ?: ctx.missing("customer_id")
            val categoryId = ctx.queryLong("category_id") ?: ctx.missing("category_id")
            val batchTag = ctx.query("batch_tag") ?: ""
            respondJson(deps.prices.resolve(customerId, categoryId, batchTag), PriceDto.serializer())
        },
        Route(HttpMethod.POST, "/prices/change", RouteAccess.ADMIN) { ctx ->
            val cmd = ctx.decode(ChangePriceCmd.serializer())
            val adminId = ctx.callerId()
            val price = deps.txRunner.run { tx ->
                val updated = deps.prices.changePrice(tx, cmd, adminId)
                recordPriceAudit(deps, tx, cmd, ctx.traceId)
                updated
            }
            respondJson(price, PriceDto.serializer())
        },

        // —— S08 订单 ——
        Route(HttpMethod.GET, "/orders/today", RouteAccess.AUTHENTICATED) {
            respondJson(deps.orders.today(), ListSerializer(OrderDto.serializer()))
        },
        Route(HttpMethod.GET, "/orders", RouteAccess.AUTHENTICATED) { ctx ->
            val query = OrderQuery(
                phone = ctx.query("phone"),
                customerId = ctx.queryLong("customer_id"),
                orderType = ctx.queryEnum<OrderType>("order_type"),
                fromDate = ctx.query("from_date"),
                toDate = ctx.query("to_date"),
                page = ctx.queryInt("page", DEFAULT_PAGE),
                pageSize = ctx.queryInt("page_size", DEFAULT_PAGE_SIZE),
                orderNo = ctx.query("order_no"),
            )
            respondJson(
                deps.orders.getRecent(query),
                PageDto.serializer(OrderDto.serializer()),
            )
        },
        Route(HttpMethod.GET, "/orders/{orderNo}/receivable", RouteAccess.AUTHENTICATED) { ctx ->
            respondJson(
                deps.orders.getReceivable(ctx.path("orderNo")),
                MoneySerializer,
            )
        },
        Route(HttpMethod.POST, "/orders", RouteAccess.AUTHENTICATED, idempotent = true) { ctx ->
            val cmd = ctx.decode(CreateOrderCmd.serializer())
            val userId = ctx.callerId()
            val order = deps.txRunner.run { tx ->
                if (isReturn(cmd.orderType)) {
                    val sourceNo = cmd.sourceOrderNo?.trim().takeUnless { it.isNullOrEmpty() }
                        ?: throw GatewayException(
                            code = ErrorCode.E422,
                            message = "退货单必须带原单号 source_order_no",
                        )
                    val original = requireOrder(deps, sourceNo, tx)
                    deps.returns.validate(tx, original, cmd.lines)
                }
                deps.orders.create(tx, cmd, userId)
            }
            respondCreated(order, OrderDto.serializer())
        },
        Route(HttpMethod.PUT, "/orders/{orderNo}", RouteAccess.AUTHENTICATED) { ctx ->
            val orderNo = ctx.path("orderNo")
            val cmd = ctx.decode(UpdateOrderCmd.serializer())
            if (cmd.orderNo != orderNo) throw mismatch("order_no", orderNo, cmd.orderNo)
            respondJson(deps.orders.update(cmd), OrderDto.serializer())
        },

        // —— S09 退单校验 ——
        Route(HttpMethod.GET, "/orders/{orderNo}/returnable-batches", RouteAccess.AUTHENTICATED) { ctx ->
            val original = requireOrder(deps, ctx.path("orderNo"))
            respondJson(
                deps.returns.availableBatches(original),
                ListSerializer(ReturnableBatchDto.serializer()),
            )
        },
        Route(HttpMethod.POST, "/orders/{orderNo}/return-check", RouteAccess.AUTHENTICATED) { ctx ->
            val original = requireOrder(deps, ctx.path("orderNo"))
            val lines = ctx.decode(ListSerializer(OrderLineCmd.serializer()))
            respondJson(
                deps.returns.validate(original, lines),
                ReturnCheckResultDto.serializer(),
            )
        },

        // —— S10 差异备注单 ——
        Route(HttpMethod.POST, "/notes", RouteAccess.AUTHENTICATED, idempotent = true) { ctx ->
            respondCreated(
                deps.notes.create(ctx.decode(CreateNoteCmd.serializer()), ctx.callerId()),
                NoteDto.serializer(),
            )
        },
        Route(HttpMethod.POST, "/notes/{noteNo}/void", RouteAccess.AUTHENTICATED) { ctx ->
            val noteNo = ctx.path("noteNo")
            val cmd = ctx.decode(VoidNoteCmd.serializer())
            if (cmd.noteNo != noteNo) throw mismatch("note_no", noteNo, cmd.noteNo)
            respondJson(deps.notes.void(cmd, ctx.callerId()), NoteDto.serializer())
        },

        // —— S11 结算 ——
        Route(HttpMethod.POST, "/settlements", RouteAccess.AUTHENTICATED, idempotent = true) { ctx ->
            respondCreated(
                deps.settlements.submit(ctx.decode(SubmitSettleCmd.serializer()), ctx.callerId()),
                SettleDto.serializer(),
            )
        },
        Route(HttpMethod.POST, "/settlements/{settleNo}/audit", RouteAccess.ADMIN) { ctx ->
            val settleNo = ctx.path("settleNo")
            val raw = ctx.decode(AuditSettleCmd.serializer())
            if (raw.settleNo != settleNo) throw mismatch("settle_no", settleNo, raw.settleNo)
            val auditorId = ctx.callerId()
            val settle = deps.txRunner.run { tx ->
                val audited = deps.settlements.audit(tx, raw, auditorId)
                deps.audit?.settleAudited(tx, audited, raw.approved, raw.remark, ctx.traceId)
                audited
            }
            respondJson(settle, SettleDto.serializer())
        },
        Route(HttpMethod.GET, "/customers/{customerId}/balance", RouteAccess.AUTHENTICATED) { ctx ->
            respondJson(
                deps.settlements.getBalance(ctx.path("customerId").toIdPath("customerId")),
                ReceivableDto.serializer(),
            )
        },

        // —— S14 审计（只读；管理员） ——
        Route(HttpMethod.GET, "/audit", RouteAccess.ADMIN) { ctx ->
            val audit = deps.audit ?: throw GatewayException(ErrorCode.E503, "审计未接入")
            val filter = AuditQuery(
                action = ctx.query("action")?.let { wire ->
                    AuditAction.entries.firstOrNull { it.name.equals(wire, ignoreCase = true) }
                        ?: throw GatewayException(ErrorCode.E422, "未知审计动作：$wire")
                },
                targetNo = ctx.query("target_no"),
                operatorId = ctx.queryLong("operator_id"),
                limit = ctx.queryInt("limit", AuditQuery.DEFAULT_LIMIT),
            )
            respondJson(audit.entries(filter), ListSerializer(AuditEntryDto.serializer()))
        },

        // —— S12 打印（服务端渲染，客户端零打印） ——
        Route(HttpMethod.GET, "/print/{docNo}", RouteAccess.AUTHENTICATED) { ctx ->
            val document = deps.printing.render(ctx.path("docNo"), ctx.callerId())
            respondText(
                text = document.content,
                headers = mapOf(
                    "X-Doc-No" to document.docNo,
                    "X-Doc-Type" to document.docType.name,
                    "X-Print-Count" to document.printCount.toString(),
                    "X-Print-Max" to document.maxPrints.toString(),
                    "X-Paper-Width-Mm" to document.paperWidthMm.toString(),
                ),
            )
        },
    )

    /** 退货类单据（必须在建单前做 S09 校验）。 */
    private fun isReturn(type: OrderType): Boolean =
        type == OrderType.PURCHASE_RETURN || type == OrderType.DIRECT_RETURN

    /** 按单号取原单（S08 的只读 `get`，不碰订单表）；[tx] 非空时复用调用方事务。 */
    private fun requireOrder(deps: GatewayDeps, orderNo: String, tx: TxContext? = null): OrderDto {
        val order = if (tx == null) deps.orders.get(orderNo) else deps.orders.get(tx, orderNo)
        return order ?: throw GatewayException(ErrorCode.E2001, "订单不存在：$orderNo")
    }

    /**
     * 改价留痕：从 S06 的改价台账里取最新一条（S06 已经产出完整事实），
     * 网关只把它交给 S14，不重新计算价格（铁律 5）。
     */
    private fun recordPriceAudit(deps: GatewayDeps, tx: TxContext, cmd: ChangePriceCmd, traceId: String) {
        val audit = deps.audit ?: return
        val log = deps.prices.history(tx, cmd.customerId, cmd.categoryId, cmd.batchTag)
            .maxByOrNull { it.id } ?: return
        audit.priceChanged(tx, log, traceId)
    }

    private fun now(deps: GatewayDeps): String =
        OffsetDateTime.now(deps.clock).format(DateTimeFormatter.ofPattern(WireFormat.TIMESTAMP))

    /** 路径里的数字 id；非法 → 422（不进入业务模块）。 */
    private fun String.toIdPath(name: String): Long =
        toLongOrNull()?.takeIf { it > 0 }
            ?: throw GatewayException(ErrorCode.E422, "路径参数 $name 必须是正整数")

    private fun RouteContext.missing(name: String): Nothing =
        throw GatewayException(ErrorCode.E422, "缺少查询参数 $name")

    private fun mismatch(field: String, fromPath: String, fromBody: String): GatewayException =
        GatewayException(
            code = ErrorCode.E422,
            message = "请求体 $field 与路径不一致",
            details = mapOf("path" to fromPath, "body" to fromBody),
        )

    private fun mismatchNumber(field: String, fromPath: Long, fromBody: Long): GatewayException =
        GatewayException(
            code = ErrorCode.E422,
            message = "请求体 $field 与路径不一致",
            details = mapOf("path" to fromPath.toString(), "body" to fromBody.toString()),
        )
}
