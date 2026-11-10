package com.jiancai.customer

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.CreateCustomerCmd
import com.jiancai.shared.dto.CustomerDto

/**
 * 客户 Port：S04 唯一对外入口（建档、按电话查询、按主键读取）。
 *
 * 契约口径：
 * - 出参一律 [CustomerDto]，不暴露 `customer` 表的持久化结构（铁律 2）；
 * - 每个方法都有带 [TxContext] 的重载：调用方把「客户建档 + 订单写入」放进同一个
 *   db-core 事务（铁律 4）；便捷重载内部同样经 `TxRunner.run {}`，不存在非事务写入；
 * - **电话是客户的自然键**：建档时按规范化后的电话做唯一约束（见 [CustomerService] 的电话口径），
 *   同一号码的 `138-0000-0000` / `13800000000` 视为同一客户，重复建档抛 `E409`；
 * - **地址快照归下游所有**：本 Port 只返回客户当前地址，下单时由 S08 把地址复制进
 *   `OrderDto.customerAddress` 快照；客户资料后续变化不影响历史订单，本模块不存快照；
 * - 不判断权限（铁律 7）：参数里没有 token / 操作者 / 角色，权限由 S13 api-gateway 拦截。
 */
interface CustomerPort {

    /**
     * 按电话查询客户。
     *
     * 电话先 trim 并去掉空白与连字符再匹配（与建档时的规范化口径一致），
     * 因此 `" 0574-12345678 "` 与 `"057412345678"` 查到同一条客户。
     *
     * @return 命中的客户；没有命中返回 null（由调用方决定是「老客户直填」还是「新建档案」）
     * @throws CustomerException `E422` 电话为空（规范化后没有任何数字/字符）
     */
    fun findByPhone(tx: TxContext, phone: String): CustomerDto?

    /** 便捷重载：内部经 `TxRunner.run {}` 开一个只读事务。 */
    fun findByPhone(phone: String): CustomerDto?

    /**
     * 按主键读取客户。
     *
     * @throws CustomerException `E404` 客户不存在
     */
    fun get(tx: TxContext, id: Long): CustomerDto

    /** 便捷重载：内部经 `TxRunner.run {}` 开一个只读事务。 */
    fun get(id: Long): CustomerDto

    /**
     * 客户建档，返回落库后的 DTO（含服务端产出的 id 与 `created_at`）。
     *
     * 入参会 trim：名称 / 电话去掉首尾空白后不得为空；地址去掉首尾空白，空白视为 null。
     *
     * @throws CustomerException `E422` 名称或电话为空、`E409` 该电话已建档
     */
    fun create(tx: TxContext, cmd: CreateCustomerCmd): CustomerDto

    /** 便捷重载：内部经 `TxRunner.run {}` 开一个写事务。 */
    fun create(cmd: CreateCustomerCmd): CustomerDto
}
