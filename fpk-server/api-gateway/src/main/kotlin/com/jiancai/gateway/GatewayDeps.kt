/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   52 行  类 GatewayDeps
 * 第   70 行  对象 ?
 */

package com.jiancai.gateway

import com.jiancai.account.AccountAdminPort
import com.jiancai.auth.AuthPort
import com.jiancai.category.CategoryPort
import com.jiancai.config.ConfigPort
import com.jiancai.customer.CustomerPort
import com.jiancai.db.TxRunner
import com.jiancai.note.NotePort
import com.jiancai.observability.AuditPort
import com.jiancai.order.OrderPort
import com.jiancai.pricing.PricePort
import com.jiancai.print.PrintPort
import com.jiancai.returncheck.ReturnCheckPort
import com.jiancai.settlement.SettlePort
import java.time.Clock

/**
 * 网关的全部依赖（组合根装配时一次性注入）。
 *
 * 硬边界：
 * - 只持有**别的模块的 Port 接口**，不持有任何 repository / 表 / SQL（铁律 3 / 10）；
 * - 不持有 `SerialPort`：单号只能由业务模块内部经 S07 产出，网关不暴露取号接口（铁律 8），
 *   否则客户端可以凭空消耗单号；
 * - [audit] 可为 null（未接 S14 时不要留痕），接入后改价 / 审核在**同一事务**里留痕；
 * - [clock] 只用于契约握手的 `serverTime`，不参与业务日期判定（业务日期在服务端各模块）。
 *
 * @property txRunner db-core 事务 Port（跨 Port 写操作包裹，铁律 4）
 * @property auth S02 登录 / token 校验
 * @property accounts S03 账号管理
 * @property customers S04 客户
 * @property categories S05 型号库
 * @property prices S06 定价
 * @property orders S08 订单
 * @property returns S09 退单校验
 * @property notes S10 差异备注单
 * @property settlements S11 结算
 * @property printing S12 打印（服务端渲染）
 * @property audit S14 审计留痕（可选）
 * @property config S01 部署配置（可选，只用于握手时回显商家名称）
 * @property clock 服务端时钟
 * @property maxBodyBytes 请求体上限（超过 → `E413`，在调用任何 Port 之前失败）
 * @property idempotency 幂等键存储
 */
data class GatewayDeps(
    val txRunner: TxRunner,
    val auth: AuthPort,
    val accounts: AccountAdminPort,
    val customers: CustomerPort,
    val categories: CategoryPort,
    val prices: PricePort,
    val orders: OrderPort,
    val returns: ReturnCheckPort,
    val notes: NotePort,
    val settlements: SettlePort,
    val printing: PrintPort,
    val audit: AuditPort? = null,
    val config: ConfigPort? = null,
    val clock: Clock = Clock.systemDefaultZone(),
    val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
    val idempotency: IdempotencyStore = InMemoryIdempotencyStore(),
) {
    companion object {
        /** 请求体默认上限：256 KiB（发票 / 单据 JSON 远小于此，超过视为异常）。 */
        const val DEFAULT_MAX_BODY_BYTES: Int = 256 * 1024
    }
}
