package czxs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JSON 帮助：统一响应结构 { ok, data } / { ok:false, code, message }。 */
object Json {
    val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    fun ok(data: JsonElement, status: Int = 200) = Resp(status, body = buildJsonObject {
        put("ok", true); put("data", data)
    }.toString())

    fun okRaw(json: String) = Resp(200, body = """{"ok":true,"data":$json}""")

    fun error(code: String, message: String, status: Int = 400) = Resp(status, body = buildJsonObject {
        put("ok", false); put("code", code); put("message", message)
    }.toString())

    fun obj(body: String): JsonObject =
        runCatching { parser.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: throw ApiError("BAD_JSON", "请求体不是合法 JSON")

    fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.contentOrNullSafe()
    fun long(o: JsonObject, key: String): Long? = str(o, key)?.toLongOrNull()
    fun int(o: JsonObject, key: String): Int? = str(o, key)?.toIntOrNull()
    fun arr(o: JsonObject, key: String): JsonArray? = o[key] as? JsonArray

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is JsonNull) null else content

    fun categoryJson(c: CategoryRow) = buildJsonObject {
        put("id", c.id); put("group", c.group); put("name", c.name); put("spec", c.spec)
        put("batch_type", c.batchType); put("status", c.status)
        put("units", buildJsonArray {
            c.units.forEach { u ->
                add(buildJsonObject {
                    put("unit", u.unit); put("conversion", u.conversion.toPlainString()); put("is_settlement", u.isSettlement)
                })
            }
        })
    }

    fun orderJson(o: OrderRow, items: List<OrderItemRow>) = buildJsonObject {
        put("id", o.id); put("order_no", o.orderNo); put("type", o.type)
        put("customer_name", o.customerName); put("customer_phone", o.customerPhone)
        put("account_name", o.accountName); put("status", o.status)
        put("remark", o.remark ?: ""); put("order_total", o.orderTotal)
        put("version", o.version); put("created_at", o.createdAt)
        put("print_count", o.printCount); put("printed_at", o.printedAt ?: "")
        put("items", buildJsonArray {
            items.forEach { i ->
                add(buildJsonObject {
                    put("line_no", i.lineNo); put("category_id", i.categoryId)
                    put("category_name", i.categoryName); put("spec", i.spec)
                    put("batch_tag", i.batchTag ?: ""); put("input_unit", i.inputUnit)
                    put("input_quantity", i.inputQuantity); put("settlement_unit", i.settlementUnit)
                    put("settlement_quantity", i.settlementQuantity); put("unit_price", i.unitPrice)
                    put("line_total", i.lineTotal)
                })
            }
        })
    }

    fun noteJson(n: NoteRow) = buildJsonObject {
        put("id", n.id); put("note_no", n.noteNo); put("ref_order_no", n.refOrderNo)
        put("customer_name", n.customerName); put("status", n.status); put("created_at", n.createdAt)
        put("items", buildJsonArray {
            n.items.forEach { i ->
                add(buildJsonObject {
                    put("category_id", i.categoryId); put("category_name", i.categoryName)
                    put("batch_tag", i.batchTag ?: ""); put("diff_type", i.diffType)
                    put("diff_quantity", i.diffQuantity); put("remark", i.remark ?: "")
                })
            }
        })
    }
}
