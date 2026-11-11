/*
 * @file fpk-server/api-gateway/src/test/kotlin/com/jiancai/gateway/TestFakes.kt
 * @role 网关测试用的「假世界」：所有 Port 的内存替身 + 调用计数。
 * @exports （无）
 * @depends account, auth, category, config, customer, db-core, note, observability, order, pricing, print, return-check, settlement, shared
 * @calledBy （无）
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第  211 行  类 FakeWorld
 * 第  256 行  函数 login
 * 第  268 行  函数 newOrder
 * 第  294 行  类 FakeAuth
 * 第  295 行  函数 login
 * 第  296 行  函数 login
 * 第  298 行  函数 verify
 * 第  300 行  函数 verify
 * 第  302 行  函数 lookup
 * 第  309 行  类 FakeAccounts
 * 第  310 行  函数 list
 * 第  311 行  函数 list
 * 第  312 行  函数 get
 * 第  314 行  函数 get
 * 第  315 行  函数 create
 * 第  317 行  函数 create
 * 第  318 行  函数 update
 * 第  320 行  函数 update
 * 第  321 行  函数 delete
 * 第  325 行  函数 delete
 * 第  329 行  函数 find
 * 第  332 行  函数 updated
 * 第  335 行  函数 inserted
 * 第  351 行  类 FakeCustomers
 * 第  352 行  函数 findByPhone
 * 第  353 行  函数 findByPhone
 * 第  354 行  函数 get
 * 第  355 行  函数 get
 * 第  356 行  函数 create
 * 第  357 行  函数 create
 * 第  359 行  函数 customer
 * 第  366 行  函数 created
 * 第  371 行  类 FakeCategories
 * 第  385 行  函数 list
 * 第  386 行  函数 list
 * 第  387 行  函数 get
 * 第  388 行  函数 get
 * 第  389 行  函数 create
 * 第  390 行  函数 create
 * 第  391 行  函数 update
 * 第  392 行  函数 update
 * 第  393 行  函数 delete
 * 第  394 行  函数 delete
 * 第  396 行  函数 filtered
 * 第  399 行  函数 created
 * 第  408 行  函数 updated
 * 第  413 行  类 FakePrices
 * 第  416 行  函数 resolve
 * 第  419 行  函数 resolve
 * 第  422 行  函数 changePrice
 * 第  424 行  函数 changePrice
 * 第  426 行  函数 history
 * 第  429 行  函数 history
 * 第  432 行  函数 changed
 * 第  449 行  函数 matched
 * 第  452 行  函数 price
 * 第  466 行  类 FakeOrders
 * 第  467 行  函数 create
 * 第  468 行  函数 create
 * 第  469 行  函数 update
 * 第  471 行  函数 update
 * 第  472 行  函数 today
 * 第  473 行  函数 today
 * 第  474 行  函数 getRecent
 * 第  475 行  函数 getRecent
 * 第  476 行  函数 get
 * 第  477 行  函数 get
 * 第  478 行  函数 getReceivable
 * 第  479 行  函数 getReceivable
 * 第  480 行  函数 nextNoteNo
 * 第  481 行  函数 nextNoteNo
 * 第  483 行  函数 page
 * 第  488 行  函数 changed
 * 第  493 行  函数 receivable
 * 第  498 行  类 FakeReturns
 * 第  499 行  函数 availableBatches
 * 第  502 行  函数 availableBatches
 * 第  504 行  函数 validate
 * 第  510 行  函数 validate
 * 第  513 行  函数 batches
 * 第  527 行  函数 validated
 * 第  548 行  类 FakeNotes
 * 第  549 行  函数 create
 * 第  550 行  函数 create
 * 第  551 行  函数 void
 * 第  552 行  函数 void
 * 第  554 行  函数 created
 * 第  570 行  函数 voided
 * 第  576 行  类 FakeSettles
 * 第  577 行  函数 submit
 * 第  578 行  函数 submit
 * 第  580 行  函数 audit
 * 第  581 行  函数 audit
 * 第  583 行  函数 getBalance
 * 第  584 行  函数 getBalance
 * 第  586 行  函数 submitted
 * 第  604 行  函数 audited
 * 第  616 行  函数 balance
 * 第  626 行  类 FakePrint
 * 第  627 行  函数 render
 * 第  629 行  函数 render
 * 第  631 行  函数 rendered
 * 第  672 行  类 FakeAudit
 * 第  673 行  函数 record
 * 第  674 行  函数 record
 * 第  675 行  函数 priceChanged
 * 第  678 行  函数 priceChanged
 * 第  679 行  函数 settleAudited
 * 第  687 行  函数 settleAudited
 * 第  694 行  函数 query
 * 第  695 行  函数 query
 * 第  697 行  函数 entries
 * 第  699 行  函数 entries
 * 第  701 行  函数 mapped
 * 第  710 行  函数 toEntry
 * 第  723 行  函数 priceEvent
 * 第  731 行  函数 settleEvent
 * 第  739 行  函数 recorded
 * 第  759 行  类 FakeConfig
 * 第  760 行  函数 getMerchantName
 * 第  761 行  函数 getConfig
 * 第  769 行  类 FakeGateway
 * 第  801 行  函数 close
 * 第  807 行  函数 get_
 * 第  810 行  函数 post_
 * 第  813 行  函数 put_
 * 第  816 行  函数 delete_
 * 第  819 行  函数 request
 */

