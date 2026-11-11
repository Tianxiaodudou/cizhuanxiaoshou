/*
 * @file fpk-server/order/src/main/kotlin/com/jiancai/order/OrderPort.kt
 * @role 订单 Port：S08 唯一对外入口（建单、改单、当日单、历史查询、应收读取、备注单号代取）。
 * @exports OrderPort
 * @depends db-core, shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayDeps.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt, fpk-server/note/src/main/kotlin/com/jiancai/note/NoteService.kt
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.order

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.CreateOrderCmd
import com.jiancai.shared.dto.Money
import com.jiancai.shared.dto.OrderDto
import com.jiancai.shared.dto.OrderQuery
import com.jiancai.shared.dto.PageDto
import com.jiancai.shared.dto.UpdateOrderCmd

/**
 * 订单 Port：S08 唯一对外入口（建单、改单、当日单、历史查询、应收读取、备注单号代取）。
 *
 * contractVersion 1.1.0 起：`OrderDto.sourceOrderNo` / `CreateOrderCmd.sourceOrderNo` 支持退货单
 * 关联原单；`OrderQuery.orderNo` 支持按单号精确查询（S09 / S10 / S11 不再翻页探测存在性）。
 *
 * 契约口径：
 * - 出参一律 `shared` 的 [OrderDto] / [PageDto] / [Money]，不暴露 `order_header` / `order_line`
 *   的持久化结构（铁律 2）；
 * - 每个方法都有带 [TxContext] 的重载：调用方把「订单 + 结算 + 备注」放进同一个 db-core 事务
 *   （铁律 4）；便捷重载内部走 `TxRunner.run {}`，不存在非事务写入；
 * - **单号只来自 S07**：本模块只调 `SerialPort.next(tx, SerialType.of(...))`，禁止自行拼前缀 / 序号
 *   （铁律 8）；**单价只来自 S06**：本模块只调 `PricePort.resolve`，金额由服务端按数量派生（铁律 5）；
 * - **不跨模块读写表**（铁律 3 / 10）：客户 / 型号 / 价格只经 S04 / S05 / S06 的 Port；
 * - **不判权限**（铁律 7）：参数里没有 token / 角色；`createdBy` / 订单号都是业务数据，
 *   登录与权限由 S13 api-gateway 在转发前拦截。
 *
 * 幂等与乐观锁：
 * - [create] 以 `CreateOrderCmd.clientRequestId` 去重：同一请求 id 重复提交返回**同一张订单**，
 *   不重复取号、不重复写明细；
 * - [update] 必须回传 `OrderDto.version`：版本不一致抛 `E2002`，不覆盖别人的修改；
 *   仅允许修改**服务端当天**的订单，其它日期抛 `E2003`。
 *
 * 应收派生：
 * - [OrderDto.receivableAmount] 由服务端按「总额 + 单据方向」派生：采购 / 直送为正，退货为负；
 * - [getReceivable] 返回某张订单当前的应收（退货为负、作废为 `0.00`），供 S11 结算读取；
 *   已收金额与未收读模型归 S11，本模块不读取结算表。
 */
interface OrderPort {

    /**
     * 建单（幂等）。
     *
     * 服务端负责：校验客户 / 型号 / 批号、经 S07 取号、经 S06 解析单价、按数量派生明细金额与总额、
     * 复制客户与型号快照、生成时间戳与版本号。客户端不得上传单价 / 金额 / 单号 / 日期。
     *
     * 退货单可带 [CreateOrderCmd.sourceOrderNo]：必须是同客户、对应正向类型（采购退货 → 采购、
     * 直送退货 → 直送）且未作废的订单，否则 `E3005` / `E3004`；非退货单携带则 `E422`。
     * 为兼容 1.0.0 历史退货单，`sourceOrderNo = null` 仍允许创建（S09 兼容归集模式）。
     *
     * @param createdBy 已由 S13 认证的操作者 id，仅用于 `created_by` 留痕；本模块不校验角色
     * @return 落库后的完整订单（含明细、金额、版本号、原单号）
     * @throws OrderException `E2005` 明细为空；`E2006` 数量 <= 0；`E2007` 批号与型号不匹配；
     *   `E422` 入参格式非法；`E404` 客户 / 型号不存在；`E1002` 未配置价格；`E9001` 取号冲突（可重试）；
     *   退货单原单非法时 `E3004` / `E3005`
     */
    fun create(tx: TxContext, cmd: CreateOrderCmd, createdBy: Long): OrderDto

