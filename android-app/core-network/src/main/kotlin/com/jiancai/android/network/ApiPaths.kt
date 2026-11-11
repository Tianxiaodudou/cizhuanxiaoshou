/*
 * @file android-app/core-network/src/main/kotlin/com/jiancai/android/network/ApiPaths.kt
 * @role 客户端唯一的接口清单（路径 + 方法 + 序列化器 + 幂等键来源）。
 * @exports ApiPaths
 * @depends shared
 * @calledBy android-app/core-session/src/main/kotlin/com/jiancai/android/session/SessionGate.kt, android-app/feature-admin/src/main/kotlin/com/jiancai/android/admin/AdminRoute.kt, android-app/feature-note/src/main/kotlin/com/jiancai/android/note/NoteRoute.kt
 * @sideEffects 无（纯计算 / 转发）
 */

package com.jiancai.android.network

import com.jiancai.shared.ContractJson
import com.jiancai.shared.dto.AccountDto
import com.jiancai.shared.dto.AuditSettleCmd
import com.jiancai.shared.dto.CategoryCmd
import com.jiancai.shared.dto.CategoryDto
import com.jiancai.shared.dto.ChangePriceCmd
import com.jiancai.shared.dto.ContractInfoDto
import com.jiancai.shared.dto.CreateAccountCmd
import com.jiancai.shared.dto.CreateCustomerCmd
import com.jiancai.shared.dto.CreateNoteCmd
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.dto.LoginCmd
import com.jiancai.shared.dto.LoginResultDto
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.MoneySerializer
import com.jiancai.shared.dto.NoteDto
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderLineCmd
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.PageDto
import com.jiancai.shared.dto.PriceDto
import com.jiancai.shared.dto.ReceivableDto
import com.jiancai.shared.dto.ReturnCheckResultDto
import com.jiancai.shared.dto.ReturnableBatchDto
import com.jiancai.shared.dto.SettleDto
import com.jiancai.shared.dto.SubmitSettleCmd
import com.jiancai.shared.dto.UpdateAccountCmd
import com.jiancai.shared.dto.UpdateOrderCmd
import com.jiancai.shared.dto.VoidNoteCmd
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer

/**
 * 客户端唯一的接口清单（路径 + 方法 + 序列化器 + 幂等键来源）。
 *
 * 硬边界：
 * - **路径只能写在这里**：界面层不得就地拼字符串（否则 S13 改路由时客户端静默 404）；
 * - 请求体一律是 `shared` 的 Cmd，序列化走 `ContractJson`（两端同一份线格式）；
 * - 幂等键直接用 Cmd 的 `client_request_id`（与 S08 / S11 的服务端唯一约束同一口径）；
 * - 金额 / 单号 / 状态都由服务端返回，客户端只搬运。
 */
object ApiPaths {

    // —— 契约 / 登录 ——
    fun contract(): ApiRequestSpec<ContractInfoDto> = get(
        "/contract",
        ContractInfoDto.serializer(),
    )

    fun login(cmd: LoginCmd): ApiRequestSpec<LoginResultDto> = post(
        "/auth/login",
        cmd,
        LoginCmd.serializer(),
        LoginResultDto.serializer(),
    )

    fun me(): ApiRequestSpec<AccountDto> = get("/me", AccountDto.serializer())

    // —— S03 账号（仅管理员；权限由服务端拦截） ——
    fun accounts(): ApiRequestSpec<List<AccountDto>> = get(
        "/accounts",
        ListSerializer(AccountDto.serializer()),
    )

    fun account(id: Long): ApiRequestSpec<AccountDto> = get("/accounts/$id", AccountDto.serializer())

    fun createAccount(cmd: CreateAccountCmd): ApiRequestSpec<AccountDto> = post(
        "/accounts",
        cmd,
        CreateAccountCmd.serializer(),
        AccountDto.serializer(),
    )

    fun updateAccount(cmd: UpdateAccountCmd): ApiRequestSpec<AccountDto> = put(
        "/accounts/${cmd.id}",
        cmd,
        UpdateAccountCmd.serializer(),
        AccountDto.serializer(),
    )

    fun deleteAccount(id: Long): ApiRequestSpec<Unit> = delete("/accounts/$id")

    // —— S04 客户 ——
    fun customerByPhone(phone: String): ApiRequestSpec<CustomerDto> = get(
        "/customers",
        CustomerDto.serializer(),
        mapOf("phone" to phone),
    )

    fun createCustomer(cmd: CreateCustomerCmd): ApiRequestSpec<CustomerDto> = post(
        "/customers",
        cmd,
        CreateCustomerCmd.serializer(),
        CustomerDto.serializer(),
    )

    // —— S05 型号库 ——
    fun categories(enabledOnly: Boolean = false): ApiRequestSpec<List<CategoryDto>> = get(
        "/categories",
        ListSerializer(CategoryDto.serializer()),
        mapOf("enabled_only" to enabledOnly.toString()),
    )

    fun createCategory(cmd: CategoryCmd): ApiRequestSpec<CategoryDto> = post(
        "/categories",
        cmd,
        CategoryCmd.serializer(),
        CategoryDto.serializer(),
    )

    fun updateCategory(cmd: CategoryCmd): ApiRequestSpec<CategoryDto> = put(
        "/categories/${cmd.id}",
        cmd,
        CategoryCmd.serializer(),
        CategoryDto.serializer(),
    )

    fun deleteCategory(id: Long): ApiRequestSpec<Unit> = delete("/categories/$id")

    // —— S06 定价 ——
    fun price(customerId: Long, categoryId: Long, batchTag: String): ApiRequestSpec<PriceDto> = get(
        "/prices",
        PriceDto.serializer(),
        mapOf(
            "customer_id" to customerId.toString(),
            "category_id" to categoryId.toString(),
            "batch_tag" to batchTag,
        ),
    )

