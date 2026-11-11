/*
 * @file fpk-server/db-core/src/main/kotlin/com/jiancai/db/TxContext.kt
 * @role 事务上下文：跨模块写操作的唯一事务入口，由 [TxRunner.run] 产生并传给各模块 Port 方法。
 * @exports TxContext
 * @depends （无）
 * @calledBy fpk-server/account/src/main/kotlin/com/jiancai/account/AccountAdminPort.kt, fpk-server/account/src/main/kotlin/com/jiancai/account/AccountAdminService.kt, fpk-server/account/src/main/kotlin/com/jiancai/account/AccountStore.kt
 * @sideEffects 经本模块 repository 读写（SQL 收口在 repository）
 */

package com.jiancai.db

import java.sql.Connection

/**
 * 事务上下文：跨模块写操作的唯一事务入口，由 [TxRunner.run] 产生并传给各模块 Port 方法。
 *
 * 用法（模块 repository 只拿它执行 SQL）：
 * ```
 * class CustomerRepository(private val txRunner: TxRunner) { ... }
 * fun CustomerRepository.insert(tx: TxContext, dto: CustomerDto) { ... }
 * ```
 *
 * 硬边界：
 * - 只能由 db-core 构造（`internal constructor`），其它模块无法伪造事务；
 * - 不得缓存、不得跨界到别的线程、不得在 [TxRunner.run] 之外使用；
 * - [connection] 只供**各模块自己的 repository** 执行 SQL（铁律 10），
 *   禁止调用 `commit` / `rollback` / `setAutoCommit` / `close` —— 事务边界只属于 [TxRunner]。
 */
class TxContext internal constructor(
    /** 当前事务使用的唯一连接（repository 专用）。 */
    val connection: Connection,
    /** 嵌套层级：1 = 最外层真实事务，>1 = 同一事务内的保存点（见 [TxRunner.run]）。 */
    val depth: Int,
) {
    /** 是否为最外层事务；只有它能真正提交。 */
    val isRoot: Boolean get() = depth == 1

    override fun toString(): String = "TxContext(depth=$depth)"
}
