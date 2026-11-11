package czxs

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 管理端：HTML 页面，只挂在飞牛统一网关的 Unix Socket 上（规格 48/49）。 */
class Admin(private val svc: Services, private val cfg: Config) {

    private val redirect = { to: String -> Resp(303, "text/html; charset=utf-8", "", mapOf("Location" to to)) }

    fun handle(req: Req): Resp {
        val p = req.path
        val initialized = svc.dbAccess { c -> svc.isInitialized(c) }

        if (!initialized) {
            return when {
                p == "/admin/setup" && req.method == "POST" -> doSetup(req)
                p == "/admin/setup" -> page("初始化", setupForm())
                p == "/admin/login" -> page("登录", loginForm())
                else -> redirect(cfg.gatewayPrefix + "/admin/setup")
            }
        }
        if (p == "/admin/setup") return redirect(cfg.gatewayPrefix + "/admin")

        val account = currentAccount(req)
        if (p == "/admin/login" && req.method == "POST") return doLogin(req)
        if (p == "/admin/login") return page("登录", loginForm())
        if (p == "/admin/logout") {
            req.cookies["czxs_admin"]?.let { svc.adminLogout(it) }
            return Resp(303, "text/html; charset=utf-8", "", mapOf("Location" to cfg.gatewayPrefix + "/admin/login", "Set-Cookie" to "czxs_admin=; Path=/; Max-Age=0"))
        }
        if (account == null) return redirect(cfg.gatewayPrefix + "/admin/login")

        return when {
            p == "/admin" || p == "/admin/" -> dashboard(account)
            p == "/admin/orders" -> orders(req)
            p.startsWith("/admin/orders/") -> orderDetail(req, account)
            p == "/admin/print" -> printPage(account)
            p == "/admin/categories" -> categories(req, account)
            p == "/admin/prices" -> prices(req, account)
            p == "/admin/accounts" -> accountsPage(req, account)
            p == "/admin/settlement" -> settlement()
            p == "/admin/settings" -> settings(req, account)
            else -> page("404", "<p>页面不存在</p>")
        }
    }

    // ---------- 认证 ----------
    private fun currentAccount(req: Req): AccountRow? {
        val token = req.cookies["czxs_admin"] ?: return null
        return svc.dbAccess { c -> svc.parseAdminToken(c, token) }
    }

    private fun doSetup(req: Req): Resp {
        val f = form(req.body)
        val account = svc.setup(
            f["username"] ?: "admin",
            f["name"] ?: "管理员",
            f["password"] ?: "",
            f["merchant"] ?: "",
            f["api_port"]?.toIntOrNull() ?: 8080,
        )
        val token = svc.adminLogin(account.username, f["password"] ?: "")
        return Resp(303, "text/html; charset=utf-8", "", mapOf(
            "Location" to cfg.gatewayPrefix + "/admin",
            "Set-Cookie" to "czxs_admin=${token.first}; Path=/; HttpOnly; SameSite=Lax",
        ))
    }

    private fun doLogin(req: Req): Resp {
        val f = form(req.body)
        return try {
            val (token, _) = svc.adminLogin(f["username"] ?: "", f["password"] ?: "")
            Resp(303, "text/html; charset=utf-8", "", mapOf(
                "Location" to cfg.gatewayPrefix + "/admin",
                "Set-Cookie" to "czxs_admin=$token; Path=/; HttpOnly; SameSite=Lax",
            ))
        } catch (e: ApiError) {
            page("登录", loginForm("账号或密码错误"))
        }
    }

