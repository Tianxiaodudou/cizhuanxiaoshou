/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第  116 行  类 OrderService
 * 第  135 行  函数 create
 * 第  138 行  函数 create
 * 第  204 行  函数 update
 * 第  206 行  函数 update
 * 第  264 行  函数 today
 * 第  266 行  函数 today
 * 第  269 行  函数 getRecent
 * 第  272 行  函数 get
 * 第  274 行  函数 get
 * 第  280 行  函数 getRecent
 * 第  334 行  函数 getReceivable
 * 第  336 行  函数 getReceivable
 * 第  344 行  函数 nextNoteNo
 * 第  346 行  函数 nextNoteNo
 * 第  361 行  函数 normalizeLines
 * 第  408 行  函数 insertLines
 * 第  431 行  函数 loadCustomer
 * 第  440 行  函数 loadCategory
 * 第  447 行  函数 loadPrice
 * 第  467 行  函数 resolveSourceOrderNo
 * 第  502 行  函数 expectedSourceType
 * 第  509 行  函数 normalizeClientRequestId
 * 第  522 行  函数 normalizeOrderNo
 * 第  541 行  函数 normalizeQuantity
 * 第  563 行  函数 normalizeRemark
 * 第  581 行  函数 normalizeBatchTag
 * 第  607 行  函数 normalizeDate
 * 第  621 行  函数 hydrate
 * 第  624 行  函数 hydrateAll
 * 第  630 行  函数 emptyPage
 * 第  633 行  函数 businessDate
 * 第  635 行  函数 now
 * 第  641 行  函数 isUniqueViolation
 * 第  649 行  类 NormalizedLine
 * 第  663 行  类 PriceKey
 * 第  665 行  对象 ?
 */

package com.jiancai.order

import com.jiancai.category.CategoryException
import com.jiancai.category.CategoryPort
import com.jiancai.customer.CustomerException
import com.jiancai.customer.CustomerPort
import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.pricing.PricePort
import com.jiancai.pricing.PricingException
import com.jiancai.serial.SerialException
import com.jiancai.serial.SerialPort
import com.jiancai.shared.dto.BatchTagRules
import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.PageDto
import com.jiancai.shared.dto.PriceDto
import com.jiancai.shared.dto.Quantity
import com.jiancai.shared.dto.UpdateOrderCmd
import com.jiancai.shared.dto.WireFormat
import com.jiancai.shared.enums.OrderStatus
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.errors.ErrorCode
import com.jiancai.shared.serial.SerialType
import com.jiancai.shared.units.PackingSpec
import com.jiancai.shared.units.PricingUnit
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * [OrderPort] 的唯一实现：订单用例层。
 *
 * 职责边界：
 * - **不判权限**：构造参数与参数里都没有角色 / token；`createdBy` 只用于 `created_by` 留痕，
 *   登录与权限只在 S13 api-gateway（铁律 7）；
 * - **单号只来自 S07**：调用 `SerialPort.next(tx, SerialType.of(...))`，本类没有任何前缀字面量，
 *   也不拼接 / 截取单号（铁律 8）；
 * - **单价只来自 S06**：每条明细都调 `PricePort.resolve` 取单价，本类只按数量算金额（铁律 5）；
 * - **不跨模块读写表**：客户 / 型号只经 [CustomerPort] / [CategoryPort] 读取，SQL 只在
 *   [OrderRepository]（铁律 3 / 10）；
 * - **服务端产出**：单号、日期、时间戳、版本、金额、状态全部由服务端产出，客户端只展示。
 *
 * 幂等（验收「幂等通过」）：
 * - 建单先按 `client_request_id` 查已有订单；命中直接返回同一张订单，不重复取号 / 写明细；
 * - 数据库对 `client_request_id` 加唯一约束，作为并发请求的最后一道兜底。
 *
 * 乐观锁与改当日单（验收「乐观锁通过」「改当日单正确」）：
 * - `order_header.version` 从 1 开始，改单必须带原版本；
 * - `UPDATE ... WHERE order_no = ? AND version = ?` 条件更新，0 行 → `E2002`；
 * - 只允许改 `order_date` = 服务端今天的订单，否则 `E2003`；已结清 `E2004`、已作废 `E2003`。
 *
 * 应收派生（验收「应收派生正确」）：
 * - 总额 = 明细金额之和；应收 = 总额按单据方向取符号（退货为负）；
 * - 状态 = 已收 / 应收的纯函数；v1.0.0 建单已收为 `0.00`，状态恒为 `CREATED`；
 * - [getReceivable] 只返回订单应收，已收 / 未收读模型归 S11。
 *
 * @param txRunner db-core 的事务 Port（铁律 4：写操作经它包裹）
 * @param serials S07 单号 Port（唯一取号入口）
 * @param prices S06 定价 Port（唯一单价来源）
 * @param customers S04 客户 Port（客户存在性 + 名称 / 电话 / 地址快照）
 * @param categories S05 型号 Port（型号快照 + 批号类型校验 + 计价单位）
 * @param clock 服务端时钟；测试注入固定时钟以断言业务日期 / 时间戳
 */