    /** [create] 的便捷重载：内部开一个写事务。 */
    fun create(cmd: CreateOrderCmd, createdBy: Long): OrderDto

    /**
     * 改单（仅服务端当天、未结清 / 未作废的订单；乐观锁）。
     *
     * 全量覆盖明细与备注；重新经 S06 解析单价并重算金额、总额、应收与状态。
     * 客户与订单号不变，`received_amount` 保持不变（已收归 S11）。
     *
     * @throws OrderException `E2001` 订单不存在；`E2002` 版本冲突；`E2003` 非当日 / 已作废；
     *   `E2004` 已结清；其余同 [create]
     */
    fun update(tx: TxContext, cmd: UpdateOrderCmd): OrderDto

    /** [update] 的便捷重载：内部开一个写事务。 */
    fun update(cmd: UpdateOrderCmd): OrderDto

    /**
     * 服务端「今天」的全部订单（C08 改当日单列表），按创建时间倒序。
     *
     * 业务日期取服务端时钟，客户端本地日期不参与。
     */
    fun today(tx: TxContext): List<OrderDto>

    /** [today] 的便捷重载：内部开一个只读事务。 */
    fun today(): List<OrderDto>

    /**
     * 历史订单分页查询（C07 按电话查历史，其它列表页复用）。
     *
     * [OrderQuery.phone] 非空时经 S04 `CustomerPort.findByPhone` 解析客户；查不到直接返回空页，
     * 不扫描客户表（铁律 3）。[OrderQuery.orderNo] 非空时按单号精确过滤，可与其它条件组合。
     * 结果按创建时间倒序；日期过滤按订单业务日期（`yyyy-MM-dd`）。
     *
     * @throws OrderException `E422` 日期 / 页码 / 每页条数非法
     */
    fun getRecent(tx: TxContext, query: OrderQuery): PageDto<OrderDto>

    /** [getRecent] 的便捷重载：内部开一个只读事务。 */
    fun getRecent(query: OrderQuery): PageDto<OrderDto>

    /**
     * 按单号精确读取一张订单（只读；S09 / S10 / S11 / S13 关联原单用）。
     *
     * 契约 1.2.0 追加（接口只增不改）：与 `getRecent(OrderQuery(orderNo = ...))` 结果一致，
     * 但不需要调用方构造分页查询，也不受每页条数限制。
     *
     * @return 订单 DTO；不存在返回 null（是否算错误由调用方决定）
     */
    fun get(tx: TxContext, orderNo: String): OrderDto?

    /** [get] 的便捷重载：内部开一个只读事务。 */
    fun get(orderNo: String): OrderDto?

    /**
     * 读取某张订单当前的应收金额（S11 结算用）。
     *
     * 采购 / 直送为正，退货为负；已作废订单返回 `0.00`。已收 / 未收由 S11 结合自己的收款记录派生，
     * 本模块不返回已收，避免跨模块读结算表（铁律 3）。
     *
     * @throws OrderException `E2001` 订单不存在
     */
    fun getReceivable(tx: TxContext, orderNo: String): Money

    /** [getReceivable] 的便捷重载：内部开一个只读事务。 */
    fun getReceivable(orderNo: String): Money

    /**
     * 取一个备注单号（S10 note 建单用；前缀 `BZ` 由 S07 的 `SerialType.NOTE` 决定）。
     *
     * 这是把 BZ 取号也收口到 S08 的可选入口；S10 也可以直接调用 S07 的 `SerialPort.nextNote`，
     * 两条路径共用同一条 BZ 序列。无论走哪条，都禁止自行拼 `BZ` 前缀或序号。
     * S10 应把本方法与备注单写入放进同一个事务：备注单失败回滚时，号段与账本一并回滚，
     * 不会留下「号已用、单未建」。
     *
     * @return 服务端产出的备注单号，形如 `BZ202611110001`（纯值，不返回 Entity）
     * @throws OrderException `E9001` 序号用尽 / 取号冲突（可重试），错误码来自 S07
     */
    fun nextNoteNo(tx: TxContext): String

    /** [nextNoteNo] 的便捷重载：内部开一个写事务；需要与备注单同事务时请用带 tx 的重载。 */
    fun nextNoteNo(): String
}
