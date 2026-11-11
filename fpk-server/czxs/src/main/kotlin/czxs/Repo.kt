package czxs

import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.sql.ResultSet

/** 金额/数量统一用 BigDecimal + 两位小数字符串，禁止浮点累加。 */
object Num {
    fun money(v: BigDecimal): String = v.setScale(2, RoundingMode.HALF_UP).toPlainString()
    fun qty(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()
    fun bd(s: String?): BigDecimal = s?.toBigDecimalOrNull() ?: BigDecimal.ZERO
}

data class AccountRow(val id: Long, val username: String, val name: String, val role: String, val status: String)
data class UnitRow(val unit: String, val conversion: BigDecimal, val isSettlement: Boolean)
data class CategoryRow(
    val id: Long, val group: String, val name: String, val spec: String,
    val batchType: String, val status: String, val units: List<UnitRow>,
)
data class CustomerRow(val id: Long, val name: String, val phone: String)
data class ItemInput(val categoryId: Long, val batchTag: String?, val inputUnit: String, val inputQuantity: String, val unitPrice: String? = null)
data class OrderItemRow(
    val lineNo: Int, val categoryId: Long, val categoryName: String, val spec: String, val batchTag: String?,
    val inputUnit: String, val inputQuantity: String, val settlementUnit: String, val settlementQuantity: String,
    val unitPrice: String, val lineTotal: String,
)
data class OrderRow(
    val id: Long, val orderNo: String, val type: String, val customerId: Long, val customerName: String,
    val customerPhone: String, val accountId: Long, val accountName: String, val status: String,
    val remark: String?, val orderTotal: String, val version: Int, val createdAt: String,
    val printCount: Int, val printedAt: String?,
)
data class NoteItemRow(val categoryId: Long, val categoryName: String, val batchTag: String?, val diffType: String, val diffQuantity: String, val remark: String?)
data class NoteRow(val id: Long, val noteNo: String, val refOrderNo: String, val customerName: String, val status: String, val createdAt: String, val items: List<NoteItemRow>)
data class PrintJobRow(val id: Long, val orderNo: String?, val orderType: String, val status: String, val errorMsg: String?, val createdAt: String, val printedAt: String?)

/** 所有 SQL 只出现在本文件（规格 3）。 */
class Repo(private val db: Db) {

    // ---------- settings ----------
    fun setting(c: Connection, key: String): String? =
        c.prepareStatement("SELECT value FROM settings WHERE key=?").use { ps ->
            ps.setString(1, key); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun putSetting(c: Connection, key: String, value: String) {
        c.prepareStatement("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use { ps ->
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate()
        }
    }

    fun initialized(c: Connection): Boolean = setting(c, "initialized") == "true"

    fun allSettings(c: Connection): Map<String, String> =
        c.createStatement().use { st -> st.executeQuery("SELECT key,value FROM settings").use { rs ->
            buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
        } }

    // ---------- accounts ----------
    fun accountByUsername(c: Connection, username: String): Pair<AccountRow, String>? =
        c.prepareStatement("SELECT id,username,name,password_hash,role,status FROM accounts WHERE username=?").use { ps ->
            ps.setString(1, username); ps.executeQuery().use { rs ->
                if (!rs.next()) null else AccountRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(5), rs.getString(6)) to rs.getString(4)
            }
        }

