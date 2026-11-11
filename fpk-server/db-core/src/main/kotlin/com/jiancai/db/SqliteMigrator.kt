/*
 * @file fpk-server/db-core/src/main/kotlin/com/jiancai/db/SqliteMigrator.kt
 * @role [Migrator] 的唯一实现。
 * @exports （无）
 * @depends （无）
 * @calledBy fpk-server/db-core/src/main/kotlin/com/jiancai/db/DbCore.kt
 * @sideEffects 写 IF（INSERT / UPDATE / DELETE）
 */

package com.jiancai.db

import java.sql.SQLException
import java.time.Instant

/**
 * [Migrator] 的唯一实现。
 *
 * 幂等性的两条防线：
 * 1. 每步先按版本号查账本表，已应用就只核对 checksum 并跳过（同进程重复调用、重启后重复调用都成立）；
 * 2. 账本表 `version` 是主键 + `CREATE TABLE IF NOT EXISTS`，即便并发调用也不会重复记账。
 *
 * 迁移内容一经上线不可改：账本里的 checksum 与代码不一致时抛 [MigrationDriftException]，
 * 而不是「悄悄重跑」——静默重跑会让已经改了结构的库和没改的库走成两种状态。
 */
internal class SqliteMigrator(
    private val txRunner: TxRunner,
    migrations: List<Migration>,
    private val now: () -> Instant = Instant::now,
) : Migrator {

    private val repository = SchemaMigrationRepository()

    /** 应用顺序：严格按版本号升序（与传入顺序无关，各模块各自贡献迁移也不会乱序）。 */
    private val ordered: List<Migration> = migrations.sortedBy { it.version }

    init {
        val duplicatedVersions = ordered.groupBy { it.version }.filterValues { it.size > 1 }.keys
        require(duplicatedVersions.isEmpty()) { "迁移版本号重复：$duplicatedVersions（版本号全局唯一，段位分配见 module.md）" }
        val duplicatedNames = ordered.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicatedNames.isEmpty()) { "迁移名称重复：$duplicatedNames" }
    }

    override fun migrate(): MigrationReport {
        val startedAt = System.nanoTime()
        // 先单独建账本表：拿写锁的顺序固定，跨进程并发迁移时不会出现「读快照升级写锁」的 BUSY。
        txRunner.run { tx -> repository.ensureTable(tx) }

        val applied = ArrayList<Int>()
        val skipped = ArrayList<Int>()
        for (migration in ordered) {
            val wasApplied = txRunner.run { tx ->
                val existing = repository.find(tx, migration.version)
                if (existing != null) {
                    requireSameContent(existing, migration)
                    false
                } else {
                    val migrationStartedAt = System.nanoTime()
                    executeStatements(tx, migration)
                    val tookMs = (System.nanoTime() - migrationStartedAt) / 1_000_000
                    repository.insert(tx, migration, appliedAt = now().toString(), tookMs = tookMs)
                    true
                }
            }
            if (wasApplied) applied += migration.version else skipped += migration.version
        }

        return MigrationReport(
            applied = applied,
            skipped = skipped,
            currentVersion = currentVersion(),
            tookMs = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    override fun currentVersion(): Int = txRunner.run { tx ->
        repository.ensureTable(tx)
        repository.maxVersion(tx)
    }

    override fun pendingVersions(): List<Int> = txRunner.run { tx ->
        repository.ensureTable(tx)
        val appliedVersions = repository.applied(tx).map { it.version }.toSet()
        ordered.map { it.version }.filterNot(appliedVersions::contains)
    }

    override fun drift(): List<MigrationDrift> = txRunner.run { tx ->
        repository.ensureTable(tx)
        val recorded = repository.applied(tx).associateBy { it.version }
        ordered.mapNotNull { migration ->
            val row = recorded[migration.version] ?: return@mapNotNull null
            if (row.checksum == migration.checksum) {
                null
            } else {
                MigrationDrift(
                    version = migration.version,
                    name = migration.name,
                    recorded = row.checksum,
                    current = migration.checksum,
                )
            }
        }
    }

    private fun requireSameContent(
        existing: SchemaMigrationRepository.AppliedMigration,
        migration: Migration,
    ) {
        if (existing.checksum == migration.checksum) return
        throw MigrationDriftException(
            version = migration.version,
            expected = existing.checksum,
            current = migration.checksum,
        )
    }

    private fun executeStatements(tx: TxContext, migration: Migration) {
        for (statement in migration.statements) {
            try {
                tx.connection.createStatement().use { sql -> sql.execute(statement) }
            } catch (failure: SQLException) {
                // 具体 SQL 不进异常消息（避免把建表语句泄进日志/响应），只留版本号与原因。
                throw MigrationFailedException(migration.version, migration.name, failure)
            }
        }
    }
}