    // ---------- 页面 ----------
    private fun dashboard(account: AccountRow): Resp {
        val today = svc.today()
        val status = svc.printStatus()
        val missing = 0
        val rows = today.take(8).joinToString("") { o ->
            "<tr><td>${esc(o.orderNo)}</td><td>${typeLabel(o.type)}</td><td>${esc(o.customerName)}</td>" +
                "<td>${esc(o.orderTotal)}</td><td>${statusLabel(o.status)}</td>" +
                "<td><a href=\"${cfg.gatewayPrefix}/admin/orders/${o.id}\">详情</a></td></tr>"
        }
        val body = """
            <h2>仪表盘</h2>
            <div class="cards">
              <div class="card"><b>${today.size}</b><span>今日单据</span></div>
              <div class="card"><b>${status.second}</b><span>待打印</span></div>
              <div class="card"><b>${if (status.first) "在线" else "离线"}</b><span>打印机</span></div>
              <div class="card"><b>$missing</b><span>价格缺失</span></div>
            </div>
            <h3>最近单据</h3>
            <table><tr><th>单号</th><th>类型</th><th>客户</th><th>金额</th><th>状态</th><th>操作</th></tr>$rows</table>
            <p class="muted">告警：（无）</p>
        """.trimIndent()
        return page("仪表盘", body, account)
    }

    private fun orders(req: Req): Resp {
        val day = req.query["day"]
        val type = req.query["type"]
        val phone = req.query["phone"]
        val list = svc.orders(day, type?.takeIf { it.isNotBlank() }, phone?.takeIf { it.isNotBlank() }, null)
        val rows = list.joinToString("") { o ->
            "<tr><td>${esc(o.orderNo)}</td><td>${typeLabel(o.type)}</td><td>${esc(o.customerName)}</td>" +
                "<td>${esc(o.orderTotal)}</td><td>${statusLabel(o.status)}</td>" +
                "<td><a href=\"${cfg.gatewayPrefix}/admin/orders/${o.id}\">详情</a></td></tr>"
        }
        val body = """
            <h2>单据管理</h2>
            <form method="get" action="${cfg.gatewayPrefix}/admin/orders" class="filters">
              <input name="day" placeholder="日期 2026-11-11" value="${esc(day ?: "")}">
              <select name="type"><option value="">全部类型</option>${typeOptions(type)}</select>
              <input name="phone" placeholder="客户电话" value="${esc(phone ?: "")}">
              <button>查询</button>
            </form>
            <table><tr><th>单号</th><th>类型</th><th>客户</th><th>金额</th><th>状态</th><th>操作</th></tr>$rows</table>
        """.trimIndent()
        return page("单据", body)
    }

    private fun orderDetail(req: Req, account: AccountRow): Resp {
        if (req.path.endsWith("/reprint") && req.method == "POST") {
            val id = req.path.removePrefix("/admin/orders/").removeSuffix("/reprint").toLongOrNull() ?: 0
            val order = svc.orderDetail(id)
            svc.reprint(account, order.orderNo)
            return redirect("${cfg.gatewayPrefix}/admin/orders/$id")
        }
        val id = req.path.removePrefix("/admin/orders/").toLongOrNull()
            ?: return page("单据", "<p>单号非法</p>")
        val order = runCatching { svc.orderDetail(id) }.getOrNull() ?: return page("单据", "<p>单据不存在</p>")
        val items = svc.itemsOf(id)
        val rows = items.joinToString("") { i ->
            "<tr><td>${esc(i.categoryName)} ${esc(i.spec)}</td><td>${esc(i.batchTag ?: "")}</td>" +
                "<td>${esc(i.inputQuantity)}${esc(i.inputUnit)}</td><td>${esc(i.settlementQuantity)}${esc(i.settlementUnit)}</td>" +
                "<td>${esc(i.unitPrice)}</td><td>${esc(i.lineTotal)}</td></tr>"
        }
        val revisions = svc.revisions(id).joinToString("") { (v, _) -> "<li>v$v 修改快照</li>" }
        val body = """
            <p><a href="${cfg.gatewayPrefix}/admin/orders">← 返回</a></p>
            <h2>${esc(order.orderNo)} ${typeLabel(order.type)}</h2>
            <p>客户：${esc(order.customerName)} ${esc(order.customerPhone)}　下单员：${esc(order.accountName)}　${esc(order.createdAt.take(16).replace('T', ' '))}</p>
            <p>状态：${statusLabel(order.status)}　打印次数：${order.printCount}　版本：v${order.version}</p>
            <table><tr><th>品类</th><th>色号</th><th>录入</th><th>结算</th><th>单价</th><th>总价</th></tr>$rows</table>
            <p>本单合计：<b>${esc(order.orderTotal)}</b></p>
            <p>修改记录：${if (revisions.isBlank()) "（无）" else "<ul>$revisions</ul>"}</p>
            <form method="post" action="${cfg.gatewayPrefix}/admin/orders/$id/reprint"><button>重打此单</button></form>
        """.trimIndent()
        return page("单据详情", body)
    }