package com.jiancai.gateway

import com.jiancai.account.AccountAdminException
import com.jiancai.account.AccountAdminPort
import com.jiancai.auth.AuthPort
import com.jiancai.auth.AuthException
import com.jiancai.category.CategoryPort
import com.jiancai.config.ConfigPort
import com.jiancai.customer.CustomerPort
import com.jiancai.db.DbCore
import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.note.NotePort
import com.jiancai.observability.AuditAction
import com.jiancai.observability.AuditEvent
import com.jiancai.observability.AuditPort
import com.jiancai.observability.AuditQuery
import com.jiancai.observability.AuditRecordDto
import com.jiancai.order.OrderPort
import com.jiancai.pricing.PricePort
import com.jiancai.print.PrintPort
import com.jiancai.print.PrintDocumentDto
import com.jiancai.print.PrintHeaderDto
import com.jiancai.print.PrintTotalsDto
import com.jiancai.returncheck.ReturnCheckPort
import com.jiancai.settlement.SettlePort
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.AuditEntryDto
import com.jiancai.shared.dto.AuditSettleCmd
import com.jiancai.shared.dto.CategoryCmd
import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.dto.ChangePriceCmd
import com.jiancai.shared.dto.ConfigDto
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.CreateCustomerCmd
import com.jiancai.shared.dto.CreateNoteCmd
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.dto.LoginCmd
import com.jiancai.shared.dto.LoginResultDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.PageDto
import com.jiancai.shared.dto.PriceChangeLog
import com.jiancai.shared.dto.PriceDto
import com.jiancai.shared.dto.ReceivableDto
import com.jiancai.shared.dto.ReturnCheckLineDto
import com.jiancai.shared.dto.ReturnCheckResultDto
import com.jiancai.shared.dto.ReturnableBatchDto
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.dto.SubmitSettleCmd
import com.jiancai.shared.dto.UpdateAccountCmd
import com.jiancai.shared.dto.UpdateOrderCmd
import com.jiancai.shared.dto.VoidNoteCmd
import com.jiancai.shared.enums.DiffType
import com.jiancai.shared.enums.DocType
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.enums.Role
import com.jiancai.shared.enums.SettleDirection
import com.jiancai.shared.enums.SettleStatus
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.units.PricingUnit
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 网关测试用的「假世界」：所有 Port 的内存替身 + 调用计数。
 *
 * 为什么用替身而不是真实模块：网关的验收点是「路由 / 拦截 / 映射」，用替身才能
 * **直接观察调用次数**（证明幂等重放没有二次执行、权限拒绝时业务 Port 根本没被调用）。
 * 业务正确性由各业务模块自己的单测保证，不在这里重复。
 */
internal class FakeWorld {
    val admin = AccountDto(
        id = 1,
        username = "admin",
        displayName = "管理员",
        role = Role.ADMIN,
        enabled = true,
        createdAt = "2026-11-01T09:00:00+08:00",
    )
    val staff = AccountDto(
        id = 2,
        username = "staff",
        displayName = "店员",
        role = Role.STAFF,
        enabled = true,
        createdAt = "2026-11-01T09:00:00+08:00",
    )