    fun changePrice(cmd: ChangePriceCmd): ApiRequestSpec<PriceDto> = post(
        "/prices/change",
        cmd,
        ChangePriceCmd.serializer(),
        PriceDto.serializer(),
    )

    // —— S08 订单 ——
    fun todayOrders(): ApiRequestSpec<List<OrderDto>> = get(
        "/orders/today",
        ListSerializer(OrderDto.serializer()),
    )

    fun orders(query: OrderQuery): ApiRequestSpec<PageDto<OrderDto>> = get(
        "/orders",
        PageDto.serializer(OrderDto.serializer()),
        buildMap {
            query.phone?.let { put("phone", it) }
            query.customerId?.let { put("customer_id", it.toString()) }
            query.orderType?.let { put("order_type", it.name) }
            query.fromDate?.let { put("from_date", it) }
            query.toDate?.let { put("to_date", it) }
            query.orderNo?.let { put("order_no", it) }
            put("page", query.page.toString())
            put("page_size", query.pageSize.toString())
        },
    )

    fun orderReceivable(orderNo: String): ApiRequestSpec<Money> = get(
        "/orders/$orderNo/receivable",
        MoneySerializer,
    )

    fun createOrder(cmd: CreateOrderCmd): ApiRequestSpec<OrderDto> = post(
        "/orders",
        cmd,
        CreateOrderCmd.serializer(),
        OrderDto.serializer(),
        idempotencyKey = cmd.clientRequestId,
    )

    fun updateOrder(cmd: UpdateOrderCmd): ApiRequestSpec<OrderDto> = put(
        "/orders/${cmd.orderNo}",
        cmd,
        UpdateOrderCmd.serializer(),
        OrderDto.serializer(),
    )

    // —— S09 退单校验 ——
    fun returnableBatches(orderNo: String): ApiRequestSpec<List<ReturnableBatchDto>> = get(
        "/orders/$orderNo/returnable-batches",
        ListSerializer(ReturnableBatchDto.serializer()),
    )

    fun returnCheck(orderNo: String, lines: List<OrderLineCmd>): ApiRequestSpec<ReturnCheckResultDto> = post(
        "/orders/$orderNo/return-check",
        lines,
        ListSerializer(OrderLineCmd.serializer()),
        ReturnCheckResultDto.serializer(),
    )

    // —— S10 差异备注单 ——
    fun createNote(cmd: CreateNoteCmd, clientRequestId: String): ApiRequestSpec<NoteDto> = post(
        "/notes",
        cmd,
        CreateNoteCmd.serializer(),
        NoteDto.serializer(),
        idempotencyKey = clientRequestId,
    )

    fun voidNote(cmd: VoidNoteCmd): ApiRequestSpec<NoteDto> = post(
        "/notes/${cmd.noteNo}/void",
        cmd,
        VoidNoteCmd.serializer(),
        NoteDto.serializer(),
    )

    // —— S11 结算 ——
    fun submitSettlement(cmd: SubmitSettleCmd): ApiRequestSpec<SettleDto> = post(
        "/settlements",
        cmd,
        SubmitSettleCmd.serializer(),
        SettleDto.serializer(),
        idempotencyKey = cmd.clientRequestId,
    )

    fun auditSettlement(cmd: AuditSettleCmd): ApiRequestSpec<SettleDto> = post(
        "/settlements/${cmd.settleNo}/audit",
        cmd,
        AuditSettleCmd.serializer(),
        SettleDto.serializer(),
    )

    fun balance(customerId: Long): ApiRequestSpec<ReceivableDto> = get(
        "/customers/$customerId/balance",
        ReceivableDto.serializer(),
    )

    // —— S12 打印（服务端渲染好的 80mm 文本；客户端零打印） ——

    fun printTicket(docNo: String): ApiRequestSpec<String> = ApiRequestSpec(
        method = HttpMethod.GET,
        path = "/print/$docNo",
        serializer = null,
    )

    /** 原始文本响应（S12 的 80mm 票据就是 `text/plain`，不是 JSON）。 */
    private fun getRaw(path: String, query: Map<String, String> = emptyMap()): ApiRequestSpec<String> =
        ApiRequestSpec(
            method = HttpMethod.GET,
            path = path,
            query = query,
            serializer = null,
        )

    private fun <T> get(
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        query: Map<String, String> = emptyMap(),
    ): ApiRequestSpec<T> = ApiRequestSpec(
        method = HttpMethod.GET,
        path = path,
        query = query,
        serializer = serializer,
    )

    private fun <C, T> post(
        path: String,
        cmd: C,
        cmdSerializer: kotlinx.serialization.KSerializer<C>,
        serializer: kotlinx.serialization.KSerializer<T>,
        idempotencyKey: String? = null,
    ): ApiRequestSpec<T> = ApiRequestSpec(
        method = HttpMethod.POST,
        path = path,
        bodyJson = ContractJson.json.encodeToString(cmdSerializer, cmd),
        serializer = serializer,
        idempotencyKey = idempotencyKey,
    )

    private fun <C, T> put(
        path: String,
        cmd: C,
        cmdSerializer: kotlinx.serialization.KSerializer<C>,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): ApiRequestSpec<T> = ApiRequestSpec(
        method = HttpMethod.PUT,
        path = path,
        bodyJson = ContractJson.json.encodeToString(cmdSerializer, cmd),
        serializer = serializer,
    )

    private fun delete(path: String): ApiRequestSpec<Unit> = ApiRequestSpec(
        method = HttpMethod.DELETE,
        path = path,
        bodyless = true,
    )
}