    private fun printPage(account: AccountRow): Resp {
        val jobs = svc.printJobsList().joinToString("") { j ->
            "<tr><td>#${j.id}</td><td>${esc(j.orderNo ?: "")}</td><td>${if (j.orderType == "note") "备注单" else "单据"}</td>" +
                "<td>${j.status}</td><td>${esc(j.createdAt.take(16).replace('T', ' '))}</td></tr>"
        }
        val body = """
            <h2>打印中枢</h2>
            <p>打印机：服务端内置队列　状态：在线</p>
            <table><tr><th>#</th><th>单号</th><th>类型</th><th>状态</th><th>时间</th></tr>$jobs</table>
        """.trimIndent()
        return page("打印", body)
    }

    private fun categories(req: Req, account: AccountRow): Resp {
        // 新增型号：五个自定义字段联合唯一
        if (req.method == "POST" && req.path == "/admin/categories") {
            val f = form(req.body)
            svc.createCategoryByAdmin(
                account,
                f["group"].orEmpty(), f["name"].orEmpty(), f["batch"].orEmpty(),
                f["settlement_unit"].orEmpty(), f["conversion_unit"].orEmpty(), f["conversion_ratio"].orEmpty(),
            )
            return redirect("${cfg.gatewayPrefix}/admin/categories")
        }
        if (req.method == "POST") {
            val parts = req.path.removePrefix("/admin/categories/").split('/')
            val id = (parts.getOrNull(0)?.toLongOrNull() ?: 0).takeIf { it > 0 } ?: (form(req.body)["category_id"]?.toLongOrNull() ?: 0)
            val action = parts.getOrNull(1)
            val f = form(req.body)
            when (action) {
                "audit" -> svc.approveCategory(account, id, f["approve"] == "1", f["reason"])
                "status" -> svc.setCategoryStatus(account, id, f["status"] ?: "active")
                "unit" -> svc.setUnit(account, id, f["unit"] ?: "", f["conversion"] ?: "1", f["settlement"] == "1")
            }
            return redirect("${cfg.gatewayPrefix}/admin/categories")
        }
        val list = svc.categoriesAll()
        val rows = list.joinToString("") { c ->
            val settlementUnit = c.units.firstOrNull { it.isSettlement }
            val units = c.units.joinToString("，") { u ->
                if (u.isSettlement) "${u.unit}(计价)"
                else "${u.unit}(1${u.unit}=${u.conversion.toPlainString()}${settlementUnit?.unit ?: ""})"
            }
            val actions = buildString {
                if (c.status == "pending") {
                    append("<form method=post action=\"${cfg.gatewayPrefix}/admin/categories/${c.id}/audit\" style=display:inline><input type=hidden name=approve value=1><button>通过</button></form>")
                    append("<form method=post action=\"${cfg.gatewayPrefix}/admin/categories/${c.id}/audit\" style=display:inline><input type=hidden name=approve value=0><input name=reason placeholder=拒绝原因><button>拒绝</button></form>")
                } else {
                    val target = if (c.status == "active") "disabled" else "active"
                    append("<form method=post action=\"${cfg.gatewayPrefix}/admin/categories/${c.id}/status\" style=display:inline><input type=hidden name=status value=$target><button>${if (target == "active") "启用" else "禁用"}</button></form>")
                }
            }
            "<tr><td>${esc(c.group)}</td><td>${esc(c.name)}</td><td>${esc(c.spec)}</td><td>$units</td><td>${c.status}</td><td>$actions</td></tr>"
        }
        val body = """
            <h2>型号库</h2>
            <p><button type="button" onclick="document.getElementById('addCat').showModal()">+ 新增型号</button></p>
            <dialog id="addCat">
              <form method="post" action="${cfg.gatewayPrefix}/admin/categories">
                <h3>新增型号</h3>
                <p>一级 品类：<input name="group" required placeholder="墙砖 / 地砖"></p>
                <p>二级 型号：<input name="name" required placeholder="T6 / p3 / c200"></p>
                <p>三级 批号：<input name="batch" required placeholder="789 / ii8"></p>
                <p>四级 计价单位：<input name="settlement_unit" required placeholder="片 / 箱"></p>
                <p>五级 换算单位：<input name="conversion_unit" required placeholder="箱 / 件"></p>
                <p>换算系数：1 <input name="conversion_ratio" value="1" size="6"> 换算单位 = ? 计价单位（例如 1 箱 = 10 片 就填 10）</p>
                <p><button>保存</button> <button type="button" onclick="document.getElementById('addCat').close()">取消</button></p>
              </form>
            </dialog>
            <table><tr><th>品类</th><th>型号</th><th>批号</th><th>单位（计价/换算）</th><th>状态</th><th>操作</th></tr>$rows</table>
            <h3>单位配置</h3>
            <form method="post" action="${cfg.gatewayPrefix}/admin/categories/0/unit">
              型号 ID <input name="category_id" size="4"> 单位 <input name="unit" size="6"> 换算 <input name="conversion" size="6" value="1">
              <label><input type="checkbox" name="settlement" value="1">计价单位</label><button>保存</button>
            </form>
        """.trimIndent()
        return page("型号库", body)
    }