class OrderService(
    private val txRunner: TxRunner,
    private val serials: SerialPort,
    private val prices: PricePort,
    private val customers: CustomerPort,
    private val categories: CategoryPort,
    private val clock: Clock = Clock.systemDefaultZone(),
) : OrderPort {

    private val repository = OrderRepository()

    /** 业务日期按共享契约 [WireFormat.DATE] 产出（服务端时区）。 */
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(WireFormat.DATE)

    /** 时间戳按共享契约 [WireFormat.TIMESTAMP] 产出（含时区偏移）。 */
    private val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(WireFormat.TIMESTAMP)

    // —— 建单（幂等）——

    override fun create(cmd: CreateOrderCmd, createdBy: Long): OrderDto =
        txRunner.run { tx -> create(tx, cmd, createdBy) }

    override fun create(tx: TxContext, cmd: CreateOrderCmd, createdBy: Long): OrderDto {
        val clientRequestId = normalizeClientRequestId(cmd.clientRequestId)

        // 幂等优先：重复提交直接返回首单，不再做后续校验 / 取号 / 写明细。
        repository.findByClientRequestId(tx, clientRequestId)?.let { existing ->
            return hydrate(tx, existing)
        }

        if (createdBy <= 0) throw OrderException(ErrorCode.E422, "操作者 id 不合法：$createdBy")
        val customer = loadCustomer(tx, cmd.customerId)
        val sourceOrderNo = resolveSourceOrderNo(tx, cmd.orderType, cmd.sourceOrderNo, customer.id)
        val lines = normalizeLines(tx, customer.id, cmd.lines)
        val remark = normalizeRemark(cmd.remark)

        val total = OrderMath.total(lines.map { it.amount })
        val receivable = OrderMath.receivable(cmd.orderType, total)
        val now = now()
        val orderNo = try {
            serials.next(tx, SerialType.of(cmd.orderType.docType))
        } catch (failure: SerialException) {
            throw OrderException(failure.code, failure.message ?: "单号生成失败", failure)
        }

        val header = StoredOrderHeader(
            orderNo = orderNo,
            orderType = cmd.orderType,
            status = OrderMath.status(ZERO, receivable),
            customerId = customer.id,
            customerName = customer.name,
            customerPhone = customer.phone,
            customerAddress = customer.address,
            orderDate = businessDate(),
            totalAmount = total,
            receivableAmount = receivable,
            receivedAmount = ZERO,
            remark = remark,
            version = INITIAL_VERSION,
            clientRequestId = clientRequestId,
            createdAt = now,
            createdBy = createdBy,
            updatedAt = null,
            sourceOrderNo = sourceOrderNo,
        )

        try {
            repository.insertHeader(tx, header)
        } catch (failure: SQLException) {
            // 并发兜底：另一请求先落库同一幂等键时，返回已有订单而不是 500。
            if (failure.isUniqueViolation()) {
                repository.findByClientRequestId(tx, clientRequestId)?.let { existing ->
                    return hydrate(tx, existing)
                }
            }
            throw failure
        }
        insertLines(tx, orderNo, lines)

        return hydrate(
            tx,
            repository.findByOrderNo(tx, orderNo)
                ?: error("订单插入后未查到：$orderNo"),
        )
    }

    // —— 改单（当日 + 乐观锁）——

    override fun update(cmd: UpdateOrderCmd): OrderDto = txRunner.run { tx -> update(tx, cmd) }

    override fun update(tx: TxContext, cmd: UpdateOrderCmd): OrderDto {
        val orderNo = normalizeOrderNo(cmd.orderNo)
        if (cmd.version < 1) throw OrderException(ErrorCode.E422, "版本号必须大于 0：${cmd.version}")

        val header = repository.findByOrderNo(tx, orderNo)
            ?: throw OrderException(ErrorCode.E2001, "订单不存在：$orderNo")
        if (header.version != cmd.version) {
            throw OrderException(
                ErrorCode.E2002,
                "订单已被修改：期望版本 ${cmd.version}，当前版本 ${header.version}",
            )
        }
        if (header.status == OrderStatus.SETTLED) {
            throw OrderException(ErrorCode.E2004, "订单已结清，不可修改：$orderNo")
        }
        if (header.status == OrderStatus.VOID) {
            throw OrderException(ErrorCode.E2003, "订单已作废，不可修改：$orderNo")
        }
        val today = businessDate()
        if (header.orderDate != today) {
            throw OrderException(
                ErrorCode.E2003,
                "仅当日订单可修改：订单日期 ${header.orderDate}，今天 $today",
            )
        }

        val lines = normalizeLines(tx, header.customerId, cmd.lines)
        val remark = normalizeRemark(cmd.remark)
        val total = OrderMath.total(lines.map { it.amount })
        val receivable = OrderMath.receivable(header.orderType, total)
        val updatedAt = now()

        val updated = repository.updateHeader(
            tx = tx,
            orderNo = orderNo,
            expectedVersion = cmd.version,
            newVersion = cmd.version + 1,
            status = OrderMath.status(header.receivedAmount, receivable),
            totalAmount = total,
            receivableAmount = receivable,
            remark = remark,
            updatedAt = updatedAt,
        )
        if (!updated) {
            throw OrderException(ErrorCode.E2002, "订单已被修改，请刷新后重试：$orderNo")
        }
        repository.deleteLines(tx, orderNo)
        insertLines(tx, orderNo, lines)

        return hydrate(
            tx,
            repository.findByOrderNo(tx, orderNo)
                ?: error("订单更新后未查到：$orderNo"),
        )
    }

    // —— 当日单 / 历史 / 应收 ——

    override fun today(): List<OrderDto> = txRunner.run { tx -> today(tx) }

    override fun today(tx: TxContext): List<OrderDto> =
        hydrateAll(tx, repository.findByOrderDate(tx, businessDate()))

    override fun getRecent(query: OrderQuery): PageDto<OrderDto> =
        txRunner.run { tx -> getRecent(tx, query) }

    override fun get(orderNo: String): OrderDto? = txRunner.run { tx -> get(tx, orderNo) }

    override fun get(tx: TxContext, orderNo: String): OrderDto? {
        val normalized = normalizeOrderNo(orderNo)
        val header = repository.findByOrderNo(tx, normalized) ?: return null
        return hydrate(tx, header)
    }

    override fun getRecent(tx: TxContext, query: OrderQuery): PageDto<OrderDto> {
        val page = query.page
        if (page < 1) throw OrderException(ErrorCode.E422, "页码必须大于 0：$page")
        val pageSize = query.pageSize
        if (pageSize !in 1..MAX_PAGE_SIZE) {
            throw OrderException(ErrorCode.E422, "每页条数必须在 1–$MAX_PAGE_SIZE：$pageSize")
        }

        var customerId = query.customerId
        if (customerId != null && customerId <= 0) {
            throw OrderException(ErrorCode.E422, "客户 id 必须大于 0：$customerId")
        }
        val phone = query.phone
        if (phone != null) {
            val customer = try {
                customers.findByPhone(tx, phone)
            } catch (failure: CustomerException) {
                throw OrderException(failure.code, failure.message ?: "电话格式不正确", failure)
            }
            if (customer == null) return emptyPage(page, pageSize)
            // 同时给了客户 id 且与电话不一致：两个条件不可能同时满足，直接空页。
            if (customerId != null && customerId != customer.id) return emptyPage(page, pageSize)
            customerId = customer.id
        }

        val fromDate = query.fromDate?.let { normalizeDate(it, "开始日期") }
        val toDate = query.toDate?.let { normalizeDate(it, "结束日期") }
        if (fromDate != null && toDate != null && fromDate > toDate) {
            throw OrderException(ErrorCode.E422, "开始日期不能晚于结束日期：$fromDate > $toDate")
        }
        val orderNo = query.orderNo?.let { normalizeOrderNo(it) }

        val filter = OrderFilter(
            customerId = customerId,
            orderType = query.orderType,
            fromDate = fromDate,
            toDate = toDate,
            orderNo = orderNo,
        )
        val total = repository.count(tx, filter)
        val offset = (page - 1).toLong() * pageSize
        val headers = if (offset >= total) {
            emptyList()
        } else {
            repository.findPage(tx, filter, pageSize, offset)
        }
        return PageDto(
            items = hydrateAll(tx, headers),
            total = total,
            page = page,
            pageSize = pageSize,
        )
    }

    override fun getReceivable(orderNo: String): Money = txRunner.run { tx -> getReceivable(tx, orderNo) }

    override fun getReceivable(tx: TxContext, orderNo: String): Money {
        val header = repository.findByOrderNo(tx, normalizeOrderNo(orderNo))
            ?: throw OrderException(ErrorCode.E2001, "订单不存在：$orderNo")
        return if (header.status == OrderStatus.VOID) ZERO else header.receivableAmount
    }

    // —— 备注单号（S10 经本模块取号）——

    override fun nextNoteNo(): String = txRunner.run { tx -> nextNoteNo(tx) }

    override fun nextNoteNo(tx: TxContext): String =
        try {
            serials.next(tx, SerialType.NOTE)
        } catch (failure: SerialException) {
            throw OrderException(failure.code, failure.message ?: "单号生成失败", failure)
        }

    // —— 明细校验 / 单价解析 ——

    /**
     * 把入参明细规范化为可落库形态：数量 → 型号 → 单位 → 批号 → 单价 → 金额。
     *
     * 同一请求内按型号 / 价格维度缓存，避免重复跨模块调用；客户 / 型号 / 价格的存在性
     * 与错误码由各自 Port 决定，本模块只把错误码原样保留。
     */
    private fun normalizeLines(
        tx: TxContext,
        customerId: Long,
        commands: List<OrderLineCmd>,
    ): List<NormalizedLine> {
        if (commands.isEmpty()) throw OrderException(ErrorCode.E2005, "订单明细不能为空")
        if (commands.size > MAX_LINES) {
            throw OrderException(ErrorCode.E422, "订单明细不能超过 $MAX_LINES 行：${commands.size}")
        }

        val categoryCache = HashMap<Long, CategoryDto>()
        val priceCache = HashMap<PriceKey, PriceDto>()

        return commands.mapIndexed { index, command ->
            if (command.categoryId <= 0) {
                throw OrderException(ErrorCode.E422, "型号 id 必须大于 0：${command.categoryId}")
            }
            val quantity = normalizeQuantity(command.quantity)
            val category = categoryCache.getOrPut(command.categoryId) {
                loadCategory(tx, command.categoryId)
            }
            if (command.unit != category.unit) {
                throw OrderException(
                    ErrorCode.E422,
                    "第 ${index + 1} 行单位与型号不一致：型号单位为 ${category.unit.label}，" +
                        "传入 ${command.unit.label}",
                )
            }
            val batchTag = normalizeBatchTag(category, command.batchTag)
            val price = priceCache.getOrPut(PriceKey(command.categoryId, batchTag)) {
                loadPrice(tx, customerId, command.categoryId, batchTag)
            }
            NormalizedLine(
                seq = index + 1,
                categoryId = category.id,
                categoryName = category.categoryName,
                model = category.model,
                batchTag = price.batchTag,
                unit = category.unit,
                quantity = quantity,
                unitPrice = price.unitPrice,
                amount = OrderMath.amount(price.unitPrice, quantity),
                packingSpec = category.packingSpec,
            )
        }
    }

    private fun insertLines(tx: TxContext, orderNo: String, lines: List<NormalizedLine>) {
        lines.forEach { line ->
            repository.insertLine(
                tx,
                orderNo,
                StoredOrderLine(
                    seq = line.seq,
                    categoryId = line.categoryId,
                    categoryName = line.categoryName,
                    model = line.model,
                    batchTag = line.batchTag,
                    unit = line.unit,
                    quantity = line.quantity,
                    unitPrice = line.unitPrice,
                    amount = line.amount,
                    packingSpec = line.packingSpec,
                ),
            )
        }
    }

    // —— 跨模块 Port 调用（错误码原样保留）——

    private fun loadCustomer(tx: TxContext, customerId: Long): CustomerDto {
        if (customerId <= 0) throw OrderException(ErrorCode.E422, "客户 id 必须大于 0：$customerId")
        return try {
            customers.get(tx, customerId)
        } catch (failure: CustomerException) {
            throw OrderException(failure.code, failure.message ?: "客户不存在：id=$customerId", failure)
        }
    }

    private fun loadCategory(tx: TxContext, categoryId: Long): CategoryDto =
        try {
            categories.get(tx, categoryId)
        } catch (failure: CategoryException) {
            throw OrderException(failure.code, failure.message ?: "型号不存在：id=$categoryId", failure)
        }

    private fun loadPrice(tx: TxContext, customerId: Long, categoryId: Long, batchTag: String): PriceDto =
        try {
            prices.resolve(tx, customerId, categoryId, batchTag)
        } catch (failure: PricingException) {
            throw OrderException(failure.code, failure.message ?: "未配置价格", failure)
        }

    // —— 入参规范化 ——

    // —— 原单关联（1.1.0：退货单的 source_order_no）——

    /**
     * 解析并校验退货单的原单号。
     *
     * 兼容口径：1.0.0 的退货单没有原单号，`sourceOrderNo = null` 仍允许创建（S09 的
     * `requireSourceOrderLink = false` 兼容归集模式）；一旦传值，必须指向同客户的、
     * 对应正向类型的未作废订单，否则 `E3005` / `E3004`。
     *
     * 非退货单禁止携带原单号（`E422`）；改单不可变更原单号（`UpdateOrderCmd` 没有该字段）。
     */
    private fun resolveSourceOrderNo(
        tx: TxContext,
        orderType: OrderType,
        raw: String?,
        customerId: Long,
    ): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() }
        if (!OrderMath.isReturn(orderType)) {
            if (text != null) {
                throw OrderException(ErrorCode.E422, "非退货单不能指定原单号：$text")
            }
            return null
        }
        if (text == null) return null

        val sourceOrderNo = normalizeOrderNo(text)
        val source = repository.findByOrderNo(tx, sourceOrderNo)
            ?: throw OrderException(ErrorCode.E3005, "原订单不存在，无法关联退货单：$sourceOrderNo")
        if (source.status == OrderStatus.VOID) {
            throw OrderException(ErrorCode.E3004, "原订单已作废，不可退货：$sourceOrderNo")
        }
        if (source.customerId != customerId) {
            throw OrderException(ErrorCode.E3005, "退货单客户与原订单不一致：$sourceOrderNo")
        }
        val expected = expectedSourceType(orderType)
        if (source.orderType != expected) {
            throw OrderException(
                ErrorCode.E3005,
                "原订单类型不匹配：${orderType.label} 应关联${expected.label}，实际为${source.orderType.label}",
            )
        }
        return sourceOrderNo
    }

    /** 退货单对应的正向单据类型：采购退货 → 采购单，直送退货 → 直送单。 */
    private fun expectedSourceType(returnType: OrderType): OrderType = when (returnType) {
        OrderType.PURCHASE_RETURN -> OrderType.PURCHASE
        OrderType.DIRECT_RETURN -> OrderType.DIRECT
        else -> throw OrderException(ErrorCode.E422, "非退货单没有原单类型：${returnType.label}")
    }

    /** 幂等键：裁剪、非空、≤ 128 字符、无控制字符。 */
    private fun normalizeClientRequestId(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) throw OrderException(ErrorCode.E422, "client_request_id 不能为空")
        if (text.length > MAX_REQUEST_ID_LENGTH) {
            throw OrderException(ErrorCode.E422, "client_request_id 不能超过 $MAX_REQUEST_ID_LENGTH 个字符")
        }
        if (text.any { it.isISOControl() }) {
            throw OrderException(ErrorCode.E422, "client_request_id 不能包含控制字符")
        }
        return text
    }

    /** 单号只做形状校验（长度 / 控制字符），前缀与格式由 S07 保证。 */
    private fun normalizeOrderNo(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) throw OrderException(ErrorCode.E422, "订单号不能为空")
        if (text.length > MAX_ORDER_NO_LENGTH) {
            throw OrderException(ErrorCode.E422, "订单号长度不合法：${text.length}")
        }
        if (text.any { it.isISOControl() }) {
            throw OrderException(ErrorCode.E422, "订单号不能包含控制字符")
        }
        return text
    }

    /**
     * 数量：十进制字符串（最多 3 位小数），必须大于 0。
     *
     * 格式 / 小数位口径统一走 shared 的 [Quantity]（`Quantity.ofPlain`），本模块不自写正则；
     * `<= 0` 仍映射为 `E2006`（先做符号判断，便于客户端区分「数量为 0」与「格式错误」），
     * 其余非法格式 / 超过 3 位小数 → `E422`。返回值是去尾零的规范写法（`1.500` → `1.5`）。
     */
    private fun normalizeQuantity(raw: String): String {
        val text = raw.trim()
        val value = try {
            BigDecimal(text)
        } catch (failure: NumberFormatException) {
            throw OrderException(ErrorCode.E422, "数量格式不正确（只允许十进制数字）：$raw", failure)
        }
        if (value.signum() <= 0) {
            throw OrderException(ErrorCode.E2006, "数量必须大于 0：$raw")
        }
        return try {
            Quantity.ofPlain(text).display()
        } catch (failure: IllegalArgumentException) {
            throw OrderException(
                ErrorCode.E422,
                failure.message ?: "数量格式不正确：$raw",
                failure,
            )
        }
    }

    /** 备注：裁剪首尾空白，空白视为无；≤ 500 字符、无控制字符。 */
    private fun normalizeRemark(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.length > MAX_REMARK_LENGTH) {
            throw OrderException(ErrorCode.E422, "备注不能超过 $MAX_REMARK_LENGTH 个字符")
        }
        if (text.any { it.isISOControl() }) {
            throw OrderException(ErrorCode.E422, "备注不能包含控制字符")
        }
        return text
    }

    /**
     * 批号：裁剪、大小写不敏感匹配型号库里的规范写法。
     *
     * - 型号未配置批号类型：只允许空串；
     * - `batchTagRequired = true`：必须命中其中之一，空串不允许；
     * - 其余情况传值必须命中，否则 `E2007`。
     */
    private fun normalizeBatchTag(category: CategoryDto, raw: String): String {
        val tag = BatchTagRules.normalize(raw)
        if (tag.length > MAX_TAG_LENGTH) {
            throw OrderException(ErrorCode.E422, "批号类型不能超过 $MAX_TAG_LENGTH 个字符")
        }
        if (tag.any { it.isISOControl() }) {
            throw OrderException(ErrorCode.E422, "批号类型不能包含控制字符")
        }

        if (category.batchTags.isEmpty()) {
            if (tag.isNotEmpty() || category.batchTagRequired) {
                throw OrderException(ErrorCode.E2007, "批号与型号不匹配：该型号未配置批号类型")
            }
            return ""
        }
        if (tag.isEmpty()) {
            if (category.batchTagRequired) {
                throw OrderException(ErrorCode.E2007, "批号与型号不匹配：该型号必须选择批号类型")
            }
            return ""
        }
        return category.batchTags.firstOrNull { BatchTagRules.same(it, tag) }
            ?: throw OrderException(ErrorCode.E2007, "批号与型号不匹配：$tag")
    }

    /** 查询日期：必须是 `WireFormat.DATE` 形状的真实日期。 */
    private fun normalizeDate(raw: String, label: String): String {
        val text = raw.trim()
        if (!DATE_PATTERN.matches(text)) {
            throw OrderException(ErrorCode.E422, "$label 格式不正确（应为 yyyy-MM-dd）：$raw")
        }
        return try {
            LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).format(dateFormat)
        } catch (failure: DateTimeParseException) {
            throw OrderException(ErrorCode.E422, "$label 不是有效日期：$raw", failure)
        }
    }

    // —— DTO 组装 / 时钟 / 错误判定 ——

    private fun hydrate(tx: TxContext, header: StoredOrderHeader): OrderDto =
        header.toDto(repository.findLines(tx, header.orderNo))

    private fun hydrateAll(tx: TxContext, headers: List<StoredOrderHeader>): List<OrderDto> {
        if (headers.isEmpty()) return emptyList()
        val linesByOrder = repository.findLinesByOrderNos(tx, headers.map { it.orderNo })
        return headers.map { header -> header.toDto(linesByOrder[header.orderNo].orEmpty()) }
    }

    private fun emptyPage(page: Int, pageSize: Int): PageDto<OrderDto> =
        PageDto(items = emptyList(), total = 0L, page = page, pageSize = pageSize)

    private fun businessDate(): String = LocalDate.now(clock).format(dateFormat)

    private fun now(): String = OffsetDateTime.now(clock).format(timestampFormat)

    /**
     * 唯一约束冲突判定：不同 SQLite JDBC 版本可能上报基础码（19）、
     * 主键扩展码（1555）或唯一索引扩展码（2067），三者都按「重复」处理。
     */
    private fun SQLException.isUniqueViolation(): Boolean =
        errorCode == SQLITE_CONSTRAINT ||
            errorCode == SQLITE_CONSTRAINT_PRIMARY_KEY ||
            errorCode == SQLITE_CONSTRAINT_UNIQUE ||
            sqlState == SQL_STATE_UNIQUE_VIOLATION ||
            message.orEmpty().contains("UNIQUE constraint failed", ignoreCase = true)

    /** 已校验的明细（内部类型）：金额已按 S06 单价派生。 */
    private data class NormalizedLine(
        val seq: Int,
        val categoryId: Long,
        val categoryName: String,
        val model: String,
        val batchTag: String,
        val unit: PricingUnit,
        val quantity: String,
        val unitPrice: Money,
        val amount: Money,
        val packingSpec: PackingSpec?,
    )

    /** 价格维度缓存键（批号已按型号库规范化）。 */
    private data class PriceKey(val categoryId: Long, val batchTag: String)

    private companion object {
        /** `Money` 的零值（已收初始值 / 作废订单应收）。 */
        val ZERO: Money = Money.ofPlain("0.00")

        /** 新单起始版本。 */
        const val INITIAL_VERSION: Long = 1L

        /** 单笔订单最大明细行数。 */
        const val MAX_LINES: Int = 200

        /** 历史查询单页上限。 */
        const val MAX_PAGE_SIZE: Int = 200

        const val MAX_TAG_LENGTH: Int = 32

        const val MAX_REMARK_LENGTH: Int = 500

        const val MAX_REQUEST_ID_LENGTH: Int = 128

        const val MAX_ORDER_NO_LENGTH: Int = 32

        /** 日期形状（真实性再交给 `LocalDate.parse`）。 */
        val DATE_PATTERN = Regex("""^\d{4}-\d{2}-\d{2}$""")

        // SQLite 约束错误码：基础码与扩展码（见 https://sqlite.org/rescode.html）。
        const val SQLITE_CONSTRAINT = 19
        const val SQLITE_CONSTRAINT_PRIMARY_KEY = 1555
        const val SQLITE_CONSTRAINT_UNIQUE = 2067

        /** 标准 SQLState：唯一键冲突（部分驱动 / 版本用它替代 vendor code）。 */
        const val SQL_STATE_UNIQUE_VIOLATION = "23505"
    }
}
