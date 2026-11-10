package com.jiancai.serial

import com.jiancai.db.DbCore
import com.jiancai.shared.serial.SerialType
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验收：并发取号不重号。
 *
 * 多个线程同时对同一前缀取号，全部经 db-core 的 TxRunner 事务；
 * 断言总数、去重后数量与「1..N 连续序号」三者一致（既不重号、也不跳号）。
 */
class SerialConcurrencyTest {

    @Test
    fun concurrent_calls_never_duplicate_a_serial_number() {
        val threads = 8
        val perThread = 50
        val total = threads * perThread

        DbCore.testDatabase(SerialMigrations.all).use { db ->
            val serial = SerialService(db.txRunner, clockAt("2026-11-10T02:00:00Z"))
            val results = Collections.synchronizedList(ArrayList<String>(total))
            val failure = AtomicReference<Throwable?>(null)
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads)

            repeat(threads) { index ->
                thread(name = "serial-taker-$index") {
                    try {
                        start.await()
                        repeat(perThread) { results += serial.next(SerialType.PURCHASE) }
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    } finally {
                        done.countDown()
                    }
                }
            }

            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发取号超时（可能存在死锁）")
            assertNull(failure.get(), "并发取号抛出异常：${failure.get()}")
            assertEquals(total, results.size)
            assertEquals(total, results.toSet().size, "出现重复单号")

            val expected = (1..total)
                .map { "PS20261110" + it.toString().padStart(4, '0') }
                .toSet()
            assertEquals(expected, results.toSet(), "序号必须连续且完整（不能跳号或重号）")
        }
    }
}