    private fun prices(req: Req, account: AccountRow): Resp {
        if (req.method == "POST") {
            val f = form(req.body)
            svc.setPrice(account, f["customer_id"]?.toLongOrNull() ?: 0, f["category_id"]?.toLongOrNull() ?: 0, f["price"] ?: "0")
            return redirect("${cfg.gatewayPrefix}/admin/prices?customer_id=${f["customer_id"]}")
        }
        val customers = svc.customersList()
        val selected = req.query["customer_id"]?.toLongOrNull() ?: customers.firstOrNull()?.id
        val rows = selected?.let { cid ->
            svc.customerPrices(cid).joinToString("") { (catId, name, price) ->
                "<tr><td>${esc(name)}</td><td>${esc(price)}</td><td>" +
                    "<form method=post action=\"${cfg.gatewayPrefix}/admin/prices\" style=display:inline>" +
                    "<input type=hidden name=customer_id value=$cid><input type=hidden name=category_id value=$catId>" +
                    "<input name=price size=6 value=\"${esc(price)}\"><button>修改</button></form></td></tr>"
            }
        } ?: ""
        val options = customers.joinToString("") { c ->
            "<option value=\"${c.id}\" ${if (c.id == selected) "selected" else ""}>${esc(c.name)} / ${esc(c.phone)}</option>"
        }
        val body = """
            <h2>客户价格</h2>
            <form method="get" action="${cfg.gatewayPrefix}/admin/prices"><select name="customer_id">$options</select><button>切换</button></form>
            <table><tr><th>型号</th><th>单价</th><th>操作</th></tr>$rows</table>
        """.trimIndent()
        return page("客户价格", body)
    }

    private fun accountsPage(req: Req, account: AccountRow): Resp {
        if (req.method == "POST") {
            val parts = req.path.removePrefix("/admin/accounts/").split('/')
            val id = (parts.getOrNull(0)?.toLongOrNull() ?: 0).takeIf { it > 0 } ?: (form(req.body)["category_id"]?.toLongOrNull() ?: 0)
            val f = form(req.body)
            when (parts.getOrNull(1)) {
                "status" -> svc.setAccountEnabled(account, id, f["enabled"] == "1")
                "password" -> svc.resetPassword(account, id, f["password"] ?: "")
            }
            return redirect("${cfg.gatewayPrefix}/admin/accounts")
        }
        val rows = svc.accountsList().joinToString("") { a ->
            val toggle = if (a.status == "active") 0 else 1
            "<tr><td>${esc(a.username)}</td><td>${esc(a.name)}</td><td>${a.role}</td><td>${a.status}</td>" +
                "<td><form method=post action=\"${cfg.gatewayPrefix}/admin/accounts/${a.id}/status\" style=display:inline>" +
                "<input type=hidden name=enabled value=$toggle><button>${if (a.status == "active") "禁用" else "启用"}</button></form>" +
                "<form method=post action=\"${cfg.gatewayPrefix}/admin/accounts/${a.id}/password\" style=display:inline>" +
                "<input name=password placeholder=新密码 size=8><button>重置密码</button></form></td></tr>"
        }
        val logs = svc.logs(20).joinToString("") { "<li>${esc(it)}</li>" }
        val body = """
            <h2>账号管理</h2>
            <table><tr><th>账号</th><th>姓名</th><th>角色</th><th>状态</th><th>操作</th></tr>$rows</table>
            <h3>操作日志</h3><ul>$logs</ul>
        """.trimIndent()
        return page("账号", body)
    }

