package czxs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 客户端 JSON API：只挂在 TCP 端口上（规格 49）。 */
class Api(private val svc: Services) {

    fun handle(req: Req): Resp {
        val path = req.path
        val method = req.method
        if (!path.startsWith("/api/")) return Json.error("NOT_FOUND", "接口不存在", 404)

        // 登录
        if (path == "/api/auth/login" && method == "POST") {
            val o = Json.obj(req.body)
            val username = Json.str(o, "username") ?: throw ApiError("AUTH_FAILED", "账号或密码错误")
            val password = Json.str(o, "password") ?: throw ApiError("AUTH_FAILED", "账号或密码错误")
            val (token, account) = svc.login(username, password)
            return Json.okRaw("""{"token":"$token","account":{"id":${account.id},"username":"${account.username}","name":"${account.name}","role":"${account.role}"}}""")
        }

        val token = req.headers["authorization"]?.removePrefix("Bearer ")?.trim()
            ?: throw ApiError("UNAUTHORIZED", "未登录")
        val account = svc.dbAccess { c -> svc.accountByToken(c, token) }

        return when {
            path == "/api/auth/me" && method == "GET" -> Json.okRaw("""{"id":${account.id},"username":"${account.username}","name":"${account.name}","role":"${account.role}"}""")
            path == "/api/auth/logout" && method == "POST" -> {
                svc.logout(token); Json.okRaw("{}")
            }

            path == "/api/categories" && method == "GET" ->
                Json.ok(buildJsonArray { svc.categories().forEach { add(Json.categoryJson(it)) } })

            path == "/api/categories" && method == "POST" -> {
                val o = Json.obj(req.body)
                val cat = svc.submitCategory(
                    account,
                    Json.str(o, "group") ?: throw ApiError("GROUP_REQUIRED", "大类必填"),
                    Json.str(o, "name") ?: throw ApiError("NAME_REQUIRED", "型号名称必填"),
                    Json.str(o, "spec") ?: "",
                    Json.str(o, "batch_type") ?: "none",
                )
                Json.okRaw(Json.categoryJson(cat).toString()).let {
                    if (cat.status == "pending") Resp(it.status, it.contentType, it.body, emptyMap()) else it
                }
            }

            path == "/api/prices" && method == "GET" -> {
                val phone = req.query["phone"] ?: throw ApiError("PHONE_REQUIRED", "电话必填")
                val categoryId = req.query["category_id"]?.toLongOrNull() ?: throw ApiError("CATEGORY_REQUIRED", "型号必填")
                val batch = req.query["batch_tag"].orEmpty()
                Json.okRaw("""{"unit_price":"${svc.price(phone, categoryId, batch)}"}""")
            }

            path == "/api/returns/available" && method == "GET" -> {
                val phone = req.query["phone"] ?: throw ApiError("PHONE_REQUIRED", "电话必填")
                val categoryId = req.query["category_id"]?.toLongOrNull() ?: throw ApiError("CATEGORY_REQUIRED", "型号必填")
                Json.ok(buildJsonArray {
                    svc.returnsAvailable(phone, categoryId).forEach { (batch, available) ->
                        add(buildJsonObject { put("batch_tag", batch); put("available", available) })
                    }
                })
            }

            path == "/api/orders" && method == "POST" -> {
                val o = Json.obj(req.body)
                val items = parseItems(o)
                val order = svc.createOrder(
                    account,
                    Json.str(o, "client_request_id") ?: throw ApiError("REQUEST_ID_REQUIRED", "缺少 client_request_id"),
                    Json.str(o, "type") ?: throw ApiError("TYPE_REQUIRED", "缺少单据类型"),
                    Json.str(o, "customer_name") ?: "客户",
                    Json.str(o, "phone") ?: throw ApiError("PHONE_REQUIRED", "电话必填"),
                    Json.str(o, "remark"),
                    items,
                )
                val base = Json.orderJson(order, svc.itemsOf(order.id))
                val withCum = kotlinx.serialization.json.JsonObject(
                    base + ("cumulative_total" to kotlinx.serialization.json.JsonPrimitive(svc.cumulative(order.customerPhone))),
                )
                Json.ok(withCum)
            }

            path == "/api/orders/today" && method == "GET" ->
                Json.ok(buildJsonArray { svc.today().forEach { add(Json.orderJson(it, svc.itemsOf(it.id))) } })

            path == "/api/orders/recent" && method == "GET" -> {
                val phone = req.query["phone"] ?: throw ApiError("PHONE_REQUIRED", "电话必填")
                val days = req.query["days"]?.toIntOrNull() ?: 3
                Json.ok(buildJsonArray { svc.recent(phone, days).forEach { add(Json.orderJson(it, svc.itemsOf(it.id))) } })
            }

            path.startsWith("/api/orders/") && method == "GET" -> {
                val id = path.removePrefix("/api/orders/").toLongOrNull() ?: throw ApiError("ORDER_NOT_FOUND", "单据不存在")
                val order = svc.orderDetail(id)
                Json.okRaw(Json.orderJson(order, svc.itemsOf(order.id)).toString())
            }

            path.startsWith("/api/orders/") && method == "PUT" -> {
                val id = path.removePrefix("/api/orders/").toLongOrNull() ?: throw ApiError("ORDER_NOT_FOUND", "单据不存在")
                val o = Json.obj(req.body)
                val order = svc.orderDetail(id)
                val updated = svc.updateOrder(
                    account, order.orderNo,
                    Json.int(o, "version") ?: throw ApiError("VERSION_REQUIRED", "缺少 version"),
                    Json.str(o, "remark"), parseItems(o),
                )
                Json.okRaw(Json.orderJson(updated, svc.itemsOf(updated.id)).toString())
            }

            path == "/api/notes" && method == "POST" -> {
                val o = Json.obj(req.body)
                val items = (Json.arr(o, "items") ?: throw ApiError("ITEMS_REQUIRED", "缺少差异明细")).map { element ->
                    val item = element as JsonObject
                    NoteItemInput(
                        Json.long(item, "category_id") ?: throw ApiError("CATEGORY_REQUIRED", "型号必填"),
                        Json.str(item, "batch_tag"),
                        Json.str(item, "diff_type") ?: "short",
                        Json.str(item, "diff_quantity") ?: "0",
                        Json.str(item, "remark"),
                    )
                }
                val ref = Json.str(o, "ref_order_no") ?: throw ApiError("REF_ORDER_REQUIRED", "必须关联原单")
                Json.okRaw(Json.noteJson(svc.createNote(account, ref, items)).toString())
            }

            path.startsWith("/api/notes/") && method == "GET" -> {
                val id = path.removePrefix("/api/notes/").toLongOrNull() ?: throw ApiError("NOTE_NOT_FOUND", "备注单不存在")
                Json.okRaw(Json.noteJson(svc.noteDetail(id)).toString())
            }

            path == "/api/query/history" && method == "GET" -> {
                val phone = req.query["phone"] ?: throw ApiError("PHONE_REQUIRED", "电话必填")
                val (customer, total) = svc.history(phone)
                Json.okRaw(
                    """{"customer":{"id":${customer.id},"name":"${customer.name}","phone":"${customer.phone}"},
                        "settlement_total":"$total"}""",
                )
            }

            path == "/api/print/status" && method == "GET" -> {
                val (online, pending, last) = svc.printStatus()
                Json.okRaw("""{"printer_online":$online,"pending":$pending,"last_printed_at":"${last ?: ""}"}""")
            }

            else -> Json.error("NOT_FOUND", "接口不存在", 404)
        }
    }

    private fun parseItems(o: JsonObject): List<ItemInput> =
        (Json.arr(o, "items") ?: throw ApiError("ITEMS_REQUIRED", "缺少明细")).map { element ->
            val item = element as JsonObject
            ItemInput(
                Json.long(item, "category_id") ?: throw ApiError("CATEGORY_REQUIRED", "型号必填"),
                Json.str(item, "batch_tag"),
                Json.str(item, "input_unit") ?: throw ApiError("UNIT_REQUIRED", "单位必填"),
                Json.str(item, "input_quantity") ?: throw ApiError("QUANTITY_REQUIRED", "数量必填"),
                Json.str(item, "unit_price"),
            )
        }
}
