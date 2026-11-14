package com.jiancai.android.app.czxs

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** 客户端只填 NAS 域名，/app/czxs 前缀在这里拼一次（规格 46/54）。 */
const val GATEWAY_PREFIX = "/app/czxs"

class ApiException(val code: String, override val message: String) : Exception(message)

/**
 * czxs 客户端 API（TCP 端口，JSON）。
 *
 * token 存本地；所有请求自动带 Authorization；错误统一抛 [ApiException]（code/message 来自服务端）。
 */
class CzxsApi(private val prefs: SharedPreferences) {

    var baseUrl: String
        get() = prefs.getString(KEY_BASE, "http://127.0.0.1:9527") ?: "http://127.0.0.1:9527"
        private set(value) = prefs.edit().putString(KEY_BASE, value).apply()

    val token: String? get() = prefs.getString(KEY_TOKEN, null)

    val accountName: String get() = prefs.getString(KEY_NAME, "") ?: ""

    fun saveServer(input: String) {
        val trimmed = input.trim().trimEnd('/')
        val normalized = if (trimmed.startsWith("http")) trimmed else "http://$trimmed"
        URL(normalized) // 校验
        baseUrl = normalized
    }

    fun login(username: String, password: String) {
        val data = request("POST", "/api/auth/login", JSONObject().put("username", username).put("password", password)).getJSONObject("data")
        val tokenValue = data.getString("token")
        val account = data.getJSONObject("account")
        prefs.edit()
            .putString(KEY_TOKEN, tokenValue)
            .putString(KEY_NAME, account.optString("name"))
            .putString(KEY_ROLE, account.optString("role", "staff"))
            .apply()
    }

    fun logout() {
        runCatching { request("POST", "/api/auth/logout", JSONObject()) }
        prefs.edit().remove(KEY_TOKEN).remove(KEY_NAME).remove(KEY_ROLE).apply()
    }

    fun categories(): JSONArray = request("GET", "/api/categories").getJSONArray("data")

    fun customer(phone: String): JSONObject =
        request("GET", "/api/customers", query = mapOf("phone" to phone)).getJSONObject("data")

    fun price(phone: String, categoryId: Long, batchTag: String): String? {
        return try {
            val data = request(
                "GET", "/api/prices",
                query = mapOf("phone" to phone, "category_id" to categoryId.toString(), "batch_tag" to batchTag),
            ).getJSONObject("data")
            data.optString("unit_price")
        } catch (e: ApiException) {
            if (e.code == "PRICE_MISSING") null else throw e
        }
    }

