package czxs

import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class ApiError(val code: String, override val message: String) : RuntimeException(message)

/** 业务规则：金额/单号/换算全部在服务端（规格 4/5/27-31）。 */
class Services(private val db: Db, private val repo: Repo, private val cfg: Config) {

    // ---------- 初始化 / 账号 ----------
    fun isInitialized(c: Connection) = repo.initialized(c)

    fun setup(adminUser: String, adminName: String, password: String, merchant: String, apiPort: Int): AccountRow {
        if (merchant.isBlank()) throw ApiError("MERCHANT_REQUIRED", "商家名称必填")
        if (password.length < 6) throw ApiError("PASSWORD_TOO_SHORT", "密码至少 6 位")
        if (apiPort !in 1024..65535) throw ApiError("PORT_INVALID", "端口范围 1024-65535")
        return db.tx { c ->
            if (repo.initialized(c)) throw ApiError("ALREADY_INITIALIZED", "已完成初始化")
            val at = now()
            val id = repo.createAccount(c, adminUser, adminName, Auth.hashPassword(password), "admin", at)
            repo.putSetting(c, "merchant_name", merchant)
            repo.putSetting(c, "api_port", apiPort.toString())
            repo.putSetting(c, "print_width", "80mm")
            repo.putSetting(c, "print_remark", "true")
            repo.putSetting(c, "print_signature", "true")
            repo.putSetting(c, "schema_version", "1")
            seedCategories(c, id, at)
            repo.putSetting(c, "initialized", "true")
            repo.log(c, id, "SETUP", "system", null, "初始化完成，商家=$merchant", at)
            repo.accountById(c, id)!!
        }
    }

    private fun seedCategories(c: Connection, adminId: Long, at: String) {
        val seeds = listOf(
            Triple("瓷砖", "800×800 浅灰抛釉", "800"),
            Triple("防水", "JS-Ⅱ 柔韧防水", "20kg"),
            Triple("胶灰", "瓷砖胶 Ⅱ 型", "20kg"),
            Triple("扣条", "扣条银 C03", "3m"),
        )
        seeds.forEach { (group, name, spec) ->
            val batchType = when (group) {
                "瓷砖", "扣条" -> "color"
                else -> "date"
            }
            val id = repo.insertCategory(c, group, name, spec, batchType, "active", adminId, at)
            repo.addUnit(c, id, if (group == "瓷砖") "片" else if (group == "防水") "桶" else "支", BigDecimal.ONE, true)
        }
    }

    // ---------- 客户端认证 ----------
    fun login(username: String, password: String): Pair<String, AccountRow> = db.tx { c ->
        val (account, hash) = repo.accountByUsername(c, username) ?: throw ApiError("AUTH_FAILED", "账号或密码错误")
        if (account.status != "active" || !Auth.verifyPassword(password, hash)) throw ApiError("AUTH_FAILED", "账号或密码错误")
        val token = Auth.newToken()
        repo.saveApiToken(c, token, account.id, now())
        repo.touchLogin(c, account.id, now())
        token to account
    }

    fun accountByToken(c: Connection, token: String): AccountRow {
        val id = repo.accountIdByToken(c, token) ?: throw ApiError("UNAUTHORIZED", "未登录")
        return repo.accountById(c, id) ?: throw ApiError("UNAUTHORIZED", "账号不存在")
    }

    fun logout(token: String) = db.tx { c -> repo.deleteApiToken(c, token) }

    // ---------- 型号 ----------
    fun categories(): List<CategoryRow> = db.tx { c -> repo.categories(c, listOf("active")) }

    fun submitCategory(account: AccountRow, group: String, name: String, spec: String, batchType: String): CategoryRow = db.tx { c ->
        val status = if (account.role == "admin") "active" else "pending"
        val id = repo.insertCategory(c, group, name, spec, batchType, status, account.id, now())
        repo.log(c, account.id, "CATEGORY_SUBMIT", "category", id.toString(), "$group/$name/$spec -> $status", now())
        repo.category(c, id)!!
    }

