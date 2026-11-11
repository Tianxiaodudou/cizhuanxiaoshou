/*
 * @file fpk-server/db-core/src/main/kotlin/com/jiancai/db/TxRunner.kt
 * @role 事务 Port：**所有**写操作的唯一入口（铁律 4）。
 * @exports TxRunner
 * @depends （无）
 * @calledBy fpk-server/account/src/main/kotlin/com/jiancai/account/AccountAdminService.kt, fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayDeps.kt, fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/SqliteIdempotencyStore.kt
 * @sideEffects 经本模块 repository 读写（SQL 收口在 repository）
 */

package com.jiancai.db

/**
 * 事务 Port：**所有**写操作的唯一入口（铁律 4）。
 *
 * 语义：
 * - 最外层 [run] 开启真实事务（`autoCommit=false`，结束提交/回滚）；
 * - 同一线程内再次 [run] 复用同一事务，用 SAVEPOINT 做嵌套：内层失败只回滚内层，
 *   异常继续向外抛，最外层失败才整体回滚（跨模块调用因此天然原子）；
 * - 不同线程排队（单连接 + 全局锁），因此不会出现 SQLITE_BUSY，也不会丢更新；
 * - 禁止在事务块内起线程并等待它（会自锁）。
 *
 * 典型用法（组合根注入给各模块）：
 * ```
 * txRunner.run { tx ->
 *     orderRepository.insert(tx, order)      // S08
 *     settlementPort.record(tx, settlement)  // S11：同一个事务
 * }
 * ```
 */
interface TxRunner {
    /**
     * 在一个事务中执行 [block]。
     *
     * @return [block] 的返回值（DTO / 标量，不返回 Entity）。
     * @throws DbException 连接已关闭等数据库层故障；[block] 自身的异常原样上抛，并回滚事务。
     */
    fun <T> run(block: (TxContext) -> T): T
}
