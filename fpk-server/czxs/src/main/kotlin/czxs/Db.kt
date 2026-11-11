package czxs

import java.sql.Connection
import java.sql.DriverManager

/** 单库 data/orders.sq3（规格 2），只允许 repository 层写 SQL（规格 3）。 */
class Db(private val path: String) {

    fun connection(): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite:$path")
        c.autoCommit = false
        c.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        return c
    }

    fun <T> tx(block: (Connection) -> T): T = connection().use { c ->
        try {
            val out = block(c)
            c.commit()
            out
        } catch (e: Throwable) {
            runCatching { c.rollback() }
            throw e
        }
    }

    /** 建表 + 默认种子；幂等。 */
    fun migrate() = tx { c ->
        c.createStatement().use { st ->
            listOf(
                """CREATE TABLE IF NOT EXISTS accounts(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL, password_hash TEXT NOT NULL, role TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'active', last_login_at TEXT, created_at TEXT NOT NULL)""",
                """CREATE TABLE IF NOT EXISTS settings(
                    key TEXT PRIMARY KEY, value TEXT NOT NULL)""",
                """CREATE TABLE IF NOT EXISTS categories(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, grp TEXT NOT NULL, name TEXT NOT NULL, spec TEXT NOT NULL,
                    batch_type TEXT NOT NULL, batch_required INTEGER NOT NULL DEFAULT 1,
                    status TEXT NOT NULL DEFAULT 'active', created_by INTEGER, created_at TEXT NOT NULL,
                    audited_by INTEGER, audited_at TEXT, reject_reason TEXT,
                    UNIQUE(grp, name, spec))""",
                """CREATE TABLE IF NOT EXISTS category_units(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, category_id INTEGER NOT NULL,
                    unit TEXT NOT NULL, conversion REAL NOT NULL, is_settlement INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(category_id, unit))""",
                """CREATE TABLE IF NOT EXISTS customers(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT NOT NULL UNIQUE,
                    created_at TEXT NOT NULL)""",
                """CREATE TABLE IF NOT EXISTS customer_prices(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id INTEGER NOT NULL, category_id INTEGER NOT NULL,
                    unit_price TEXT NOT NULL, updated_at TEXT NOT NULL, updated_by INTEGER,
                    UNIQUE(customer_id, category_id))""",
                """CREATE TABLE IF NOT EXISTS orders(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, order_no TEXT NOT NULL UNIQUE, type TEXT NOT NULL,
                    customer_id INTEGER NOT NULL, account_id INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'submitted',
                    client_request_id TEXT NOT NULL UNIQUE, remark TEXT, order_total TEXT NOT NULL,
                    version INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, print_count INTEGER NOT NULL DEFAULT 0,
                    printed_at TEXT)""",
                """CREATE TABLE IF NOT EXISTS order_items(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL, line_no INTEGER NOT NULL,
                    category_id INTEGER NOT NULL, batch_tag TEXT, input_unit TEXT NOT NULL, input_quantity TEXT NOT NULL,
                    settlement_unit TEXT NOT NULL, settlement_quantity TEXT NOT NULL, unit_price TEXT NOT NULL,
                    line_total TEXT NOT NULL)""",
                """CREATE TABLE IF NOT EXISTS order_notes(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, note_no TEXT NOT NULL UNIQUE, ref_order_no TEXT NOT NULL,
                    customer_id INTEGER NOT NULL, account_id INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'active',
                    created_at TEXT NOT NULL)""",
                """CREATE TABLE IF NOT EXISTS order_note_items(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, note_id INTEGER NOT NULL, category_id INTEGER NOT NULL,
                    batch_tag TEXT, diff_type TEXT NOT NULL, diff_quantity TEXT NOT NULL, remark TEXT)""",
                """CREATE TABLE IF NOT EXISTS print_jobs(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER, order_type TEXT NOT NULL,
                    status TEXT NOT NULL, error_msg TEXT, merchant_name_snapshot TEXT NOT NULL,
                    created_at TEXT NOT NULL, printed_at TEXT)""",
                """CREATE TABLE IF NOT EXISTS order_revisions(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER NOT NULL, version INTEGER NOT NULL,
                    snapshot_json TEXT NOT NULL, account_id INTEGER, created_at TEXT NOT NULL)""",
                """CREATE TABLE IF NOT EXISTS operation_logs(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER, action TEXT NOT NULL,
                    target_type TEXT, target_id TEXT, detail TEXT, created_at TEXT NOT NULL)""",
                """CREATE TABLE IF NOT EXISTS api_tokens(
                    token TEXT PRIMARY KEY, account_id INTEGER NOT NULL, created_at TEXT NOT NULL)""",
                """CREATE TABLE IF NOT EXISTS admin_sessions(
                    token TEXT PRIMARY KEY, account_id INTEGER NOT NULL, created_at TEXT NOT NULL)""",
            ).forEach { st.execute(it) }
        }
        now()
    }

    fun now(): String = java.time.OffsetDateTime.now().toString()
}