    /** token → 账号；`null` 值表示该 token 校验失败。 */
    val tokens: MutableMap<String, AccountDto> = mutableMapOf(
        "token-admin" to admin,
        "token-staff" to staff,
    )

    var authVerifyCount = 0
    var orderCreateCount = 0
    var orderUpdateCount = 0
    var noteCreateCount = 0
    var settleSubmitCount = 0
    var settleAuditCount = 0
    var printCount = 0
    var priceChangeCount = 0
    var returnValidateCount = 0
    var accountCreateCount = 0

    val orders: LinkedHashMap<String, OrderDto> = LinkedHashMap()
    val notes: LinkedHashMap<String, NoteDto> = LinkedHashMap()
    val settles: LinkedHashMap<String, SettleDto> = LinkedHashMap()
    val audits: MutableList<AuditRecordDto> = mutableListOf()
    val auditEvents: MutableList<AuditEvent> = mutableListOf()
    val accounts: MutableList<AccountDto> = mutableListOf(admin, staff)

    /** 测试可在这里塞入「某单号不存在」之类的场景。 */
    var orderToReturn: OrderDto? = null

    fun login(username: String, password: String): LoginResultDto {
        val account = accounts.firstOrNull { it.username == username }
        if (account == null || password != "secret") {
            throw AuthException("用户名或密码错误", ErrorCode.E6002)
        }
        return LoginResultDto(
            token = "token-${account.username}",
            expiresAt = "2026-11-12T09:00:00+08:00",
            account = account,
        )
    }

    fun newOrder(cmd: CreateOrderCmd): OrderDto {
        orderCreateCount += 1
        val orderNo = "PS2026111100${orderCreateCount.toString().padStart(2, '0')}"
        val order = OrderDto(
            orderNo = orderNo,
            orderType = cmd.orderType,
            status = OrderStatus.CREATED,
            customerId = cmd.customerId,
            customerName = "张三",
            customerPhone = "13800000000",
            orderDate = "2026-11-11",
            lines = emptyList(),
            totalAmount = Money.ofPlain("100.00"),
            receivableAmount = Money.ofPlain("100.00"),
            receivedAmount = Money.ofPlain("0.00"),
            version = 1,
            createdAt = "2026-11-11T09:00:00+08:00",
            createdBy = 2,
            sourceOrderNo = cmd.sourceOrderNo,
        )
        orders[orderNo] = order
        return order
    }
}

/** S02 替身：token → 账号（一次 verify 计一次数）。 */
internal class FakeAuth(private val world: FakeWorld) : AuthPort {
    override fun login(tx: TxContext, cmd: LoginCmd): LoginResultDto = world.login(cmd.username, cmd.password)
    override fun login(cmd: LoginCmd): LoginResultDto = world.login(cmd.username, cmd.password)

    override fun verify(tx: TxContext, token: String): AccountDto = lookup(token)

    override fun verify(token: String): AccountDto = lookup(token)

    private fun lookup(token: String): AccountDto {
        world.authVerifyCount += 1
        return world.tokens[token] ?: throw AuthException("登录已失效，请重新登录", ErrorCode.E401)
    }
}

/** S03 替身。 */
internal class FakeAccounts(private val world: FakeWorld) : AccountAdminPort {
    override fun list(tx: TxContext): List<AccountDto> = world.accounts.toList()
    override fun list(): List<AccountDto> = world.accounts.toList()
    override fun get(tx: TxContext, id: Long): AccountDto = find(id)

    override fun get(id: Long): AccountDto = find(id)
    override fun create(tx: TxContext, cmd: CreateAccountCmd): AccountDto = inserted(cmd)

    override fun create(cmd: CreateAccountCmd): AccountDto = inserted(cmd)
    override fun update(tx: TxContext, cmd: UpdateAccountCmd): AccountDto = updated(cmd)

    override fun update(cmd: UpdateAccountCmd): AccountDto = updated(cmd)
    override fun delete(tx: TxContext, id: Long) {
        world.accounts.removeIf { it.id == id }
    }

    override fun delete(id: Long) {
        world.accounts.removeIf { it.id == id }
    }

    private fun find(id: Long): AccountDto =
        world.accounts.firstOrNull { it.id == id } ?: throw AccountAdminException(ErrorCode.E6005)

