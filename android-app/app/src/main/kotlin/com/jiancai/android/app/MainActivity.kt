/*
 * @file android-app/app/src/main/kotlin/com/jiancai/android/app/MainActivity.kt
 * @role 真机验证用最小界面：登录（服务端地址可改）/ 首页 5 入口 / 采购下单 / 历史查询 / 设置
 * @exports MainActivity
 * @depends core-network, core-session, core-ui, feature-order-entry, shared
 * @calledBy （系统启动器）
 * @sideEffects 网络请求（HTTP）
 */
package com.jiancai.android.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jiancai.android.network.ApiPaths
import com.jiancai.android.network.ApiResult
import com.jiancai.android.network.HttpApiClient
import com.jiancai.android.network.InMemoryServerAddressStore
import com.jiancai.android.network.TokenProvider
import com.jiancai.android.orderentry.OrderEntryRoute
import com.jiancai.android.orderentry.OrderFormComponent
import com.jiancai.android.orderentry.OrderFormLine
import com.jiancai.android.session.SessionGate
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.enums.OrderType
import com.jiancai.shared.units.PricingUnit

/**
 * 单 Activity 多屏：只为实现「真机连服务端」验证，不追求完整业务 UI。
 *
 * 所有网络调用都在后台线程，结果回主线程渲染；金额 / 单号一律原样展示服务端返回值。
 */
class MainActivity : Activity() {

    private val addresses = InMemoryServerAddressStore()
    private val bridge = Bridge()
    private val client = HttpApiClient(addresses, bridge, AndroidTransport())
    private val session = SessionGate(client)

    private lateinit var root: LinearLayout
    private lateinit var status: TextView

    init {
        bridge.session = session
    }