    private fun settlement(): Resp {
        val rows = svc.settlementRows().joinToString("") { (c, total, last) ->
            "<tr><td>${esc(c.name)}</td><td>${esc(c.phone)}</td><td>${Num.money(total)}</td><td>${esc(last.take(10))}</td></tr>"
        }
        val body = "<h2>结算汇总（按客户聚合）</h2><table><tr><th>客户</th><th>电话</th><th>累计结算</th><th>最近交易</th></tr>$rows</table>"
        return page("结算", body)
    }

    private fun settings(req: Req, account: AccountRow): Resp {
        if (req.path == "/admin/settings/restart" && req.method == "POST") {
            svc.restartApiPort(account)
            return page("系统配置", "<p>已记录重启请求：端口将在进程重启后生效（当前进程内未重绑）。</p><p><a href=\"${cfg.gatewayPrefix}/admin/settings\">返回</a></p>")
        }
        if (req.method == "POST") {
            val f = form(req.body)
            f["merchant_name"]?.let { svc.saveSetting(account, "merchant_name", it) }
            f["api_port"]?.let {
                val port = it.toIntOrNull() ?: 0
                if (port in 1024..65535) svc.saveSetting(account, "api_port", port.toString())
            }
            f["print_width"]?.let { svc.saveSetting(account, "print_width", it) }
            svc.saveSetting(account, "print_remark", if (f["print_remark"] == "1") "true" else "false")
            svc.saveSetting(account, "print_signature", if (f["print_signature"] == "1") "true" else "false")
            return redirect("${cfg.gatewayPrefix}/admin/settings")
        }
        val s = svc.settingsMap()
        val body = """
            <h2>系统配置</h2>
            <form method="post" action="${cfg.gatewayPrefix}/admin/settings">
              <p>商家名称：<input name="merchant_name" value="${esc(s["merchant_name"] ?: "")}"> <button>保存</button></p>
              <p>API 端口：<input name="api_port" value="${esc(s["api_port"] ?: "8080")}" size="6"> <button>保存</button></p>
              <p>小票宽度：<select name="print_width"><option>80mm</option><option>58mm</option></select></p>
              <p><label><input type="checkbox" name="print_remark" value="1" ${if (s["print_remark"] != "false") "checked" else ""}>打印备注</label>
                 <label><input type="checkbox" name="print_signature" value="1" ${if (s["print_signature"] != "false") "checked" else ""}>签字栏</label></p>
              <button>保存全部</button>
            </form>
            <form method="post" action="${cfg.gatewayPrefix}/admin/settings/restart" onsubmit="return confirm('确认重启服务以重绑端口？')"><button>重启服务生效</button></form>
            <p class="muted">保存只写配置；重启才重绑端口，客户端需更新地址。</p>
            <p>网关状态：已接入　数据库：${esc(cfg.dbPath)}（只读展示）</p>
        """.trimIndent()
        return page("系统配置", body)
    }

    // ---------- HTML ----------
    private fun page(title: String, body: String, account: AccountRow? = null): Resp =
        Resp(200, "text/html; charset=utf-8", html(title, body, account))

