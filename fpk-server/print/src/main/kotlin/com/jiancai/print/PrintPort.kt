/*
 * @file fpk-server/print/src/main/kotlin/com/jiancai/print/PrintPort.kt
 * @role 打印 Port：S12 唯一对外入口（80mm 渲染 + 打印次数）。
 * @exports PrintPort
 * @depends db-core
 * @calledBy fpk-server/api-gateway/src/main/kotlin/com/jiancai/gateway/GatewayDeps.kt, fpk-server/app/src/main/kotlin/com/jiancai/app/GatewayMain.kt, fpk-server/print/src/main/kotlin/com/jiancai/print/PrintService.kt
 * @sideEffects 经 TxRunner / Port 间接读写数据库
 */

package com.jiancai.print

import com.jiancai.db.TxContext

/**
 * 打印 Port：S12 唯一对外入口（80mm 渲染 + 打印次数）。
 *
 * 契约口径：
 * - **打印只在服务端**：本 Port 返回的是服务端渲染好的文本 / DTO，客户端只展示「已打印 / 次数」，
 *   永远不参与排版，也不直连打印机（铁律 6）；
 * - 出参一律 [PrintDocumentDto]，不暴露 `print_job` 的持久化结构（铁律 2）；
 * - 每个方法都有带 [TxContext] 的重载：调用方把「打印 + 其它写入」放进同一个 db-core 事务
 *   （铁律 4）；便捷重载内部走 `TxRunner.run {}`，不存在非事务写入；
 * - **订单只经 S08 `OrderPort` 读取**：本模块没有任何订单表的 SQL（铁律 3 / 10）；
 * - **商家名称只经 S01 `ConfigPort` 读取**，不在打印层写死；
 * - **不生成单号**：单号由 S07 / S08 产出，本模块只接收与展示（铁律 8）；
 * - **不判权限**（铁律 7）：参数里没有 token / 角色；[printedBy] 是 S13 已认证的操作者 id，
 *   只用于 `printed_by` 留痕。
 *
 * 单号识别：`render` 先按订单号经 S08 查询，查不到再按备注单号经 [NoteReadPort] 查询，
 * **不截取 / 猜测单号前缀**（铁律 8）；两者都查不到时抛 `E7001`。
 *
 * 打印次数：
 * - 每次成功 `render` 落一行 `print_job`，同一次事务内计数；失败回滚不消耗次数；
 * - 同一单据达到 [PrintPort.DEFAULT_MAX_PRINTS]（或构造时注入的上限）后再打印抛 `E7002`；
 * - 次数持久化在库中，重启 / 换实例不会归零。
 */
interface PrintPort {

    /**
     * 渲染订单 / 备注单（带调用方事务）。
     *
     * @param docNo 订单号（PS/PT/ZS/ZT）或备注单号（BZ），由服务端产出；客户端只回传
     * @param printedBy S13 已认证的操作者 id，仅用于留痕；可为 null（系统补打）
     * @return 80mm 渲染结果 + 本次打印后的累计次数
     * @throws PrintException `E7001` 单据不存在；`E7002` 打印次数已达上限；
     *   `E422` 单号格式非法 / 打印人 id 非法
     */
    fun render(tx: TxContext, docNo: String, printedBy: Long? = null): PrintDocumentDto

    /** [render] 的便捷重载：内部开一个写事务。 */
    fun render(docNo: String, printedBy: Long? = null): PrintDocumentDto

    companion object {
        /** 同一单据默认最多打印 3 次（首打 + 2 次补打）；部署可经构造参数覆盖。 */
        const val DEFAULT_MAX_PRINTS: Int = 3
    }
}
