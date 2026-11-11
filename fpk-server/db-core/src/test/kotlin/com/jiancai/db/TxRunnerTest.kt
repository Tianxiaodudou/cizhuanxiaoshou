/*
 * @file fpk-server/db-core/src/test/kotlin/com/jiancai/db/TxRunnerTest.kt
 * @role 事务验收：提交/回滚、嵌套保存点、跨模块同一事务、并发串行化。
 * @exports TxRunnerTest
 * @depends （无）
 * @calledBy （无）
 * @sideEffects 经本模块 repository 读写（SQL 收口在 repository）
 */

package com.jiancai.db

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 事务验收：提交/回滚、嵌套保存点、跨模块同一事务、并发串行化。
 *
 * 这里的 FakeOrderRepository / FakeSettlementRepository 模拟两个业务模块的 Port：
 * 它们只接收 [TxContext]，从不自己开连接、自己起事务。
 */
class TxRunnerTest {

    @Test
    fun commit_persists_writes() {
        DbCore.inMemory().use { db ->
            db.createProbeTables()
            db.txRunner.run { tx -> tx.exec("INSERT INTO probe_row (tag) VALUES ('a')") }
            assertEquals(listOf("a"), db.txRunner.run { tx -> tx.tags("probe_row") })
        }
    }

    @Test
    fun run_returns_the_block_value() {
        DbCore.inMemory().use { db ->
            assertEquals(42, db.txRunner.run { 42 })
        }
    }

    @Test
    fun failure_rolls_back_the_whole_transaction() {
        DbCore.inMemory().use { db ->
            db.createProbeTables()
            assertFailsWith<IllegalStateException> {
                db.txRunner.run { tx ->
                    tx.exec("INSERT INTO probe_row (tag) VALUES ('a')")
                    tx.exec("INSERT INTO probe_other (tag) VALUES ('b')")
                    throw IllegalStateException("boom")
                }
            }
            assertEquals(0, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_row") })
            assertEquals(0, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_other") })
            // 回滚后连接仍可用：下一条事务能正常提交。
            db.txRunner.run { tx -> tx.exec("INSERT INTO probe_row (tag) VALUES ('c')") }
            assertEquals(listOf("c"), db.txRunner.run { tx -> tx.tags("probe_row") })
        }
    }

    @Test
    fun nested_run_uses_a_savepoint_and_keeps_outer_writes() {
        DbCore.inMemory().use { db ->
            db.createProbeTables()
            var outerDepth = -1
            var innerDepth = -1
            var innerIsRoot = true
            db.txRunner.run { outer ->
                outerDepth = outer.depth
                outer.exec("INSERT INTO probe_row (tag) VALUES ('outer-1')")
                val innerResult = runCatching {
                    db.txRunner.run { nested ->
                        innerDepth = nested.depth
                        innerIsRoot = nested.isRoot
                        nested.exec("INSERT INTO probe_row (tag) VALUES ('inner')")
                        throw IllegalStateException("inner boom")
                    }
                }
                assertTrue(innerResult.isFailure, "内层异常必须抛给调用方，由调用方决定是否吞掉")
                outer.exec("INSERT INTO probe_row (tag) VALUES ('outer-2')")
            }
            assertEquals(1, outerDepth)
            assertEquals(2, innerDepth)
            assertFalse(innerIsRoot)
            assertEquals(listOf("outer-1", "outer-2"), db.txRunner.run { tx -> tx.tags("probe_row") })
        }
    }

    @Test
    fun outer_rollback_discards_inner_joined_work() {
        DbCore.inMemory().use { db ->
            db.createProbeTables()
            assertFailsWith<IllegalStateException> {
                db.txRunner.run { outer ->
                    outer.exec("INSERT INTO probe_row (tag) VALUES ('a')")
                    // 另一个模块的 Port 在同一事务内写自己的表：它不会提前提交。
                    db.txRunner.run { inner -> inner.exec("INSERT INTO probe_other (tag) VALUES ('b')") }
                    throw IllegalStateException("outer boom")
                }
            }
            assertEquals(0, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_row") })
            assertEquals(0, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_other") })
        }
    }

    @Test
    fun cross_module_writes_are_wrapped_in_one_transaction() {
        DbCore.inMemory().use { db ->
            db.createProbeTables()
            val order = FakeOrderRepository()
            val settlement = FakeSettlementRepository()

            db.txRunner.run { tx ->
                order.insert(tx, "order-1")
                settlement.insert(tx, "settle-1")
            }
            assertEquals(1, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_row") })
            assertEquals(1, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_other") })

            assertFailsWith<IllegalStateException> {
                db.txRunner.run { tx ->
                    order.insert(tx, "order-2")
                    settlement.insert(tx, "settle-2")
                    throw IllegalStateException("settlement rejected")
                }
            }
            assertEquals(1, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_row") })
            assertEquals(1, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_other") })
            assertEquals(listOf("order-1"), db.txRunner.run { tx -> tx.tags("probe_row") })
            assertEquals(listOf("settle-1"), db.txRunner.run { tx -> tx.tags("probe_other") })
        }
    }

    @Test
    fun concurrent_writers_are_serialized_on_the_single_connection() {
        DbCore.inMemory().use { db ->
            db.createProbeTables()
            val workers = 4
            val perWorker = 25
            val start = CountDownLatch(1)
            val done = CountDownLatch(workers)
            val failures = Collections.synchronizedList(ArrayList<Throwable>())

            repeat(workers) { worker ->
                thread(name = "tx-writer-$worker") {
                    try {
                        start.await()
                        repeat(perWorker) { index ->
                            db.txRunner.run { tx ->
                                tx.exec("INSERT INTO probe_row (tag) VALUES (?)", "w$worker-$index")
                            }
                        }
                    } catch (failure: Throwable) {
                        failures += failure
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发写入超时：可能存在自锁")
            assertTrue(failures.isEmpty(), "并发写入出现异常：${failures.firstOrNull()}")
            assertEquals(workers * perWorker, db.txRunner.run { tx -> tx.queryInt("SELECT COUNT(*) FROM probe_row") })
        }
    }
}

/** 测试替身：模拟 S08 订单模块的 Port（只接收事务上下文，不自己开连接/事务）。 */
private class FakeOrderRepository {
    fun insert(tx: TxContext, tag: String) = tx.exec("INSERT INTO probe_row (tag) VALUES (?)", tag)
}

/** 测试替身：模拟 S11 结算模块的 Port。 */
private class FakeSettlementRepository {
    fun insert(tx: TxContext, tag: String) = tx.exec("INSERT INTO probe_other (tag) VALUES (?)", tag)
}