    fun approveCategory(admin: AccountRow, id: Long, approve: Boolean, reason: String?) = db.tx { c ->
        repo.auditCategory(c, id, if (approve) "active" else "rejected", admin.id, now(), reason)
        repo.log(c, admin.id, if (approve) "CATEGORY_APPROVE" else "CATEGORY_REJECT", "category", id.toString(), reason, now())
    }

    fun setCategoryStatus(admin: AccountRow, id: Long, status: String) = db.tx { c ->
        repo.auditCategory(c, id, status, admin.id, now(), null)
        repo.log(c, admin.id, "CATEGORY_STATUS", "category", id.toString(), status, now())
    }

    fun setUnit(admin: AccountRow, categoryId: Long, unit: String, conversion: String, settlement: Boolean) = db.tx { c ->
        val conv = conversion.toBigDecimalOrNull() ?: throw ApiError("CONVERSION_INVALID", "换算系数非法")
        if (conv <= BigDecimal.ZERO) throw ApiError("CONVERSION_INVALID", "换算系数必须大于 0")
        if (settlement) {
            repo.units(c, categoryId).forEach { repo.addUnit(c, categoryId, it.unit, it.conversion, false) }
        }
        repo.addUnit(c, categoryId, unit, conv, settlement)
        repo.log(c, admin.id, "UNIT_UPDATE", "category", categoryId.toString(), "$unit=$conv settlement=$settlement", now())
    }

    // ---------- 价格 ----------
    fun price(phone: String, categoryId: Long, batchTag: String = ""): String = db.tx { c ->
        val customer = repo.customerByPhone(c, normalizePhone(phone)) ?: throw ApiError("CUSTOMER_NOT_FOUND", "客户不存在")
        repo.price(c, customer.id, categoryId, batchTag) ?: throw ApiError("PRICE_MISSING", "该批次暂无价格")
    }

    fun setPrice(admin: AccountRow, customerId: Long, categoryId: Long, price: String) =
        setPrice(admin, customerId, categoryId, "", price)