    /** C00 的 token 提供者：只把请求转给 C01（不在界面里判断登录态）。 */
    private class Bridge : TokenProvider {
        var session: SessionGate? = null
        override fun token(): String? = session?.token()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }
        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 24)
            text = "服务端：${addresses.baseUrl()}"
        }
        root.addView(status)
        scroll.addView(root)
        setContentView(scroll)
        showLogin()
    }

    // ---------- 通用控件 ----------

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 22f
        setPadding(0, 0, 0, 16)
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 12, 0, 4)
    }

    private fun input(hint: String, value: String = "", numeric: Boolean = false): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 16 }
    }

    private fun output(): TextView = TextView(this).apply {
        textSize = 13f
        setPadding(0, 16, 0, 0)
    }

    private fun clean() {
        while (root.childCount > 1) root.removeViewAt(1)
        status.text = "服务端：${addresses.baseUrl()}"
    }

    private fun fail(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun <T> background(block: () -> ApiResult<T>, done: (ApiResult<T>) -> Unit) {
        Thread {
            val result = block()
            runOnUiThread { done(result) }
        }.start()
    }

    private fun <T> backgroundPlain(block: () -> T, done: (T) -> Unit) {
        Thread {
            val value = block()
            runOnUiThread { done(value) }
        }.start()
    }

    // ---------- 登录 ----------

    private fun showLogin() {
        clean()
        root.addView(title("登录"))
        val address = input("服务端地址", addresses.baseUrl())
        val user = input("账号", "admin")
        val password = input("密码")
        password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val out = output()
        root.addView(label("服务端地址（可改）")); root.addView(address)
        root.addView(label("账号")); root.addView(user)
        root.addView(label("密码")); root.addView(password)
        root.addView(button("登录") {
            val saved = runCatching { addresses.update(address.text.toString()) }
            if (saved.isFailure) {
                out.text = "地址非法：${saved.exceptionOrNull()?.message}"
                return@button
            }
            backgroundPlain({ session.login(user.text.toString(), password.text.toString()) }) { outcome ->
                if (outcome.success) showHome() else out.text = outcome.failure?.message ?: "登录失败"
            }
        })
        root.addView(button("测试契约握手 /contract") {
            background({ client.call(ApiPaths.contract()) }) { result ->
                out.text = when (result) {
                    is ApiResult.Success -> "契约版本=${result.value.contractVersion}，商家=${result.value.merchantName}"
                    is ApiResult.Failure -> "失败 ${result.wireCode} ${result.message}"
                }
            }
        })
        root.addView(out)
    }

    // ---------- 首页 ----------

    private fun showHome() {
        clean()
        root.addView(title("首页"))
        listOf("采购下单", "直送下单", "历史查询", "差异备注", "结算上传").forEach { name ->
            root.addView(button(name) {
                when (name) {
                    "采购下单" -> showOrder(OrderType.PURCHASE)
                    "直送下单" -> showOrder(OrderType.DIRECT)
                    "历史查询" -> showQuery()
                    else -> Toast.makeText(this, "本期只验证连接：$name 暂未实现", Toast.LENGTH_SHORT).show()
                }
            })
        }
        root.addView(button("设置 / 服务端") { showSettings() })
    }

    // ---------- 下单 ----------

    private fun showOrder(type: OrderType) {
        clean()
        root.addView(title("${type.label}录入"))
        val phone = input("客户电话", "13800000001", numeric = true)
        val categoryId = input("型号 id", "1", numeric = true)
        val batch = input("批号（可空）")
        val quantity = input("数量", "2")
        val out = output()
        root.addView(label("客户电话")); root.addView(phone)
        root.addView(label("型号 id")); root.addView(categoryId)
        root.addView(label("批号")); root.addView(batch)
        root.addView(label("数量")); root.addView(quantity)

        val route = OrderEntryRoute(client, type)
        var state = OrderFormComponent.empty(type)

        root.addView(button("查客户") {
            background({ route.lookupCustomer(phone.text.toString()) }) { result ->
                when (result) {
                    is ApiResult.Success -> {
                        state = OrderFormComponent.withCustomer(state, result.value)
                        out.text = "客户：${result.value.name}（id=${result.value.id}）"
                    }
                    is ApiResult.Failure -> out.text = "失败 ${result.wireCode}：${result.message}"
                }
            }
        })
        root.addView(button("提交订单") {
            val customerId = state.customerId
            if (customerId == null) {
                out.text = "请先点「查客户」"
                return@button
            }
            val line = OrderFormLine(
                categoryId = categoryId.text.toString().toLongOrNull() ?: 1L,
                batchTag = batch.text.toString(),
                unit = PricingUnit.BAG,
                quantity = quantity.text.toString(),
            )
            state = OrderFormComponent.addLine(OrderFormComponent.withRemark(state, ""), line)
            background({ route.submit(state, "android-${System.currentTimeMillis()}") }) { result ->
                out.text = when (result) {
                    is ApiResult.Success -> {
                        state = OrderFormComponent.prefillFrom(result.value)
                        "下单成功：单号=${result.value.orderNo}\n总额=${result.value.totalAmount.display()} 应收=${result.value.receivableAmount.display()}"
                    }
                    is ApiResult.Failure -> "下单失败 ${result.wireCode}：${result.message}"
                }
            }
        })
        root.addView(button("返回首页") { showHome() })
        root.addView(out)
    }

    // ---------- 历史查询 ----------

    private fun showQuery() {
        clean()
        root.addView(title("历史查询"))
        val phone = input("客户电话", "13800000001", numeric = true)
        val out = output()
        root.addView(label("客户电话")); root.addView(phone)
        root.addView(button("查询") {
            background({ client.call(ApiPaths.orders(OrderQuery(phone = phone.text.toString(), pageSize = 10))) }) { result ->
                out.text = when (result) {
                    is ApiResult.Success -> if (result.value.items.isEmpty()) {
                        "没有记录"
                    } else {
                        result.value.items.joinToString("\n") { order ->
                            "${order.orderNo} ${order.status.name} 总额=${order.totalAmount.display()} 客户=${order.customerName}"
                        }
                    }
                    is ApiResult.Failure -> "失败 ${result.wireCode}：${result.message}"
                }
            }
        })
        root.addView(button("返回首页") { showHome() })
        root.addView(out)
    }

    // ---------- 设置 ----------

    private fun showSettings() {
        clean()
        root.addView(title("设置"))
        val address = input("服务端地址", addresses.baseUrl())
        val out = output()
        root.addView(label("服务端地址")); root.addView(address)
        root.addView(button("保存") {
            val saved = runCatching { addresses.update(address.text.toString()) }
            out.text = if (saved.isSuccess) "已保存：${saved.getOrThrow().value}" else "非法：${saved.exceptionOrNull()?.message}"
            status.text = "服务端：${addresses.baseUrl()}"
        })
        root.addView(button("退出登录") {
            session.logout()
            showLogin()
        })
        root.addView(button("返回首页") { showHome() })
        root.addView(out)
    }
}