    private fun updated(cmd: UpdateAccountCmd): AccountDto =
        AccountDto(cmd.id, "x", cmd.displayName, cmd.role, cmd.enabled, "2026-11-01T09:00:00+08:00")

    private fun inserted(cmd: CreateAccountCmd): AccountDto {
        world.accountCreateCount += 1
        val created = AccountDto(
            id = (world.accounts.maxOfOrNull { it.id } ?: 0) + 1,
            username = cmd.username,
            displayName = cmd.displayName,
            role = cmd.role,
            enabled = true,
            createdAt = "2026-11-11T09:00:00+08:00",
        )
        world.accounts += created
        return created
    }
}

/** S04 替身。 */
internal class FakeCustomers : CustomerPort {
    override fun findByPhone(tx: TxContext, phone: String): CustomerDto? = customer(phone)
    override fun findByPhone(phone: String): CustomerDto? = customer(phone)
    override fun get(tx: TxContext, id: Long): CustomerDto = customer("13800000000")!!
    override fun get(id: Long): CustomerDto = customer("13800000000")!!
    override fun create(tx: TxContext, cmd: CreateCustomerCmd): CustomerDto = created(cmd)
    override fun create(cmd: CreateCustomerCmd): CustomerDto = created(cmd)

    private fun customer(phone: String): CustomerDto? =
        if (phone == "13800000000") {
            CustomerDto(11, "张三", phone, "旧地址", "2026-11-01T09:00:00+08:00")
        } else {
            null
        }

    private fun created(cmd: CreateCustomerCmd): CustomerDto =
        CustomerDto(12, cmd.name, cmd.phone, cmd.address, "2026-11-11T09:00:00+08:00")
}

/** S05 替身。 */
internal class FakeCategories : CategoryPort {
    private val items = listOf(
        CategoryDto(
            id = 22,
            categoryName = "水泥",
            model = "P.O42.5",
            unit = PricingUnit.BAG,
            batchTagRequired = true,
            batchTags = listOf("A1"),
            enabled = true,
            createdAt = "2026-11-01T09:00:00+08:00",
        ),
    )

    override fun list(tx: TxContext, enabledOnly: Boolean): List<CategoryDto> = filtered(enabledOnly)
    override fun list(enabledOnly: Boolean): List<CategoryDto> = filtered(enabledOnly)
    override fun get(tx: TxContext, id: Long): CategoryDto = items.first { it.id == id }
    override fun get(id: Long): CategoryDto = items.first { it.id == id }
    override fun create(tx: TxContext, cmd: CategoryCmd): CategoryDto = created(cmd)
    override fun create(cmd: CategoryCmd): CategoryDto = created(cmd)
    override fun update(tx: TxContext, cmd: CategoryCmd): CategoryDto = updated(cmd)
    override fun update(cmd: CategoryCmd): CategoryDto = updated(cmd)
    override fun delete(tx: TxContext, id: Long) = Unit
    override fun delete(id: Long) = Unit

    private fun filtered(enabledOnly: Boolean): List<CategoryDto> =
        if (enabledOnly) items.filter { it.enabled } else items

    private fun created(cmd: CategoryCmd): CategoryDto =
        CategoryDto(
            id = cmd.id ?: 23,
            categoryName = cmd.categoryName,
            model = cmd.model,
            unit = cmd.unit,
            createdAt = "2026-11-11T09:00:00+08:00",
        )

    private fun updated(cmd: CategoryCmd): CategoryDto =
        created(cmd).copy(id = cmd.id ?: 22)
}

/** S06 替身（`changePrice` 追加一条改价台账，供网关留痕取最新一条）。 */
internal class FakePrices(private val world: FakeWorld) : PricePort {
    private val logs = mutableListOf<PriceChangeLog>()

    override fun resolve(tx: TxContext, customerId: Long, categoryId: Long, batchTag: String): PriceDto =
        price(customerId, categoryId, batchTag)

    override fun resolve(customerId: Long, categoryId: Long, batchTag: String): PriceDto =
        price(customerId, categoryId, batchTag)

    override fun changePrice(tx: TxContext, cmd: ChangePriceCmd, adminId: Long): PriceDto = changed(cmd, adminId)

