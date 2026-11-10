package com.jiancai.customer

import com.jiancai.db.TxContext
import com.jiancai.db.TxRunner
import com.jiancai.shared.dto.CreateCustomerCmd
import com.jiancai.shared.dto.CustomerDto
import com.jiancai.shared.errors.ErrorCode
import java.sql.SQLException
import java.time.Clock
import java.time.Instant

/**
 * [CustomerPort] 的唯一实现：客户建档与查询的用例层。
 *
 * 职责边界：
 * - **不判权限**：构造参数与参数里都没有调用者身份，权限只由 S13 api-gateway 拦截（铁律 7）；
 * - **不碰别模块的表**：只读写自己的 `customer` 表，SQL 只在 [CustomerRepository]（铁律 3 / 10）；
 * - **地址只提供当前值**：下单时的地址快照由 S08 复制保存，本模块不存快照、也不回填历史订单；
 * - 自己负责用例级规则：入参裁剪、电话规范化与唯一性、`E404` / `E409` / `E422` 的判定。
 *
 * 电话口径（v1.0.0）：
 * - 展示值 `phone` 只做 trim，保留用户输入的连字符等写法；
 * - 规范化键 `phone_key` 去掉所有空白与 `-`，用于唯一约束与查询：`" 0574-12345678 "`
 *   与 `"057412345678"` 是同一个客户，先建档者胜出，后建档者 `E409`；
 * - 规范化后为空（如 `"---"`）视为非法入参 `E422`。
 *
 * @param txRunner db-core 的事务 Port（铁律 4：全部写操作经它包裹）
 * @param clock 服务端时钟（测试注入固定时间；`created_at` 用 UTC ISO-8601）
 */
class CustomerService(
    private val txRunner: TxRunner,
    private val clock: Clock = Clock.systemDefaultZone(),
) : CustomerPort {

    private val repository = CustomerRepository()

    // —— 读 ——

    override fun findByPhone(phone: String): CustomerDto? = txRunner.run { tx -> findByPhone(tx, phone) }

    override fun findByPhone(tx: TxContext, phone: String): CustomerDto? {
        val phoneKey = phoneKeyOf(phone) ?: throw CustomerException(ErrorCode.E422, "电话不能为空")
        return repository.findByPhoneKey(tx, phoneKey)?.toDto()
    }

    override fun get(id: Long): CustomerDto = txRunner.run { tx -> get(tx, id) }

    override fun get(tx: TxContext, id: Long): CustomerDto =
        repository.findById(tx, id)?.toDto() ?: throw CustomerException(ErrorCode.E404, "客户不存在")

    // —— 写 ——

    override fun create(cmd: CreateCustomerCmd): CustomerDto = txRunner.run { tx -> create(tx, cmd) }

    override fun create(tx: TxContext, cmd: CreateCustomerCmd): CustomerDto {
        val name = cmd.name.trim()
        if (name.isEmpty()) throw CustomerException(ErrorCode.E422, "客户名称不能为空")

        val phone = cmd.phone.trim()
        val phoneKey = phoneKeyOf(phone) ?: throw CustomerException(ErrorCode.E422, "电话不能为空")

        val address = cmd.address?.trim()?.takeIf { it.isNotEmpty() }

        // 先查再写：唯一约束是并发下的最终兜底，这里先把「已建档」翻译成可读的 E409。
        if (repository.findByPhoneKey(tx, phoneKey) != null) {
            throw CustomerException(ErrorCode.E409, "该电话已建档")
        }

        val id = try {
            repository.insert(
                tx = tx,
                name = name,
                phone = phone,
                phoneKey = phoneKey,
                address = address,
                createdAt = Instant.now(clock).toString(),
            )
        } catch (failure: SQLException) {
            if (isUniquePhoneViolation(failure)) {
                throw CustomerException(ErrorCode.E409, "该电话已建档", failure)
            }
            throw failure
        }

        return repository.findById(tx, id)!!.toDto()
    }

    /**
     * 电话规范化键：去掉所有空白与 `-`；规范化后为空返回 null（由调用方转 `E422`）。
     *
     * 只做「同一号码不同写法」的归一，不做号码段校验：固话区号、分机、国际前缀都原样保留，
     * 避免服务端用一套猜测的号码规则误拒真实客户。
     */
    private fun phoneKeyOf(raw: String): String? =
        raw.filterNot { it.isWhitespace() || it == '-' }.ifEmpty { null }

    /** SQLite 唯一约束冲突的识别（单连接 + TxRunner 串行化下极少发生，属并发兜底）。 */
    private fun isUniquePhoneViolation(failure: SQLException): Boolean =
        failure.message?.contains("UNIQUE constraint failed", ignoreCase = true) == true
}