    private fun html(title: String, body: String, account: AccountRow? = null): String {
        val nav = if (account == null) "" else """
            <nav>
              <a href="${cfg.gatewayPrefix}/admin">仪表盘</a>
              <a href="${cfg.gatewayPrefix}/admin/orders">单据</a>
              <a href="${cfg.gatewayPrefix}/admin/print">打印</a>
              <a href="${cfg.gatewayPrefix}/admin/categories">型号</a>
              <a href="${cfg.gatewayPrefix}/admin/prices">价格</a>
              <a href="${cfg.gatewayPrefix}/admin/accounts">账号</a>
              <a href="${cfg.gatewayPrefix}/admin/settlement">结算</a>
              <a href="${cfg.gatewayPrefix}/admin/settings">配置</a>
              <span class="who">管理员：${esc(account.name)} <a href="${cfg.gatewayPrefix}/admin/logout">退出</a></span>
            </nav>
        """.trimIndent()
        return """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<title>$title · 瓷砖店销售系统</title>
<style>
body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;margin:0;background:#f6f7f9;color:#1c2430}
header{background:#1565c0;color:#fff;padding:12px 20px;font-weight:600}
nav{background:#fff;border-bottom:1px solid #e3e6ea;padding:10px 20px;display:flex;gap:16px;flex-wrap:wrap;align-items:center}
nav a{color:#1565c0;text-decoration:none}
nav .who{margin-left:auto;color:#555;font-size:13px}
main{padding:20px;max-width:1100px}
table{border-collapse:collapse;background:#fff;width:100%;margin:10px 0}
th,td{border:1px solid #e3e6ea;padding:8px;text-align:left;font-size:14px}
th{background:#f0f3f7}
.cards{display:flex;gap:12px;flex-wrap:wrap}
.card{background:#fff;border:1px solid #e3e6ea;border-radius:8px;padding:14px 18px;min-width:130px;display:flex;flex-direction:column}
.card b{font-size:22px;color:#1565c0}
.card span{color:#666;font-size:13px}
form{margin:8px 0}
input,select,button{font-size:14px;padding:5px 8px;border:1px solid #cfd6df;border-radius:5px;background:#fff}
button{background:#1565c0;color:#fff;border:0;cursor:pointer}
.filters{display:flex;gap:8px;flex-wrap:wrap}
.muted{color:#777;font-size:13px}
label{margin-right:12px}
</style></head><body>
<header>瓷砖店销售系统</header>$nav<main>$body</main></body></html>"""
    }

    private fun setupForm(error: String? = null) = """
        <h2>首次初始化</h2>
        <p>首次部署，请创建管理员账号（${error ?: "商家名称必填"}）</p>
        <form method="post" action="${cfg.gatewayPrefix}/admin/setup">
          <p>管理员账号 <input name="username" value="admin"></p>
          <p>管理员姓名 <input name="name" value="管理员"></p>
          <p>密码 <input type="password" name="password"></p>
          <p>商家名称 <input name="merchant" value=""></p>
          <p>API 端口 <input name="api_port" value="8080" size="6"></p>
          <button>完成初始化</button>
        </form>
    """.trimIndent()

    private fun loginForm(error: String? = null) = """
        <h2>管理员登录</h2>
        <form method="post" action="${cfg.gatewayPrefix}/admin/login">
          <p>管理员账号 <input name="username"></p>
          <p>密　　　码 <input type="password" name="password"></p>
          <button>登 录</button>
        </form>
        ${if (error != null) "<p style=color:#c62828>$error</p>" else ""}
    """.trimIndent()

    private fun typeOptions(selected: String?) = listOf(
        "delivery" to "配送送货", "delivery_return" to "配送退货",
        "pickup" to "自提送货", "pickup_return" to "自提退货",
    ).joinToString("") { (v, l) ->
        "<option value=\"$v\" ${if (v == selected) "selected" else ""}>$l</option>"
    }

    private fun typeLabel(type: String) = when (type) {
        "delivery" -> "配送送货"; "delivery_return" -> "配送退货"
        "pickup" -> "自提送货"; "pickup_return" -> "自提退货"; else -> type
    }

    private fun statusLabel(status: String) = when (status) {
        "submitted" -> "待打印"; "printed" -> "已打印"; "draft" -> "草稿"; else -> status
    }

    private fun form(body: String): Map<String, String> =
        HttpServer.parseQuery(body)

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