    override fun changePrice(cmd: ChangePriceCmd, adminId: Long): PriceDto = changed(cmd, adminId)

    override fun history(tx: TxContext, customerId: Long, categoryId: Long, batchTag: String): List<PriceChangeLog> =
        matched(customerId, categoryId, batchTag)

    override fun history(customerId: Long, categoryId: Long, batchTag: String): List<PriceChangeLog> =
        matched(customerId, categoryId, batchTag)

    private fun changed(cmd: ChangePriceCmd, adminId: Long): PriceDto {
        world.priceChangeCount += 1
        val log = PriceChangeLog(
            id = logs.size + 1L,
            customerId = cmd.customerId,
            categoryId = cmd.categoryId,
            batchTag = cmd.batchTag,
            oldPrice = Money.ofPlain("100.00"),
            newPrice = cmd.newPrice,
            adminId = adminId,
            reason = cmd.reason,
            changedAt = "2026-11-11T09:00:00+08:00",
        )
        logs += log
        return price(cmd.customerId, cmd.categoryId, cmd.batchTag).copy(unitPrice = cmd.newPrice)
    }

    private fun matched(customerId: Long, categoryId: Long, batchTag: String): List<PriceChangeLog> =
        logs.filter { it.customerId == customerId && it.categoryId == categoryId && it.batchTag == batchTag }

    private fun price(customerId: Long, categoryId: Long, batchTag: String): PriceDto = PriceDto(
        id = 1,
        customerId = customerId,
        categoryId = categoryId,
        batchTag = batchTag,
        unitPrice = Money.ofPlain("100.00"),
        effectiveDate = "2026-11-11",
        locked = false,
        updatedAt = "2026-11-11T09:00:00+08:00",
        updatedBy = 1,
    )
}

/** S08 替身。 */
internal class FakeOrders(private val world: FakeWorld) : OrderPort {
    override fun create(tx: TxContext, cmd: CreateOrderCmd, createdBy: Long): OrderDto = world.newOrder(cmd)
    override fun create(cmd: CreateOrderCmd, createdBy: Long): OrderDto = world.newOrder(cmd)
    override fun update(tx: TxContext, cmd: UpdateOrderCmd): OrderDto = changed(cmd)

    override fun update(cmd: UpdateOrderCmd): OrderDto = changed(cmd)
    override fun today(tx: TxContext): List<OrderDto> = world.orders.values.toList()
    override fun today(): List<OrderDto> = world.orders.values.toList()
    override fun getRecent(tx: TxContext, query: OrderQuery): PageDto<OrderDto> = page(query)
    override fun getRecent(query: OrderQuery): PageDto<OrderDto> = page(query)
    override fun get(tx: TxContext, orderNo: String): OrderDto? = world.orders[orderNo]
    override fun get(orderNo: String): OrderDto? = world.orders[orderNo]
    override fun getReceivable(tx: TxContext, orderNo: String): Money = receivable(orderNo)
    override fun getReceivable(orderNo: String): Money = receivable(orderNo)
    override fun nextNoteNo(tx: TxContext): String = "BZ202611110001"
    override fun nextNoteNo(): String = "BZ202611110001"

    private fun page(query: OrderQuery): PageDto<OrderDto> {
        val items = world.orders.values.filter { query.orderNo == null || it.orderNo == query.orderNo }
        return PageDto(items = items, total = items.size.toLong(), page = query.page, pageSize = query.pageSize)
    }

    private fun changed(cmd: UpdateOrderCmd): OrderDto {
        world.orderUpdateCount += 1
        return world.orders[cmd.orderNo] ?: throw com.jiancai.order.OrderException(ErrorCode.E2001)
    }

    private fun receivable(orderNo: String): Money =
        world.orders[orderNo]?.receivableAmount ?: throw com.jiancai.order.OrderException(ErrorCode.E2001)
}

/** S09 替身。 */
internal class FakeReturns(private val world: FakeWorld) : ReturnCheckPort {
    override fun availableBatches(tx: TxContext, original: OrderDto): List<ReturnableBatchDto> =
        batches(original)

    override fun availableBatches(original: OrderDto): List<ReturnableBatchDto> = batches(original)

    override fun validate(
        tx: TxContext,
        original: OrderDto,
        lines: List<OrderLineCmd>,
    ): ReturnCheckResultDto = validated(original, lines)

