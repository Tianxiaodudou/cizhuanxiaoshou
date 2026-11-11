/*
 * @file fpk-server/pricing/src/main/kotlin/com/jiancai/pricing/PricePort.kt
 * @role 定价 Port：S06 唯一对外入口（解析价格、管理员改价、读取改价留痕）。
 * @exports PricePort
 * @depends db-core, shared
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayDeps.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt, fpk-server/order/src/main/kotlin/com/jiancai/order/OrderService.kt
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.pricing

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.ChangePriceCmd
import com.jiancai.shared.dto.PriceChangeLog
import com.jiancai.shared.dto.PriceDto

/**
 * 定价 Port：S06 唯一对外入口（解析价格、管理员改价、读取改价留痕）。
 *
 * 契约口径：
 * - 出参一律 [PriceDto] / [PriceChangeLog]，不暴露 `price` / `price_change_log` 的持久化结构（铁律 2）；
 * - 每个方法都有带 [TxContext] 的重载：调用方把「定价 + 订单写入」放进同一个 db-core 事务（铁律 4）；
 *   便捷重载内部同样经 `TxRunner.run {}`，不存在非事务写入；
 * - **本模块不判权限**（铁律 7）：参数里没有 token、没有角色；[changePrice] 的 `adminId` 只是
 *   「这次改价记在谁头上」的留痕主体，不是权限判断。S13 api-gateway 必须先 `verify → ADMIN`，
 *   通过后才把已认证的管理员 id 传进来，否则返回 `E403` 且不进入本模块；
 * - **不跨模块读写表**（铁律 3 / 10）：客户与型号只经 S04 `CustomerPort` / S05 `CategoryPort` 获取；
 * - **价格只由本模块解析**：S08 order 必须调用 [resolve] 取单价，禁止在订单模块自行算价（硬边界）。
 *
 * 定价维度与锁：
 * - 维度 = 客户 + 型号 + 批号类型；批号按型号配置规范化（大小写不敏感、保留型号库中的写法）；
 * - 首次 [changePrice] 创建价格，`effective_date` = 定价当天；
 * - 同一自然日内可继续 [changePrice]，`effective_date` 保持不变；
 * - 跨日后该行锁定（[PriceDto.locked] = true），再改抛 `E1001`；锁由服务端时钟判定，客户端不参与。
 */
interface PricePort {

    /**
     * 解析某客户 + 型号 + 批号的恒定单价。
     *
     * @param batchTag 批号类型；型号未使用批号时传空串。大小写不敏感匹配型号库配置，
     *   返回值中的 `batchTag` 是型号库里的规范写法。
     * @throws PricingException `E1002` 未配置价格；`E422` 维度参数非法 / 批号与型号不匹配；
     *   `E404` 客户或型号不存在（保留 S04 / S05 的错误码）
     */
    fun resolve(tx: TxContext, customerId: Long, categoryId: Long, batchTag: String): PriceDto

    /** [resolve] 的便捷重载：内部自开一个只读事务。 */
    fun resolve(customerId: Long, categoryId: Long, batchTag: String): PriceDto

    /**
     * 管理员设置 / 修改价格。首次调用创建价格；已有价格必须仍在定价当日，否则 `E1001`。
     *
     * 价格真的发生变化时：同一事务内追加一条 [PriceChangeLog]（只增不改不删），
     * 并同步派发 [PriceChanged] 给已注册的 [PriceChangeListener]（S08 重算）。
     * 首次定价与同价重复提交不产生留痕、不派发事件。
     *
     * @param adminId 已由 S13 认证的管理员 id，仅用于留痕 / 事件；本模块不校验角色
     * @throws PricingException `E1001` 跨日锁定；`E1003` 原因为空；`E422` 金额 / id / 批号非法；
     *   `E404` 客户或型号不存在
     */
    fun changePrice(tx: TxContext, cmd: ChangePriceCmd, adminId: Long): PriceDto

    /** [changePrice] 的便捷重载：内部自开一个写事务。 */
    fun changePrice(cmd: ChangePriceCmd, adminId: Long): PriceDto

    /**
     * 按维度读取改价留痕，最新在前（S14 审计 / 管理员查历史）。
     *
     * 该读方法**不校验客户 / 型号是否仍存在**：主数据被删后历史留痕仍必须可查（硬边界「禁止删除留痕」）。
     * 批号按大小写不敏感匹配。
     *
     * @throws PricingException `E422` id 或批号格式非法
     */
    fun history(tx: TxContext, customerId: Long, categoryId: Long, batchTag: String): List<PriceChangeLog>

    /** [history] 的便捷重载：内部自开一个只读事务。 */
    fun history(customerId: Long, categoryId: Long, batchTag: String): List<PriceChangeLog>
}