    fun returns(phone: String, categoryId: Long): List<Pair<String, String>> {
        val arr = request("GET", "/api/returns/available", query = mapOf("phone" to phone, "category_id" to categoryId.toString()))
            .getJSONArray("data")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("batch_tag") to o.getString("available")
        }
    }

    fun createOrder(
        type: String,
        phone: String,
        name: String,
        address: String,
        remark: String,
        items: List<JSONObject>,
    ): JSONObject {
        val body = JSONObject()
            .put("client_request_id", UUID.randomUUID().toString())
            .put("type", type)
            .put("phone", phone)
            .put("customer_name", name)
            .put("address", address)
            .put("remark", remark)
            .put("items", JSONArray(items))
        return request("POST", "/api/orders", body).getJSONObject("data")
    }

    fun updateOrder(orderId: Long, version: Int, remark: String, items: List<JSONObject>): JSONObject {
        val body = JSONObject().put("version", version).put("remark", remark).put("items", JSONArray(items))
        return request("PUT", "/api/orders/$orderId", body).getJSONObject("data")
    }

    fun today(): JSONArray = request("GET", "/api/orders/today").getJSONArray("data")

    fun recent(phone: String, days: Int = 3): JSONArray =
        request("GET", "/api/orders/recent", query = mapOf("phone" to phone, "days" to days.toString())).getJSONArray("data")

    fun order(id: Long): JSONObject = request("GET", "/api/orders/$id").getJSONObject("data")

    fun history(phone: String): JSONObject =
        request("GET", "/api/query/history", query = mapOf("phone" to phone)).getJSONObject("data")

    fun createNote(refOrderNo: String, items: List<JSONObject>): JSONObject {
        val body = JSONObject()
            .put("client_request_id", UUID.randomUUID().toString())
            .put("ref_order_no", refOrderNo)
            .put("items", JSONArray(items))
        return request("POST", "/api/notes", body).getJSONObject("data")
    }

    fun printStatus(): JSONObject = request("GET", "/api/print/status").getJSONObject("data")

    // ---------- czxs 服务端扩展（3.x） ----------

    val isAdmin: Boolean get() = prefs.getString(KEY_ROLE, "staff") == "admin"

    /** 客户模糊搜索：电话 / 姓名 / 地址关键字。 */
    fun searchCustomers(q: String): JSONArray =
        request("GET", "/api/customers/search", query = mapOf("q" to q)).getJSONArray("data")

    /** 该客户全部可退商品（客户+型号+批次，available>0）。 */
    fun returnableList(phone: String): JSONArray =
        request("GET", "/api/returns/list", query = mapOf("phone" to phone)).getJSONArray("data")

    fun refundable(phone: String): String {
        val d = request("GET", "/api/refundable", query = mapOf("phone" to phone)).getJSONObject("data")
        return d.optString("refundable", "0")
    }

    fun outstanding(phone: String): String {
        val d = request("GET", "/api/outstanding", query = mapOf("phone" to phone)).getJSONObject("data")
        return d.optString("outstanding", "0")
    }

    fun settings(): JSONObject = request("GET", "/api/settings").getJSONObject("data")

    fun saveSetting(key: String, value: String) {
        request("POST", "/api/settings", JSONObject().put(key, value))
    }

    fun presets(key: String, fallback: List<String>): List<String> {
        val raw = runCatching { settings().optString(key, "") }.getOrDefault("")
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { fallback }
    }

    /** 收款 / 退款，支持多付款方式与优惠。 */
    fun createPayment(
        type: String,
        phone: String,
        name: String,
        address: String,
        payments: List<JSONObject>,
        discount: String = "",
        discountType: String = "",
        remark: String = "",
    ): JSONObject {
        val body = JSONObject()
            .put("type", type)
            .put("phone", phone)
            .put("customer_name", name)
            .put("address", address)
            .put("remark", remark)
            .put("discount", discount)
            .put("discount_type", discountType)
            .put("payments", JSONArray(payments))
        return request("POST", "/api/payments", body).getJSONObject("data")
    }

    /** 把备注写入已有单据（不再生成独立备注单）。 */
    fun setRemark(orderId: Long, remark: String): JSONObject =
        request("POST", "/api/orders/$orderId/remark", JSONObject().put("remark", remark)).getJSONObject("data")

    fun ordersAll(): JSONArray = request("GET", "/api/orders/all").getJSONArray("data")

    fun settlement(): JSONArray = request("GET", "/api/settlement").getJSONArray("data")

    fun settlementDetail(customerId: Long): JSONObject =
        request("GET", "/api/settlement/$customerId").getJSONObject("data")

    // ---------- HTTP ----------
    private fun request(method: String, path: String, body: JSONObject? = null, query: Map<String, String> = emptyMap()): JSONObject {
        val qs = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") {
            encode(it.key) + "=" + encode(it.value)
        }
        val url = URL(baseUrl + GATEWAY_PREFIX + path + qs)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (code !in 200..299 || !json.optBoolean("ok", true)) {
                throw ApiException(json.optString("code", "HTTP_$code"), json.optString("message", "请求失败"))
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val KEY_BASE = "nas_base"
        const val KEY_TOKEN = "token"
        const val KEY_NAME = "account_name"
        const val KEY_ROLE = "account_role"
    }
}