    override fun validate(original: OrderDto, lines: List<OrderLineCmd>): ReturnCheckResultDto =
        validated(original, lines)

    private fun batches(original: OrderDto): List<ReturnableBatchDto> = listOf(
        ReturnableBatchDto(
            orderNo = original.orderNo,
            categoryId = 22,
            categoryName = "水泥",
            model = "P.O42.5",
            batchTag = "A1",
            unit = PricingUnit.BAG,
            orderedQuantity = "10",
            returnedQuantity = "0",
            returnableQuantity = "10",
        ),
    )

    private fun validated(original: OrderDto, lines: List<OrderLineCmd>): ReturnCheckResultDto {
        world.returnValidateCount += 1
        return ReturnCheckResultDto(
            orderNo = original.orderNo,
            lines = lines.map { line ->
                ReturnCheckLineDto(
                    categoryId = line.categoryId,
                    categoryName = "水泥",
                    model = "P.O42.5",
                    batchTag = line.batchTag,
                    unit = line.unit,
                    requestedQuantity = line.quantity,
                    returnableQuantity = "10",
                    remainingQuantity = "5",
                )
            },
        )
    }
}

/** S10 替身。 */
internal class FakeNotes(private val world: FakeWorld) : NotePort {
    override fun create(tx: TxContext, cmd: CreateNoteCmd, createdBy: Long): NoteDto = created(cmd, createdBy)
    override fun create(cmd: CreateNoteCmd, createdBy: Long): NoteDto = created(cmd, createdBy)
    override fun void(tx: TxContext, cmd: VoidNoteCmd, voidedBy: Long): NoteDto = voided(cmd, voidedBy)
    override fun void(cmd: VoidNoteCmd, voidedBy: Long): NoteDto = voided(cmd, voidedBy)

    private fun created(cmd: CreateNoteCmd, createdBy: Long): NoteDto {
        world.noteCreateCount += 1
        val note = NoteDto(
            noteNo = "BZ20261111000${world.noteCreateCount}",
            sourceOrderNo = cmd.sourceOrderNo,
            customerId = 11,
            diffType = cmd.diffType,
            diffAmount = cmd.diffAmount,
            remark = cmd.remark,
            createdAt = "2026-11-11T09:00:00+08:00",
            createdBy = createdBy,
        )
        world.notes[note.noteNo] = note
        return note
    }

    private fun voided(cmd: VoidNoteCmd, voidedBy: Long): NoteDto =
        world.notes[cmd.noteNo]?.copy(voided = true, voidedBy = voidedBy, voidReason = cmd.reason)
            ?: throw com.jiancai.note.NoteException(ErrorCode.E4001)
}

/** S11 替身。 */
internal class FakeSettles(private val world: FakeWorld) : SettlePort {
    override fun submit(tx: TxContext, cmd: SubmitSettleCmd, submittedBy: Long): SettleDto = submitted(cmd, submittedBy)
    override fun submit(cmd: SubmitSettleCmd, submittedBy: Long): SettleDto = submitted(cmd, submittedBy)

    override fun audit(tx: TxContext, cmd: AuditSettleCmd, auditedBy: Long): SettleDto = audited(cmd, auditedBy)
    override fun audit(cmd: AuditSettleCmd, auditedBy: Long): SettleDto = audited(cmd, auditedBy)

    override fun getBalance(tx: TxContext, customerId: Long): ReceivableDto = balance(customerId)
    override fun getBalance(customerId: Long): ReceivableDto = balance(customerId)

    private fun submitted(cmd: SubmitSettleCmd, submittedBy: Long): SettleDto {
        world.settleSubmitCount += 1
        val settle = SettleDto(
            settleNo = "JS2026111100${world.settleSubmitCount.toString().padStart(2, '0')}",
            orderNo = cmd.orderNo,
            customerId = 11,
            customerName = "张三",
            direction = cmd.direction,
            amount = cmd.amount,
            status = SettleStatus.SUBMITTED,
            submittedAt = "2026-11-11T09:00:00+08:00",
            submittedBy = submittedBy,
            remark = cmd.remark,
        )
        world.settles[settle.settleNo] = settle
        return settle
    }