    fun accountById(c: Connection, id: Long): AccountRow? =
        c.prepareStatement("SELECT id,username,name,role,status FROM accounts WHERE id=?").use { ps ->
            ps.setLong(1, id); ps.executeQuery().use { rs ->
                if (!rs.next()) null else AccountRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))
            }
        }

    fun createAccount(c: Connection, username: String, name: String, hash: String, role: String, at: String): Long =
        c.prepareStatement("INSERT INTO accounts(username,name,password_hash,role,status,created_at) VALUES(?,?,?,?,'active',?)").use { ps ->
            ps.setString(1, username); ps.setString(2, name); ps.setString(3, hash); ps.setString(4, role); ps.setString(5, at)
            ps.executeUpdate()
            c.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
        }

    fun touchLogin(c: Connection, id: Long, at: String) {
        c.prepareStatement("UPDATE accounts SET last_login_at=? WHERE id=?").use { ps -> ps.setString(1, at); ps.setLong(2, id); ps.executeUpdate() }
    }

    fun accounts(c: Connection): List<AccountRow> =
        c.createStatement().use { st -> st.executeQuery("SELECT id,username,name,role,status FROM accounts ORDER BY id").use { rs ->
            buildList { while (rs.next()) add(AccountRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))) }
        } }

    fun setAccountStatus(c: Connection, id: Long, status: String) {
        c.prepareStatement("UPDATE accounts SET status=? WHERE id=?").use { ps -> ps.setString(1, status); ps.setLong(2, id); ps.executeUpdate() }
    }

    fun resetPassword(c: Connection, id: Long, hash: String) {
        c.prepareStatement("UPDATE accounts SET password_hash=? WHERE id=?").use { ps -> ps.setString(1, hash); ps.setLong(2, id); ps.executeUpdate() }
    }

    // ---------- tokens / sessions ----------
    fun saveApiToken(c: Connection, token: String, accountId: Long, at: String) {
        c.prepareStatement("INSERT INTO api_tokens(token,account_id,created_at) VALUES(?,?,?)").use { ps ->
            ps.setString(1, token); ps.setLong(2, accountId); ps.setString(3, at); ps.executeUpdate()
        }
    }

    fun accountIdByToken(c: Connection, token: String): Long? =
        c.prepareStatement("SELECT account_id FROM api_tokens WHERE token=?").use { ps ->
            ps.setString(1, token); ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    fun deleteApiToken(c: Connection, token: String) {
        c.prepareStatement("DELETE FROM api_tokens WHERE token=?").use { ps -> ps.setString(1, token); ps.executeUpdate() }
    }

    fun saveAdminSession(c: Connection, token: String, accountId: Long, at: String) {
        c.prepareStatement("INSERT INTO admin_sessions(token,account_id,created_at) VALUES(?,?,?)").use { ps ->
            ps.setString(1, token); ps.setLong(2, accountId); ps.setString(3, at); ps.executeUpdate()
        }
    }

    fun adminSessionAccount(c: Connection, token: String): Long? =
        c.prepareStatement("SELECT account_id FROM admin_sessions WHERE token=?").use { ps ->
            ps.setString(1, token); ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    fun deleteAdminSession(c: Connection, token: String) {
        c.prepareStatement("DELETE FROM admin_sessions WHERE token=?").use { ps -> ps.setString(1, token); ps.executeUpdate() }
    }

    // ---------- customers ----------
    fun customerByPhone(c: Connection, phone: String): CustomerRow? =
        c.prepareStatement("SELECT id,name,phone FROM customers WHERE phone=?").use { ps ->
            ps.setString(1, phone); ps.executeQuery().use { rs ->
                if (!rs.next()) null else CustomerRow(rs.getLong(1), rs.getString(2), rs.getString(3))
            }
        }

    fun customerById(c: Connection, id: Long): CustomerRow? =
        c.prepareStatement("SELECT id,name,phone FROM customers WHERE id=?").use { ps ->
            ps.setLong(1, id); ps.executeQuery().use { rs ->
                if (!rs.next()) null else CustomerRow(rs.getLong(1), rs.getString(2), rs.getString(3))
            }
        }

    fun upsertCustomer(c: Connection, name: String, phone: String, at: String): CustomerRow {
        // 电话已存在：忽略客户端姓名，沿用库中原姓名（v1.2 流程）
        customerByPhone(c, phone)?.let { return it }
        c.prepareStatement("INSERT INTO customers(name,phone,created_at) VALUES(?,?,?)").use { ps ->
            ps.setString(1, name); ps.setString(2, phone); ps.setString(3, at); ps.executeUpdate()
        }
        return customerByPhone(c, phone)!!
    }

    fun customers(c: Connection): List<CustomerRow> =
        c.createStatement().use { st -> st.executeQuery("SELECT id,name,phone FROM customers ORDER BY id").use { rs ->
            buildList { while (rs.next()) add(CustomerRow(rs.getLong(1), rs.getString(2), rs.getString(3))) }
        } }

    // ---------- categories & units ----------
    fun units(c: Connection, categoryId: Long): List<UnitRow> =
        c.prepareStatement("SELECT unit,conversion,is_settlement FROM category_units WHERE category_id=? ORDER BY is_settlement DESC, id").use { ps ->
            ps.setLong(1, categoryId); ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(UnitRow(rs.getString(1), BigDecimal(rs.getString(2)), rs.getInt(3) == 1)) }
            }
        }

    fun category(c: Connection, id: Long): CategoryRow? =
        c.prepareStatement("SELECT id,grp,name,spec,batch_type,status FROM categories WHERE id=?").use { ps ->
            ps.setLong(1, id); ps.executeQuery().use { rs ->
                if (!rs.next()) null else CategoryRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), units(c, id))
            }
        }

    fun categories(c: Connection, statuses: List<String> = listOf("active")): List<CategoryRow> {
        val marks = statuses.joinToString(",") { "?" }
        return c.prepareStatement("SELECT id,grp,name,spec,batch_type,status FROM categories WHERE status IN ($marks) ORDER BY grp,name,spec").use { ps ->
            statuses.forEachIndexed { i, s -> ps.setString(i + 1, s) }
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(CategoryRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), units(c, rs.getLong(1)))) }
            }
        }
    }

    fun categoryByUnique(c: Connection, group: String, name: String, spec: String): Long? =
        c.prepareStatement("SELECT id FROM categories WHERE grp=? AND name=? AND spec=?").use { ps ->
            ps.setString(1, group); ps.setString(2, name); ps.setString(3, spec)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    fun insertCategory(c: Connection, group: String, name: String, spec: String, batchType: String, status: String, accountId: Long, at: String): Long {
        c.prepareStatement("INSERT INTO categories(grp,name,spec,batch_type,batch_required,status,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, group); ps.setString(2, name); ps.setString(3, spec); ps.setString(4, batchType)
            ps.setInt(5, if (batchType == "none") 0 else 1); ps.setString(6, status); ps.setLong(7, accountId); ps.setString(8, at)
            ps.executeUpdate()
        }
        val id = c.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
        // 默认单位：片 / 箱=10 片（可管理端再改）
        val settlement = if (group == "防水") "桶" else if (group == "胶灰") "包" else "片"
        addUnit(c, id, settlement, BigDecimal.ONE, true)
        if (settlement == "片") addUnit(c, id, "箱", BigDecimal.TEN, false)
        return id
    }

    fun addUnit(c: Connection, categoryId: Long, unit: String, conversion: BigDecimal, settlement: Boolean) {
        c.prepareStatement("INSERT INTO category_units(category_id,unit,conversion,is_settlement) VALUES(?,?,?,?) ON CONFLICT(category_id,unit) DO UPDATE SET conversion=excluded.conversion, is_settlement=excluded.is_settlement").use { ps ->
            ps.setLong(1, categoryId); ps.setString(2, unit); ps.setString(3, conversion.toPlainString()); ps.setInt(4, if (settlement) 1 else 0); ps.executeUpdate()
        }
    }

    fun auditCategory(c: Connection, id: Long, status: String, auditorId: Long, at: String, reason: String?) {
        c.prepareStatement("UPDATE categories SET status=?, audited_by=?, audited_at=?, reject_reason=? WHERE id=?").use { ps ->
            ps.setString(1, status); ps.setLong(2, auditorId); ps.setString(3, at); ps.setString(4, reason); ps.setLong(5, id); ps.executeUpdate()
        }
    }

    // ---------- prices ----------
    fun price(c: Connection, customerId: Long, categoryId: Long, batchTag: String): String? =
        c.prepareStatement("SELECT unit_price FROM customer_prices WHERE customer_id=? AND category_id=? AND batch_tag=?").use { ps ->
            ps.setLong(1, customerId); ps.setLong(2, categoryId); ps.setString(3, batchTag)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun setPrice(c: Connection, customerId: Long, categoryId: Long, batchTag: String, price: String, at: String, accountId: Long) {
        c.prepareStatement("INSERT INTO customer_prices(customer_id,category_id,batch_tag,unit_price,updated_at,updated_by) VALUES(?,?,?,?,?,?) ON CONFLICT(customer_id,category_id,batch_tag) DO UPDATE SET unit_price=excluded.unit_price, updated_at=excluded.updated_at, updated_by=excluded.updated_by").use { ps ->
            ps.setLong(1, customerId); ps.setLong(2, categoryId); ps.setString(3, batchTag); ps.setString(4, price); ps.setString(5, at); ps.setLong(6, accountId); ps.executeUpdate()
        }
    }

    fun prices(c: Connection, customerId: Long): List<Triple<Long, String, String>> =
        c.prepareStatement("SELECT p.category_id, c.name||' '||c.spec||CASE WHEN p.batch_tag='' THEN '' ELSE ' · '||p.batch_tag END, p.unit_price FROM customer_prices p JOIN categories c ON c.id=p.category_id WHERE p.customer_id=? ORDER BY c.name, p.batch_tag").use { ps ->
            ps.setLong(1, customerId); ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(Triple(rs.getLong(1), rs.getString(2), rs.getString(3))) }
            }
        }

    // ---------- orders ----------
    fun insertOrder(c: Connection, orderNo: String, type: String, customerId: Long, accountId: Long, requestId: String, remark: String?, total: String, at: String): Long {
        c.prepareStatement("INSERT INTO orders(order_no,type,customer_id,account_id,status,client_request_id,remark,order_total,version,created_at) VALUES(?,?,?,?,'submitted',?,?,?,1,?)").use { ps ->
            ps.setString(1, orderNo); ps.setString(2, type); ps.setLong(3, customerId); ps.setLong(4, accountId)
            ps.setString(5, requestId); ps.setString(6, remark); ps.setString(7, total); ps.setString(8, at); ps.executeUpdate()
        }
        return c.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
    }

    fun insertItem(c: Connection, orderId: Long, lineNo: Int, categoryId: Long, batchTag: String?, inUnit: String, inQty: String, setUnit: String, setQty: String, price: String, total: String) {
        c.prepareStatement("INSERT INTO order_items(order_id,line_no,category_id,batch_tag,input_unit,input_quantity,settlement_unit,settlement_quantity,unit_price,line_total) VALUES(?,?,?,?,?,?,?,?,?,?)").use { ps ->
            ps.setLong(1, orderId); ps.setInt(2, lineNo); ps.setLong(3, categoryId); ps.setString(4, batchTag)
            ps.setString(5, inUnit); ps.setString(6, inQty); ps.setString(7, setUnit); ps.setString(8, setQty)
            ps.setString(9, price); ps.setString(10, total); ps.executeUpdate()
        }
    }

    fun orderByRequestId(c: Connection, requestId: String): Long? =
        c.prepareStatement("SELECT id FROM orders WHERE client_request_id=?").use { ps ->
            ps.setString(1, requestId); ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    fun orderById(c: Connection, id: Long): OrderRow? = orderWhere(c, "o.id=?", id)
    fun orderByNo(c: Connection, orderNo: String): OrderRow? = orderWhere(c, "o.order_no=?", orderNo)

    private fun orderWhere(c: Connection, cond: String, arg: Any): OrderRow? =
        c.prepareStatement(
            """SELECT o.id,o.order_no,o.type,o.customer_id,cu.name,cu.phone,o.account_id,a.name,o.status,o.remark,
                      o.order_total,o.version,o.created_at,o.print_count,o.printed_at
               FROM orders o JOIN customers cu ON cu.id=o.customer_id JOIN accounts a ON a.id=o.account_id
               WHERE $cond""",
        ).use { ps ->
            when (arg) { is Long -> ps.setLong(1, arg); is String -> ps.setString(1, arg); else -> ps.setObject(1, arg) }
            ps.executeQuery().use { rs ->
                if (!rs.next()) null else OrderRow(
                    rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6),
                    rs.getLong(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getInt(12),
                    rs.getString(13), rs.getInt(14), rs.getString(15),
                )
            }
        }

    fun items(c: Connection, orderId: Long): List<OrderItemRow> =
        c.prepareStatement(
            """SELECT i.line_no,i.category_id,c.name,c.spec,i.batch_tag,i.input_unit,i.input_quantity,
                      i.settlement_unit,i.settlement_quantity,i.unit_price,i.line_total
               FROM order_items i JOIN categories c ON c.id=i.category_id WHERE i.order_id=? ORDER BY i.line_no""",
        ).use { ps ->
            ps.setLong(1, orderId); ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        OrderItemRow(
                            rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                        ),
                    )
                }
            }
        }

    fun ordersToday(c: Connection, day: String): List<OrderRow> = ordersWhere(c, "date(o.created_at)=?", listOf(day))

    fun ordersRecentByPhone(c: Connection, phone: String, sinceIso: String): List<OrderRow> =
        ordersWhere(c, "cu.phone=? AND o.created_at>=?", listOf(phone, sinceIso))

    fun ordersByPhone(c: Connection, phone: String, limit: Int): List<OrderRow> =
        ordersWhere(c, "cu.phone=? ", listOf(phone), limit)

    fun ordersAll(c: Connection, day: String?, type: String?, phone: String?, status: String?, limit: Int): List<OrderRow> {
        val conds = mutableListOf<String>()
        val args = mutableListOf<Any>()
        day?.let { conds += "date(o.created_at)=?"; args += it }
        type?.let { conds += "o.type=?"; args += it }
        phone?.let { conds += "cu.phone=?"; args += it }
        status?.let { conds += "o.status=?"; args += it }
        return ordersWhere(c, if (conds.isEmpty()) "1=1" else conds.joinToString(" AND "), args, limit)
    }

    private fun ordersWhere(c: Connection, where: String, args: List<Any>, limit: Int = 100): List<OrderRow> {
        val sql = """SELECT o.id,o.order_no,o.type,o.customer_id,cu.name,cu.phone,o.account_id,a.name,o.status,o.remark,
                            o.order_total,o.version,o.created_at,o.print_count,o.printed_at
                     FROM orders o JOIN customers cu ON cu.id=o.customer_id JOIN accounts a ON a.id=o.account_id
                     WHERE $where ORDER BY o.id DESC LIMIT $limit"""
        return c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, v -> when (v) { is Long -> ps.setLong(i + 1, v); is String -> ps.setString(i + 1, v); else -> ps.setObject(i + 1, v) } }
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        OrderRow(
                            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6),
                            rs.getLong(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getInt(12),
                            rs.getString(13), rs.getInt(14), rs.getString(15),
                        ),
                    )
                }
            }
        }
    }

    fun updateOrder(c: Connection, id: Long, total: String, remark: String?, version: Int) {
        c.prepareStatement("UPDATE orders SET order_total=?, remark=?, version=version+1, status='submitted' WHERE id=? AND version=?").use { ps ->
            ps.setString(1, total); ps.setString(2, remark); ps.setLong(3, id); ps.setInt(4, version); ps.executeUpdate()
        }
    }

    fun deleteItems(c: Connection, orderId: Long) {
        c.prepareStatement("DELETE FROM order_items WHERE order_id=?").use { ps -> ps.setLong(1, orderId); ps.executeUpdate() }
    }

    fun markPrinted(c: Connection, id: Long, at: String) {
        c.prepareStatement("UPDATE orders SET status='printed', print_count=print_count+1, printed_at=? WHERE id=?").use { ps ->
            ps.setString(1, at); ps.setLong(2, id); ps.executeUpdate()
        }
    }

    fun saveRevision(c: Connection, orderId: Long, version: Int, snapshot: String, accountId: Long, at: String) {
        c.prepareStatement("INSERT INTO order_revisions(order_id,version,snapshot_json,account_id,created_at) VALUES(?,?,?,?,?)").use { ps ->
            ps.setLong(1, orderId); ps.setInt(2, version); ps.setString(3, snapshot); ps.setLong(4, accountId); ps.setString(5, at); ps.executeUpdate()
        }
    }

    fun revisions(c: Connection, orderId: Long): List<Pair<Int, String>> =
        c.prepareStatement("SELECT version,snapshot_json FROM order_revisions WHERE order_id=? ORDER BY id").use { ps ->
            ps.setLong(1, orderId); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getInt(1) to rs.getString(2)) } }
        }

    // ---------- returns ----------
    fun delivered(c: Connection, customerId: Long, categoryId: Long, batchTag: String): BigDecimal =
        c.prepareStatement(
            """SELECT COALESCE(SUM(CAST(i.settlement_quantity AS REAL)),0) FROM order_items i
               JOIN orders o ON o.id=i.order_id
               WHERE o.customer_id=? AND i.category_id=? AND COALESCE(i.batch_tag,'')=? AND o.type IN ('delivery','pickup')""",
        ).use { ps ->
            ps.setLong(1, customerId); ps.setLong(2, categoryId); ps.setString(3, batchTag)
            ps.executeQuery().use { rs -> rs.next(); BigDecimal(rs.getDouble(1).toString()) }
        }

    fun returned(c: Connection, customerId: Long, categoryId: Long, batchTag: String): BigDecimal =
        c.prepareStatement(
            """SELECT COALESCE(SUM(CAST(i.settlement_quantity AS REAL)),0) FROM order_items i
               JOIN orders o ON o.id=i.order_id
               WHERE o.customer_id=? AND i.category_id=? AND COALESCE(i.batch_tag,'')=? AND o.type IN ('delivery_return','pickup_return')""",
        ).use { ps ->
            ps.setLong(1, customerId); ps.setLong(2, categoryId); ps.setString(3, batchTag)
            ps.executeQuery().use { rs -> rs.next(); BigDecimal(rs.getDouble(1).toString()) }
        }

    fun batches(c: Connection, customerId: Long, categoryId: Long): List<String> =
        c.prepareStatement(
            """SELECT DISTINCT COALESCE(i.batch_tag,'') FROM order_items i JOIN orders o ON o.id=i.order_id
               WHERE o.customer_id=? AND i.category_id=? ORDER BY 1""",
        ).use { ps ->
            ps.setLong(1, customerId); ps.setLong(2, categoryId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    // ---------- notes ----------
    fun insertNote(c: Connection, noteNo: String, refOrderNo: String, customerId: Long, accountId: Long, at: String): Long {
        c.prepareStatement("INSERT INTO order_notes(note_no,ref_order_no,customer_id,account_id,status,created_at) VALUES(?,?,?,?,'active',?)").use { ps ->
            ps.setString(1, noteNo); ps.setString(2, refOrderNo); ps.setLong(3, customerId); ps.setLong(4, accountId); ps.setString(5, at); ps.executeUpdate()
        }
        return c.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
    }

    fun insertNoteItem(c: Connection, noteId: Long, categoryId: Long, batchTag: String?, diffType: String, qty: String, remark: String?) {
        c.prepareStatement("INSERT INTO order_note_items(note_id,category_id,batch_tag,diff_type,diff_quantity,remark) VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setLong(1, noteId); ps.setLong(2, categoryId); ps.setString(3, batchTag); ps.setString(4, diffType); ps.setString(5, qty); ps.setString(6, remark); ps.executeUpdate()
        }
    }

    fun noteById(c: Connection, id: Long): NoteRow? =
        c.prepareStatement("SELECT n.id,n.note_no,n.ref_order_no,cu.name,n.status,n.created_at FROM order_notes n JOIN customers cu ON cu.id=n.customer_id WHERE n.id=?").use { ps ->
            ps.setLong(1, id); ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val noteId = rs.getLong(1)
                val items = c.prepareStatement("SELECT i.category_id,c.name,i.batch_tag,i.diff_type,i.diff_quantity,i.remark FROM order_note_items i JOIN categories c ON c.id=i.category_id WHERE i.note_id=?").use { ps2 ->
                    ps2.setLong(1, noteId); ps2.executeQuery().use { r2 ->
                        buildList { while (r2.next()) add(NoteItemRow(r2.getLong(1), r2.getString(2), r2.getString(3), r2.getString(4), r2.getString(5), r2.getString(6))) }
                    }
                }
                NoteRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), items)
            }
        }

    // ---------- print jobs ----------
    fun insertPrintJob(c: Connection, orderId: Long?, orderType: String, merchant: String, at: String, status: String, error: String?, printedAt: String?): Long {
        c.prepareStatement("INSERT INTO print_jobs(order_id,order_type,status,error_msg,merchant_name_snapshot,created_at,printed_at) VALUES(?,?,?,?,?,?,?)").use { ps ->
            ps.setObject(1, orderId); ps.setString(2, orderType); ps.setString(3, status); ps.setString(4, error); ps.setString(5, merchant)
            ps.setString(6, at); ps.setString(7, printedAt); ps.executeUpdate()
        }
        return c.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
    }

    fun printJobs(c: Connection, limit: Int = 30): List<PrintJobRow> =
        c.createStatement().use { st ->
            st.executeQuery("""SELECT j.id,o.order_no,j.order_type,j.status,j.error_msg,j.created_at,j.printed_at
                               FROM print_jobs j LEFT JOIN orders o ON o.id=j.order_id ORDER BY j.id DESC LIMIT $limit""").use { rs ->
                buildList {
                    while (rs.next()) add(PrintJobRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)))
                }
            }
        }

    fun finishPrintJob(c: Connection, jobId: Long, at: String) {
        c.prepareStatement("UPDATE print_jobs SET status='printed', printed_at=? WHERE id=?").use { ps ->
            ps.setString(1, at); ps.setLong(2, jobId); ps.executeUpdate()
        }
    }

    fun pendingPrintCount(c: Connection): Int =
        c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM print_jobs WHERE status='pending'").use { rs -> rs.next(); rs.getInt(1) } }

    // ---------- logs ----------
    fun log(c: Connection, accountId: Long?, action: String, targetType: String?, targetId: String?, detail: String?, at: String) {
        c.prepareStatement("INSERT INTO operation_logs(account_id,action,target_type,target_id,detail,created_at) VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setObject(1, accountId); ps.setString(2, action); ps.setString(3, targetType); ps.setString(4, targetId); ps.setString(5, detail); ps.setString(6, at); ps.executeUpdate()
        }
    }

    fun logs(c: Connection, limit: Int = 50): List<String> =
        c.createStatement().use { st ->
            st.executeQuery("""SELECT l.created_at,COALESCE(a.name,'-'),l.action,COALESCE(l.detail,'') FROM operation_logs l
                               LEFT JOIN accounts a ON a.id=l.account_id ORDER BY l.id DESC LIMIT $limit""").use { rs ->
                buildList { while (rs.next()) add("${rs.getString(1)}  ${rs.getString(2)}  ${rs.getString(3)}  ${rs.getString(4)}") }
            }
        }

    fun settlement(c: Connection): List<Triple<CustomerRow, BigDecimal, String>> =
        c.createStatement().use { st ->
            st.executeQuery("""SELECT cu.id,cu.name,cu.phone,COALESCE(SUM(CAST(o.order_total AS REAL)),0),COALESCE(MAX(o.created_at),'')
                               FROM customers cu LEFT JOIN orders o ON o.customer_id=cu.id
                               GROUP BY cu.id,cu.name,cu.phone ORDER BY 4 DESC""").use { rs ->
                buildList { while (rs.next()) add(Triple(CustomerRow(rs.getLong(1), rs.getString(2), rs.getString(3)), BigDecimal(rs.getDouble(4).toString()), rs.getString(5))) }
            }
        }
}

fun ResultSet.str(i: Int): String? = getString(i)
