/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   45 行  类 CustomerService
 * 第   58 行  函数 findByPhone
 * 第   60 行  函数 findByPhone
 * 第   65 行  函数 get
 * 第   67 行  函数 get
 * 第   72 行  函数 create
 * 第   74 行  函数 create
 * 第  111 行  函数 requireCanonicalPhone
 * 第  118 行  函数 isUniquePhoneViolation
 * 第  121 行  对象 ?
 */

package com.jiancai.customer

import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.shared.dto.CreateCustomerCmd
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.dto.WireFormat
import com.jiancai.shared.errors.ErrorCode
import java.sql.SQLException
import java.time.Clock
import java.time.format.DateTimeFormatter

/**
 * [CustomerPort] 的唯一实现：客户建档与查询的用例层。
 *
 * 职责边界：
 * - **不判权限**：构造参数与参数里都没有调用者身份，权限只由 S13 api-gateway 拦截（铁律 7）；
 * - **不碰别模块的表**：只读写自己的 `customer` 表，SQL 只在 [CustomerRepository]（铁律 3 / 10）；
 * - **地址只提供当前值**：下单时的地址快照由 S08 复制保存，本模块不存快照、也不回填历史订单；
 * - 自己负责用例级规则：入参裁剪、电话格式与唯一性、`E404` / `E422` 的判定、电话重复时的幂等返回。
 *
 * 电话口径（v1.0.0）：
 * - 电话就是客户的唯一 id（业务主键），**只有一种格式：纯数字，无空格、无划线**；
 * - 服务端不做「去空格 / 去划线」的归一，也不接受这类输入：`E422`，避免同一号码出现多种写法；
 * - 建档遇到已存在的电话时**直接返回已有客户（幂等）**，不新建第二条、不覆盖已有名称 / 地址。
 *
 * @param txRunner db-core 的事务 Port（铁律 4：全部写操作经它包裹）
 * @param clock 服务端时钟（测试注入固定时间；`created_at` 按 `shared` 的 `WireFormat.TIMESTAMP` 输出）
 */
class CustomerService(
    private val txRunner: TxRunner,
    private val clock: Clock = Clock.systemDefaultZone(),
) : CustomerPort {

    private val repository = CustomerRepository()

    /** 线格式时间戳（唯一真源 `WireFormat.TIMESTAMP`）；按服务端时区输出。 */
    private val wireTimestamps: DateTimeFormatter =
        DateTimeFormatter.ofPattern(WireFormat.TIMESTAMP).withZone(clock.zone)

    // —— 读 ——

    override fun findByPhone(phone: String): CustomerDto? = txRunner.run { tx -> findByPhone(tx, phone) }

    override fun findByPhone(tx: TxContext, phone: String): CustomerDto? {
        requireCanonicalPhone(phone)
        return repository.findByPhone(tx, phone)?.toDto()
    }

    override fun get(id: Long): CustomerDto = txRunner.run { tx -> get(tx, id) }

    override fun get(tx: TxContext, id: Long): CustomerDto =
        repository.findById(tx, id)?.toDto() ?: throw CustomerException(ErrorCode.E404, "客户不存在")

    // —— 写 ——

    override fun create(cmd: CreateCustomerCmd): CustomerDto = txRunner.run { tx -> create(tx, cmd) }

    override fun create(tx: TxContext, cmd: CreateCustomerCmd): CustomerDto {
        val name = cmd.name.trim()
        if (name.isEmpty()) throw CustomerException(ErrorCode.E422, "客户名称不能为空")

        val phone = cmd.phone
        requireCanonicalPhone(phone)

        val address = cmd.address?.trim()?.takeIf { it.isNotEmpty() }

        // 电话是客户的唯一 id：已建档则直接返回已有客户（幂等），不覆盖既有名称 / 地址。
        repository.findByPhone(tx, phone)?.let { return it.toDto() }

        val id = try {
            repository.insert(
                tx = tx,
                name = name,
                phone = phone,
                address = address,
                createdAt = wireTimestamps.format(clock.instant()),
            )
        } catch (failure: SQLException) {
            // 并发下另一请求刚插入同号：以库中已有行为准，仍保持幂等。
            if (isUniquePhoneViolation(failure)) {
                return repository.findByPhone(tx, phone)?.toDto() ?: throw failure
            }
            throw failure
        }

        return repository.findById(tx, id)!!.toDto()
    }

    /**
     * 校验电话只有一种写法：纯数字、无空格、无划线。
     *
     * 不做 trim / 替换：客户端必须发送规范值，服务端不接受「同号不同写法」，
     * 从入口保证「电话 = 客户唯一 id」不会被多种写法破坏。
     */
    private fun requireCanonicalPhone(phone: String) {
        if (!CANONICAL_PHONE.matches(phone)) {
            throw CustomerException(ErrorCode.E422, "电话格式不正确：只允许数字，不含空格和划线")
        }
    }

    /** SQLite 唯一约束冲突的识别（单连接 + TxRunner 串行化下极少发生，属并发兜底）。 */
    private fun isUniquePhoneViolation(failure: SQLException): Boolean =
        failure.message?.contains("UNIQUE constraint failed", ignoreCase = true) == true

    private companion object {
        /** 唯一允许的电话写法：至少一位数字，不含任何其它字符。 */
        val CANONICAL_PHONE = Regex("[0-9]+")
    }
}