    private fun audited(cmd: AuditSettleCmd, auditedBy: Long): SettleDto {
        world.settleAuditCount += 1
        val current = world.settles[cmd.settleNo] ?: throw com.jiancai.settlement.SettleException(ErrorCode.E5001)
        val updated = current.copy(
            status = if (cmd.approved) SettleStatus.APPROVED else SettleStatus.REJECTED,
            auditedAt = "2026-11-11T09:30:00+08:00",
            auditedBy = auditedBy,
        )
        world.settles[cmd.settleNo] = updated
        return updated
    }

    private fun balance(customerId: Long): ReceivableDto = ReceivableDto(
        customerId = customerId,
        customerName = "张三",
        receivableAmount = Money.ofPlain("100.00"),
        receivedAmount = Money.ofPlain("40.00"),
        unreceivedAmount = Money.ofPlain("60.00"),
    )
}

/** S12 替身：返回一段可识别的 80mm 文本。 */
internal class FakePrint(private val world: FakeWorld) : PrintPort {
    override fun render(tx: TxContext, docNo: String, printedBy: Long?): PrintDocumentDto = rendered(docNo, printedBy)

    override fun render(docNo: String, printedBy: Long?): PrintDocumentDto = rendered(docNo, printedBy)

    private fun rendered(docNo: String, printedBy: Long?): PrintDocumentDto {
        world.printCount += 1
        val content = "测试商家\n单据号：$docNo\n合计：100.00\n"
        return PrintDocumentDto(
            docNo = docNo,
            docType = DocType.PURCHASE,
            title = "采购单",
            paperWidthMm = 80,
            printableWidthMm = 72,
            charactersPerLine = 48,
            columns = 5,
            linesPerItem = 2,
            printedAt = "2026-11-11T09:00:00+08:00",
            printedBy = printedBy,
            printCount = world.printCount,
            maxPrints = 3,
            header = PrintHeaderDto(
                merchantName = "测试商家",
                title = "采购单",
                docNo = docNo,
                date = "2026-11-11",
                statusLabel = "已提交",
                customerName = "张三",
                customerPhone = "13800000000",
                customerAddress = null,
                sourceOrderNo = null,
                remark = null,
            ),
            items = emptyList(),
            totals = PrintTotalsDto(
                totalAmount = Money.ofPlain("100.00"),
                receivableAmount = Money.ofPlain("100.00"),
                receivedAmount = Money.ofPlain("0.00"),
            ),
            note = null,
            content = content,
        )
    }
}

/** S14 替身：记录被写入的审计事件（网关只负责「业务成功后调用」，规则在 S14 自己测）。 */
internal class FakeAudit(private val world: FakeWorld) : AuditPort {
    override fun record(tx: TxContext, event: AuditEvent): AuditRecordDto = recorded(event)
    override fun record(event: AuditEvent): AuditRecordDto = recorded(event)
    override fun priceChanged(tx: TxContext, log: PriceChangeLog, traceId: String?): AuditRecordDto =
        recorded(priceEvent(log, traceId))

    override fun priceChanged(log: PriceChangeLog, traceId: String?): AuditRecordDto = recorded(priceEvent(log, traceId))
    override fun settleAudited(
        tx: TxContext,
        settle: SettleDto,
        approved: Boolean,
        remark: String?,
        traceId: String?,
    ): AuditRecordDto = recorded(settleEvent(settle, approved, traceId))

    override fun settleAudited(
        settle: SettleDto,
        approved: Boolean,
        remark: String?,
        traceId: String?,
    ): AuditRecordDto = recorded(settleEvent(settle, approved, traceId))

    override fun query(tx: TxContext, filter: AuditQuery): List<AuditRecordDto> = world.audits.toList()
    override fun query(filter: AuditQuery): List<AuditRecordDto> = world.audits.toList()

    override fun entries(tx: TxContext, filter: AuditQuery): List<AuditEntryDto> = mapped(filter)

    override fun entries(filter: AuditQuery): List<AuditEntryDto> = mapped(filter)

    private fun mapped(filter: AuditQuery): List<AuditEntryDto> {
        val actionName: String? = filter.action?.name
        val target: String? = filter.targetNo
        return world.audits
            .map { it.toEntry() }
            .filter { actionName == null || it.action == actionName }
            .filter { target == null || it.targetNo == target }
    }

