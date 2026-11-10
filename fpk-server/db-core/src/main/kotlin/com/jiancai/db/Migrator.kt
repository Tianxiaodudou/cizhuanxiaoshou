package com.jiancai.db

/**
 * 迁移 Port：把各模块的 [Migration] 按版本号应用到一个库里，并保证**幂等**。
 *
 * 幂等口径：同一个库上重复调用 [migrate] 不产生任何副作用——
 * 已应用的版本只核对 checksum 后跳过，报告里落在 [MigrationReport.skipped]。
 *
 * 事务口径：每条迁移单独一个事务（DDL 在 SQLite 中可回滚），
 * 因此「第 3 条迁移失败」不会污染第 1、2 条已落库的结果，也不会留下半张表。
 */
interface Migrator {
    /**
     * 应用所有未应用的迁移（按版本号升序）。
     *
     * @throws MigrationDriftException 库中已记录的迁移与代码中的迁移内容不一致（拒绝继续，避免结构性错乱）。
     * @throws MigrationFailedException 某条迁移执行失败；该条整体回滚，之前的迁移保持已应用。
     */
    fun migrate(): MigrationReport

    /** 当前库的迁移版本（无迁移时为 0）。 */
    fun currentVersion(): Int

    /** 尚未应用的迁移版本号，升序。 */
    fun pendingVersions(): List<Int>

    /** 已应用但内容已变更的迁移（空列表 = 正常）；只诊断、不修改库。 */
    fun drift(): List<MigrationDrift>
}

/**
 * 一次 [Migrator.migrate] 的结果。
 *
 * @property applied 本次实际应用的版本号（升序）。
 * @property skipped 本次核对后跳过的已应用版本号（升序）——幂等性的直接证据。
 * @property currentVersion 执行后的库版本。
 * @property tookMs 总耗时（毫秒）。
 */
data class MigrationReport(
    val applied: List<Int>,
    val skipped: List<Int>,
    val currentVersion: Int,
    val tookMs: Long,
) {
    /** 本次是否真的改了库结构。 */
    val changed: Boolean get() = applied.isNotEmpty()
}

/**
 * 迁移漂移诊断项。
 *
 * @property version 迁移版本号。
 * @property name 代码中的迁移名称。
 * @property recorded 库中记录的 checksum。
 * @property current 代码中的 checksum。
 */
data class MigrationDrift(
    val version: Int,
    val name: String,
    val recorded: String,
    val current: String,
)
