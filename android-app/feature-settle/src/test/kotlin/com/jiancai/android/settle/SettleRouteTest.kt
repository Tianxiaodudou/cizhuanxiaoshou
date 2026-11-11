/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   37 行  类 SettleRouteTest
 * 第   39 行  类 FakeClient
 * 第   41 行  函数 call
 * 第   47 行  函数 serverAddress
 * 第   51 行  函数 a_successful_upload_is_echoed_from_the_server_response
 * 第   63 行  函数 a_server_rejection_is_echoed_verbatim_with_its_code
 * 第   75 行  函数 obvious_input_errors_are_stopped_locally
 * 第   84 行  函数 the_client_never_decides_whether_the_settlement_is_valid
 * 第   97 行  函数 the_amount_is_shown_read_only_and_comes_from_the_server
 * 第  103 行  函数 sampleSettle
 */

package com.jiancai.android.settle

import com.jiancai.android.network.ApiClient
import com.jiancai.android.network.ApiRequestSpec
import com.jiancai.android.network.ApiResult
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.enums.SettleDirection
import com.jiancai.shared.enums.SettleStatus
import com.jiancai.shared.errors.ErrorCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C10 验收：上传与回显正确。
 *
 * 断言：成功只以服务端返回的结算单为准；失败原样回显；客户端不做金额比较 / 不做结算判定。
 */
class SettleRouteTest {

    private class FakeClient(private val result: ApiResult<SettleDto>) : ApiClient {
        val requests = mutableListOf<ApiRequestSpec<*>>()
        override fun <T> call(spec: ApiRequestSpec<T>): ApiResult<T> {
            requests += spec
            @Suppress("UNCHECKED_CAST")
            return result as ApiResult<T>
        }

        override fun serverAddress(): String = "http://127.0.0.1:8080"
    }

    @Test
    fun a_successful_upload_is_echoed_from_the_server_response() {
        val client = FakeClient(ApiResult.Success(sampleSettle(SettleStatus.SUBMITTED)))
        val outcome = SettleRoute.submit(client, "PS1", SettleDirection.RECEIPT, "100.00", "现金", "s-1")

        assertTrue(outcome.success)
        assertEquals(SettleStatus.SUBMITTED, outcome.settle?.status)
        assertEquals("/settlements", client.requests.single().path)
        assertEquals("s-1", client.requests.single().idempotencyKey)
        assertTrue(client.requests.single().bodyJson!!.contains("\"amount\":\"100.00\""))
    }

    @Test
    fun a_server_rejection_is_echoed_verbatim_with_its_code() {
        val client = FakeClient(
            ApiResult.Failure("E5002", ErrorCode.E5002, "结算金额超过未收金额", 422),
        )
        val outcome = SettleRoute.submit(client, "PS1", SettleDirection.RECEIPT, "999.00", null, "s-2")

        assertFalse(outcome.success)
        assertEquals(ErrorCode.E5002, outcome.failure?.code)
        assertEquals("结算金额超过未收金额", outcome.failure?.message)
    }

    @Test
    fun obvious_input_errors_are_stopped_locally() {
        val client = FakeClient(ApiResult.Success(sampleSettle(SettleStatus.SUBMITTED)))
        assertFalse(SettleRoute.submit(client, "", SettleDirection.RECEIPT, "1.00", null, "s-1").success)
        assertFalse(SettleRoute.submit(client, "PS1", SettleDirection.RECEIPT, "abc", null, "s-1").success)
        assertFalse(SettleRoute.submit(client, "PS1", SettleDirection.RECEIPT, "1.00", null, "  ").success)
        assertEquals(0, client.requests.size, "本地校验失败不得发请求")
    }

    @Test
    fun the_client_never_decides_whether_the_settlement_is_valid() {
        val sources = File("src/main/kotlin/com/jiancai/android/settle").listFiles()!!
            .filter { it.extension == "kt" }
        val text = sources.joinToString("\n") { it.readText() }

        listOf("compareTo", "isGreaterThan", "> amount", "receivedAmount +", "SettleStatus.APPROVED").forEach { forbidden ->
            assertFalse(text.contains(forbidden), "结算判定属于服务端（发现 $forbidden）")
        }
        // 成功与否只看服务端返回值：SettleOutcome.success 只判断 settle != null
        assertTrue(text.contains("val success: Boolean get() = settle != null"))
    }

    @Test
    fun the_amount_is_shown_read_only_and_comes_from_the_server() {
        val fields = SettleRoute.screen().fields
        assertTrue(fields.single { it.name == "unreceived_amount" }.readOnly)
        assertFalse(fields.single { it.name == "amount" }.readOnly, "用户填写的金额可编辑")
    }

    private fun sampleSettle(status: SettleStatus): SettleDto = SettleDto(
        settleNo = "JS202611110001",
        orderNo = "PS202611110001",
        customerId = 11,
        customerName = "张三",
        direction = SettleDirection.RECEIPT,
        amount = Money.ofPlain("100.00"),
        status = status,
        submittedAt = "2026-11-11T09:00:00+08:00",
        submittedBy = 2,
    )
}