    private fun AuditRecordDto.toEntry(): AuditEntryDto = AuditEntryDto(
        id = id,
        action = action.name,
        targetType = targetType,
        targetNo = targetNo,
        operatorId = operatorId,
        operatorLabel = operatorLabel,
        summary = summary,
        details = details,
        createdAt = createdAt,
        traceId = traceId,
    )

    private fun priceEvent(log: PriceChangeLog, traceId: String?): AuditEvent = AuditEvent(
        action = AuditAction.PRICE_CHANGE,
        targetNo = AuditPort.priceKey(log.customerId, log.categoryId, log.batchTag),
        operatorId = log.adminId,
        summary = "改价 ${log.oldPrice.display()} → ${log.newPrice.display()}",
        traceId = traceId,
    )

    private fun settleEvent(settle: SettleDto, approved: Boolean, traceId: String?): AuditEvent = AuditEvent(
        action = AuditAction.SETTLE_AUDIT,
        targetNo = settle.settleNo,
        operatorId = settle.auditedBy ?: 0,
        summary = if (approved) "结算审核通过" else "结算审核驳回",
        traceId = traceId,
    )

    private fun recorded(event: AuditEvent): AuditRecordDto {
        world.auditEvents += event
        val record = AuditRecordDto(
            id = world.audits.size + 1L,
            action = event.action,
            targetType = event.action.targetType,
            targetNo = event.targetNo,
            operatorId = event.operatorId,
            operatorLabel = event.operatorLabel,
            summary = event.summary,
            details = event.details,
            createdAt = "2026-11-11T09:00:00+08:00",
            traceId = event.traceId,
        )
        world.audits += record
        return record
    }
}

/** S01 替身（只读商家名）。 */
internal class FakeConfig : ConfigPort {
    override fun getMerchantName(): String = "测试商家"
    override fun getConfig(): ConfigDto = ConfigDto(merchantName = "测试商家")
}



/**
 * 装配好的替身世界 + 网关（每个用例一份，互不干扰）。
 */
internal class FakeGateway(
    val world: FakeWorld = FakeWorld(),
) : AutoCloseable {
    val deps: GatewayDeps
    val gateway: ApiGateway

    /** 替身 Port 不写库，但 `TxContext` 由 db-core 独占构造，因此仍需一个真实（内存）连接。 */
    private val db = DbCore.inMemory()

    /** 固定时钟：`2026-11-11T09:00:00+08:00`。 */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-11T01:00:00Z"), ZoneId.of("Asia/Shanghai"))

    init {
        deps = GatewayDeps(
            txRunner = db.txRunner,
            auth = FakeAuth(world),
            accounts = FakeAccounts(world),
            customers = FakeCustomers(),
            categories = FakeCategories(),
            prices = FakePrices(world),
            orders = FakeOrders(world),
            returns = FakeReturns(world),
            notes = FakeNotes(world),
            settlements = FakeSettles(world),
            printing = FakePrint(world),
            audit = FakeAudit(world),
            config = FakeConfig(),
            clock = clock,
        )
        gateway = ApiGateway(deps)
    }

    override fun close() = db.close()
}



/** 构造请求的简写。 */
internal fun get_(path: String, query: Map<String, String> = emptyMap(), token: String? = null): ApiRequest =
    request(HttpMethod.GET, path, query, token, body = "")

internal fun post_(path: String, body: String = "", token: String? = null, idempotencyKey: String? = null): ApiRequest =
    request(HttpMethod.POST, path, emptyMap(), token, body, idempotencyKey)

internal fun put_(path: String, body: String = "", token: String? = null): ApiRequest =
    request(HttpMethod.PUT, path, emptyMap(), token, body)

internal fun delete_(path: String, token: String? = null): ApiRequest =
    request(HttpMethod.DELETE, path, emptyMap(), token, "")

private fun request(
    method: HttpMethod,
    path: String,
    query: Map<String, String>,
    token: String?,
    body: String,
    idempotencyKey: String? = null,
): ApiRequest {
    val headers = buildMap {
        if (token != null) put("authorization", "Bearer $token")
        if (idempotencyKey != null) put("idempotency-key", idempotencyKey)
    }
    return ApiRequest(method = method, path = path, query = query, headers = headers, body = body)
}
