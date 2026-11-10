package com.jiancai.db

import java.sql.ResultSet

/**
 * `schema_migration` 的读写包 —— db-core **自己的表、自己的 repository**。
 *
 * 除这里之外 db-core 不出现任何 SQL（迁移语句由使用方以 [Migration.statements] 提供，
 * 属于各模块自己的 DDL），从而在模块内部也守住「SQL 只出现在 repository」这条铁律。
 */
internal class SchemaMigrationRepository {

    /** 建账本表（幂等）：`IF NOT EXISTS` + 版本号主键，重复执行不会报错也不会重复记账。 */
    fun ensureTable(tx: TxContext) {
        tx.connection.createStatement().use { statement -> statement.execute(SQL_CREATE_TABLE) }
    }

    fun find(tx: TxContext, version: Int): AppliedMigration? =
        tx.connection.prepareStatement(SQL_SELECT_ONE).use { statement ->
            statement.setInt(1, version)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toApplied() else null }
        }

    fun applied(tx: TxContext): List<AppliedMigration> =
        tx.connection.prepareStatement(SQL_SELECT_ALL).use { statement ->
            statement.executeQuery().use { rows ->
                val result = ArrayList<AppliedMigration>()
                while (rows.next()) result += rows.toApplied()
                result
            }
        }

    fun maxVersion(tx: TxContext): Int =
        tx.connection.prepareStatement(SQL_SELECT_MAX).use { statement ->
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }

    fun insert(tx: TxContext, migration: Migration, appliedAt: String, tookMs: Long) {
        tx.connection.prepareStatement(SQL_INSERT).use { statement ->
            statement.setInt(1, migration.version)
            statement.setString(2, migration.name)
            statement.setString(3, migration.checksum)
            statement.setString(4, appliedAt)
            statement.setLong(5, tookMs)
            statement.executeUpdate()
        }
    }

    /** 已应用的一条迁移记录。 */
    data class AppliedMigration(
        val version: Int,
        val name: String,
        val checksum: String,
        val appliedAt: String,
    )

    private fun ResultSet.toApplied(): AppliedMigration = AppliedMigration(
        version = getInt(1),
        name = getString(2),
        checksum = getString(3),
        appliedAt = getString(4),
    )

    private companion object {
        const val SQL_CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS schema_migration (
                version    INTEGER NOT NULL PRIMARY KEY,
                name       TEXT    NOT NULL,
                checksum   TEXT    NOT NULL,
                applied_at TEXT    NOT NULL,
                took_ms    INTEGER NOT NULL
            )
        """

        const val SQL_SELECT_ALL = "SELECT version, name, checksum, applied_at FROM schema_migration ORDER BY version"
        const val SQL_SELECT_ONE = "SELECT version, name, checksum, applied_at FROM schema_migration WHERE version = ?"
        const val SQL_SELECT_MAX = "SELECT COALESCE(MAX(version), 0) FROM schema_migration"
        const val SQL_INSERT =
            "INSERT INTO schema_migration (version, name, checksum, applied_at, took_ms) VALUES (?, ?, ?, ?, ?)"
    }
}