    fun setPrice(admin: AccountRow, customerId: Long, categoryId: Long, batchTag: String, price: String) = db.tx { c ->
        val value = price.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP) ?: throw ApiError("PRICE_INVALID", "价格非法")
        repo.setPrice(c, customerId, categoryId, batchTag, Num.money(value), now(), admin.id)
        repo.log(c, admin.id, "PRICE_SET", "category", categoryId.toString(), "customer=$customerId price=${Num.money(value)}", now())
    }

    fun customerPrices(customerId: Long): List<Triple<Long, String, String>> = db.tx { c -> repo.prices(c, customerId) }

    // ---------- 订单 ----------
    fun createOrder(
        account: AccountRow,
        requestId: String,
        type: String,
        customerName: String,
        phone: String,
        remark: String?,
        items: List<ItemInput>,
    ): OrderRow = db.tx { c ->
        if (type !in ORDER_TYPES) throw ApiError("ORDER_TYPE_INVALID", "单据类型非法")
        if (items.isEmpty()) throw ApiError("ORDER_EMPTY", "至少一行明细")
        repo.orderByRequestId(c, requestId)?.let { id ->
            return@tx orderView(c, repo.orderById(c, id)!!)
        }
        val customer = repo.upsertCustomer(c, customerName.ifBlank { "客户" }, normalizePhone(phone), now())
        val built = items.map { buildItem(c, customer.id, it, type) }
        val total = sign(type, built.sumOf { it.third })
        val orderNo = nextNo(c, "orders", "order_no", prefixOf(type))
        val orderId = repo.insertOrder(c, orderNo, type, customer.id, account.id, requestId, remark, Num.money(total), now())
        built.forEachIndexed { idx, (cat, qtyPair, lineTotal, price, unitPair) ->
            repo.insertItem(
                c, orderId, idx + 1, cat.id, itBatch(items[idx]), items[idx].inputUnit, items[idx].inputQuantity,
                unitPair.second, qtyPair.second, price, Num.money(lineTotal),
            )
        }
        repo.log(c, account.id, "ORDER_CREATE", "order", orderNo, "$type total=${Num.money(total)}", now())
        printOrder(c, orderId, orderNo, "order")
        orderView(c, repo.orderById(c, orderId)!!)
    }

    fun updateOrder(account: AccountRow, orderNo: String, version: Int, remark: String?, items: List<ItemInput>): OrderRow = db.tx { c ->
        val order = repo.orderByNo(c, orderNo) ?: throw ApiError("ORDER_NOT_FOUND", "单据不存在")
        if (!order.createdAt.startsWith(LocalDate.now().toString())) throw ApiError("ORDER_NOT_MODIFIABLE", "仅可修改当日单据")
        if (order.version != version) throw ApiError("ORDER_VERSION_CONFLICT", "单据已被修改，请刷新")
        if (order.status == "printed") {
            repo.saveRevision(c, order.id, order.version, snapshot(c, order), account.id, now())
        }
        val built = items.map { buildItem(c, order.customerId, it, order.type) }
        val total = sign(order.type, built.sumOf { it.third })
        repo.deleteItems(c, order.id)
        built.forEachIndexed { idx, (cat, qtyPair, lineTotal, price, unitPair) ->
            repo.insertItem(
                c, order.id, idx + 1, cat.id, itBatch(items[idx]), items[idx].inputUnit, items[idx].inputQuantity,
                unitPair.second, qtyPair.second, price, Num.money(lineTotal),
            )
        }
        repo.updateOrder(c, order.id, Num.money(total), remark, version)
        repo.log(c, account.id, "ORDER_UPDATE", "order", orderNo, "total=${Num.money(total)}", now())
        orderView(c, repo.orderByNo(c, orderNo)!!)
    }

    private fun itBatch(i: ItemInput) = i.batchTag?.takeIf { it.isNotBlank() }

    private fun buildItem(c: Connection, customerId: Long, input: ItemInput, type: String): Quad<CategoryRow, Pair<String, String>, BigDecimal, String, Pair<String, String>> {
        val cat = repo.category(c, input.categoryId) ?: throw ApiError("CATEGORY_NOT_FOUND", "型号不存在")
        val unit = cat.units.firstOrNull { it.unit == input.inputUnit } ?: throw ApiError("UNIT_INVALID", "单位非法")
        val settlementUnit = cat.units.firstOrNull { it.isSettlement } ?: throw ApiError("UNIT_NOT_CONFIGURED", "未配置结算单位")
        val qty = input.inputQuantity.toBigDecimalOrNull() ?: throw ApiError("QUANTITY_INVALID", "数量非法")
        if (qty <= BigDecimal.ZERO) throw ApiError("QUANTITY_INVALID", "数量必须大于 0")
        val settlementQty = qty.multiply(unit.conversion).setScale(4, RoundingMode.HALF_UP)
        val batch = input.batchTag.orEmpty()
        if (cat.batchType != "none" && batch.isBlank()) throw ApiError("BATCH_REQUIRED", "该型号必须填写批次")
        val existing = repo.price(c, customerId, cat.id, batch)
        val price = when {
            existing != null -> {
                val supplied = input.unitPrice?.takeIf { it.isNotBlank() }
                if (supplied != null && supplied.toBigDecimalOrNull() != existing.toBigDecimalOrNull()) {
                    throw ApiError("PRICE_EXISTS", "价格已存在，当前批次单价为 $existing")
                }
                existing
            }
            else -> {
                val supplied = input.unitPrice?.takeIf { it.isNotBlank() }
                    ?: throw ApiError("PRICE_REQUIRED", "该批次无价格，请录入单价")
                val value = supplied.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
                    ?: throw ApiError("PRICE_INVALID", "单价非法")
                repo.setPrice(c, customerId, cat.id, batch, Num.money(value), now(), 0)
                Num.money(value)
            }
        }
        val lineTotal = settlementQty.multiply(price.toBigDecimal()).setScale(2, RoundingMode.HALF_UP)
        if (type.endsWith("_return")) {
            val available = repo.delivered(c, customerId, cat.id, input.batchTag.orEmpty())
                .subtract(repo.returned(c, customerId, cat.id, input.batchTag.orEmpty()))
            if (settlementQty > available) throw ApiError("RETURN_EXCEEDS_AVAILABLE", "退货数量超出可退余量")
        }
        return Quad(cat, qty.stripTrailingZeros().toPlainString() to settlementQty.stripTrailingZeros().toPlainString(), lineTotal, price, settlementUnit.unit to settlementUnit.unit)
    }

    fun returnsAvailable(phone: String, categoryId: Long): List<Pair<String, String>> = db.tx { c ->
        val customer = repo.customerByPhone(c, normalizePhone(phone)) ?: throw ApiError("CUSTOMER_NOT_FOUND", "客户不存在")
        repo.batches(c, customer.id, categoryId).map { batch ->
            val available = repo.delivered(c, customer.id, categoryId, batch).subtract(repo.returned(c, customer.id, categoryId, batch))
            batch to Num.qty(available)
        }.filter { it.second != "0" }
    }

    fun today(): List<OrderRow> = db.tx { c -> repo.ordersToday(c, LocalDate.now().toString()).map { orderView(c, it) } }

    fun recent(phone: String, days: Int): List<OrderRow> = db.tx { c ->
        val since = OffsetDateTime.now().minusDays(days.toLong().coerceIn(1, 7)).toString()
        repo.ordersRecentByPhone(c, normalizePhone(phone), since).map { orderView(c, it) }
    }

    fun customerLookup(phone: String): Pair<Boolean, String> = db.tx { c ->
        val customer = repo.customerByPhone(c, normalizePhone(phone))
        if (customer == null) false to "" else true to customer.name
    }

    fun cumulative(phone: String): String = db.tx { c ->
        val customer = repo.customerByPhone(c, normalizePhone(phone)) ?: return@tx "0.00"
        Num.money(repo.ordersByPhone(c, customer.phone, 1000).sumOf { Num.bd(it.orderTotal) })
    }

    fun history(phone: String): Pair<CustomerRow, String> = db.tx { c ->
        val customer = repo.customerByPhone(c, normalizePhone(phone)) ?: throw ApiError("CUSTOMER_NOT_FOUND", "客户不存在")
        val total = repo.ordersByPhone(c, customer.phone, 1000).sumOf { Num.bd(it.orderTotal) }
        customer to Num.money(total)
    }

    fun orderDetail(id: Long): OrderRow = db.tx { c -> orderView(c, repo.orderById(c, id) ?: throw ApiError("ORDER_NOT_FOUND", "单据不存在")) }

    fun orderDetailByNo(no: String): OrderRow = db.tx { c -> orderView(c, repo.orderByNo(c, no) ?: throw ApiError("ORDER_NOT_FOUND", "单据不存在")) }

    fun orders(day: String?, type: String?, phone: String?, status: String?, limit: Int = 100): List<OrderRow> = db.tx { c ->
        repo.ordersAll(c, day, type, phone?.let { normalizePhone(it) }, status, limit).map { orderView(c, it) }
    }

    fun itemsOf(orderId: Long): List<OrderItemRow> = db.tx { c -> repo.items(c, orderId) }

    fun revisions(orderId: Long): List<Pair<Int, String>> = db.tx { c -> repo.revisions(c, orderId) }

    private fun orderView(c: Connection, order: OrderRow): OrderRow = order

    // ---------- 备注单 ----------
    fun createNote(account: AccountRow, refOrderNo: String, items: List<NoteItemInput>): NoteRow = db.tx { c ->
        val ref = repo.orderByNo(c, refOrderNo) ?: throw ApiError("ORDER_NOT_FOUND", "原单不存在")
        if (items.isEmpty()) throw ApiError("NOTE_EMPTY", "至少一行差异")
        val noteNo = nextNo(c, "order_notes", "note_no", "N")
        val noteId = repo.insertNote(c, noteNo, ref.orderNo, ref.customerId, account.id, now())
        items.forEach { item ->
            if (item.diffType !in DIFF_TYPES) throw ApiError("DIFF_TYPE_INVALID", "差异类型非法")
            repo.insertNoteItem(c, noteId, item.categoryId, item.batchTag, item.diffType, item.diffQuantity, item.remark)
        }
        repo.log(c, account.id, "NOTE_CREATE", "note", noteNo, "ref=${ref.orderNo}", now())
        printOrder(c, null, noteNo, "note")
        repo.noteById(c, noteId)!!
    }

    fun noteDetail(id: Long): NoteRow = db.tx { c -> repo.noteById(c, id) ?: throw ApiError("NOTE_NOT_FOUND", "备注单不存在") }

    // ---------- 打印 ----------
    fun printStatus(): Triple<Boolean, Int, String?> = db.tx { c ->
        val pending = repo.pendingPrintCount(c)
        val last = repo.printJobs(c, 1).firstOrNull()?.printedAt
        Triple(true, pending, last)
    }

    fun printJobs(): List<PrintJobRow> = db.tx { c -> repo.printJobs(c) }

    fun reprint(admin: AccountRow, orderNo: String) = db.tx { c ->
        val order = repo.orderByNo(c, orderNo) ?: throw ApiError("ORDER_NOT_FOUND", "单据不存在")
        renderTicket(c, order, null)
        repo.markPrinted(c, order.id, now())
        repo.log(c, admin.id, "ORDER_REPRINT", "order", orderNo, "补打", now())
    }

    fun executePrintJob(admin: AccountRow, jobId: Long) = db.tx { c ->
        val job = repo.printJobs(c, 200).firstOrNull { it.id == jobId } ?: throw ApiError("JOB_NOT_FOUND", "打印任务不存在")
        val order = job.orderNo?.let { repo.orderByNo(c, it) }
        if (order != null) {
            renderTicket(c, order, null)
            repo.markPrinted(c, order.id, now())
        }
        repo.finishPrintJob(c, jobId, now())
        repo.log(c, admin.id, "PRINT_JOB", "print_job", jobId.toString(), null, now())
    }

    private fun renderTicket(c: java.sql.Connection, order: OrderRow, merchantOverride: String?) {
        val merchant = merchantOverride ?: repo.setting(c, "merchant_name") ?: "瓷砖店"
        val body = buildString {
            appendLine("==== $merchant ====")
            appendLine("单号：${order.orderNo}")
            appendLine("客户：${order.customerName}")
            appendLine("时间：${now().take(16).replace('T', ' ')}")
            repo.items(c, order.id).forEach { line ->
                appendLine("${line.categoryName} ${line.spec} ${line.batchTag ?: ""}")
                appendLine("  ${line.inputQuantity}${line.inputUnit}（结算 ${line.settlementQuantity}${line.settlementUnit}）× ${line.unitPrice} = ${line.lineTotal}")
            }
            appendLine("合计：${order.orderTotal}")
            appendLine("----------------")
        }
        java.io.File(cfg.printDir, "${order.orderNo}.txt").writeText(body, Charsets.UTF_8)
    }

    fun printPending(admin: AccountRow, jobId: Long) = db.tx { c ->
        repo.log(c, admin.id, "PRINT_JOB", "print_job", jobId.toString(), null, now())
    }

    /** 生成 80mm 小票文本并落 print_jobs（打印执行由服务端内部完成，规格 51）。 */
    private fun printOrder(c: Connection, orderId: Long?, docNo: String, docType: String) {
        val merchant = repo.setting(c, "merchant_name") ?: "瓷砖店"
        val at = now()
        val body = buildString {
            appendLine("==== $merchant ====")
            appendLine("单号：$docNo")
            appendLine("时间：${at.take(16).replace('T', ' ')}")
            if (orderId != null) {
                repo.items(c, orderId).forEach { line ->
                    appendLine("${line.categoryName} ${line.spec} ${line.batchTag ?: ""}")
                    appendLine("  ${line.inputQuantity}${line.inputUnit}（结算 ${line.settlementQuantity}${line.settlementUnit}）× ${line.unitPrice} = ${line.lineTotal}")
                }
            }
            appendLine("----------------")
        }
        repo.insertPrintJob(c, orderId, docType, merchant, at, "pending", null, null)
    }

    // ---------- 工具 ----------
    fun normalizePhone(phone: String): String = phone.replace(" ", "").replace("-", "").trim()

    private fun sign(type: String, total: BigDecimal): BigDecimal =
        if (type.endsWith("_return")) total.negate() else total

    private fun prefixOf(type: String): String = when (type) {
        "delivery" -> "D"
        "delivery_return" -> "R"
        "pickup" -> "P"
        else -> "R"
    }

    private fun nextNo(c: Connection, table: String, column: String, prefix: String): String {
        val day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val like = "$prefix$day%"
        val seq = c.prepareStatement("SELECT COUNT(*) FROM $table WHERE $column LIKE ?").use { ps ->
            ps.setString(1, like); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        return prefix + day + "%03d".format(seq + 1)
    }

    private fun snapshot(c: Connection, order: OrderRow): String =
        """{"order_no":"${order.orderNo}","version":${order.version},"total":"${order.orderTotal}","items":${repo.items(c, order.id).size}}"""


    // ---------- 管理端 ----------
    fun <T> dbAccess(block: (java.sql.Connection) -> T): T = db.tx(block)

    fun parseAdminToken(c: java.sql.Connection, token: String): AccountRow? =
        repo.adminSessionAccount(c, token)?.let { repo.accountById(c, it) }

    fun adminLogin(username: String, password: String): Pair<String, AccountRow> = db.tx { c ->
        val (account, hash) = repo.accountByUsername(c, username) ?: throw ApiError("AUTH_FAILED", "账号或密码错误")
        if (account.role != "admin" || account.status != "active" || !Auth.verifyPassword(password, hash)) {
            throw ApiError("AUTH_FAILED", "账号或密码错误")
        }
        val token = Auth.newToken()
        repo.saveAdminSession(c, token, account.id, now())
        repo.touchLogin(c, account.id, now())
        token to account
    }

    fun adminLogout(token: String) = db.tx { c -> repo.deleteAdminSession(c, token) }

    fun settingsMap(): Map<String, String> = db.tx { c -> repo.allSettings(c) }

    fun saveSetting(admin: AccountRow, key: String, value: String) = db.tx { c ->
        repo.putSetting(c, key, value)
        repo.log(c, admin.id, "SETTING_SAVE", "settings", key, value, now())
    }

    fun accountsList(): List<AccountRow> = db.tx { c -> repo.accounts(c) }

    fun setAccountEnabled(admin: AccountRow, id: Long, enabled: Boolean) = db.tx { c ->
        repo.setAccountStatus(c, id, if (enabled) "active" else "disabled")
        repo.log(c, admin.id, "ACCOUNT_STATUS", "account", id.toString(), enabled.toString(), now())
    }

    fun resetPassword(admin: AccountRow, id: Long, password: String) = db.tx { c ->
        if (password.length < 6) throw ApiError("PASSWORD_TOO_SHORT", "密码至少 6 位")
        repo.resetPassword(c, id, Auth.hashPassword(password))
        repo.log(c, admin.id, "ACCOUNT_RESET_PASSWORD", "account", id.toString(), null, now())
    }

    fun logs(limit: Int = 50): List<String> = db.tx { c -> repo.logs(c, limit) }

    fun printJobsList(): List<PrintJobRow> = db.tx { c -> repo.printJobs(c) }

    fun settlementRows(): List<Triple<CustomerRow, BigDecimal, String>> = db.tx { c -> repo.settlement(c) }

    fun customersList(): List<CustomerRow> = db.tx { c -> repo.customers(c) }

    fun categoriesAll(): List<CategoryRow> = db.tx { c -> repo.categories(c, listOf("active", "pending", "rejected", "disabled")) }

    fun restartApiPort(admin: AccountRow): Int = db.tx { c ->
        val port = (repo.setting(c, "api_port") ?: "8080").toIntOrNull() ?: 8080
        repo.log(c, admin.id, "SERVICE_RESTART", "settings", "api_port", port.toString(), now())
        port
    }
}

val ORDER_TYPES = setOf("delivery", "delivery_return", "pickup", "pickup_return")
val DIFF_TYPES = setOf("short", "over", "damaged")

data class Quad<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)
data class NoteItemInput(val categoryId: Long, val batchTag: String?, val diffType: String, val diffQuantity: String, val remark: String?)

fun now(): String = OffsetDateTime.now().toString()
