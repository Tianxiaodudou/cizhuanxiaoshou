/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   32 行  接口 CustomerPort
 * 第   43 行  函数 findByPhone
 * 第   46 行  函数 findByPhone
 * 第   53 行  函数 get
 * 第   56 行  函数 get
 * 第   66 行  函数 create
 * 第   69 行  函数 create
 */

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
 * - **电话是客户的唯一 id（业务主键）**：电话只有一种写法——纯数字、无空格、无划线，
 *   入口直接按原值唯一（见 [CustomerService] 的电话口径）；建档遇到已存在的电话时直接返回已有客户；
 * - **地址快照归下游所有**：本 Port 只返回客户当前地址，下单时由 S08 把地址复制进
 *   `OrderDto.customerAddress` 快照；客户资料后续变化不影响历史订单，本模块不存快照；
 * - 不判断权限（铁律 7）：参数里没有 token / 操作者 / 角色，权限由 S13 api-gateway 拦截。
 */
interface CustomerPort {

    /**
     * 按电话查询客户。
     *
     * 电话按客户建档时的原值精确匹配（唯一 id）；只接受纯数字，
     * 含空格 / 划线的输入不是合法电话，直接拒绝而不是替客户端归一。
     *
     * @return 命中的客户；没有命中返回 null（由调用方决定是「老客户直填」还是「新建档案」）
     * @throws CustomerException `E422` 电话为空或格式非法（含空格 / 划线）
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
     * 入参会 trim 名称 / 地址；**电话不 trim、不做任何归一**，必须是纯数字。
     * 电话已存在时**幂等返回已有客户**（不新建、不覆盖既有名称 / 地址）。
     *
     * @throws CustomerException `E422` 名称或电话为空、电话含空格 / 划线等非法字符
     */
    fun create(tx: TxContext, cmd: CreateCustomerCmd): CustomerDto

    /** 便捷重载：内部经 `TxRunner.run {}` 开一个写事务。 */
    fun create(cmd: CreateCustomerCmd): CustomerDto
}
