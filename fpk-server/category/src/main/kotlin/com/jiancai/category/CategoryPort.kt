/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   40 行  接口 CategoryPort
 * 第   47 行  函数 list
 * 第   50 行  函数 list
 * 第   57 行  函数 get
 * 第   60 行  函数 get
 * 第   69 行  函数 create
 * 第   72 行  函数 create
 * 第   80 行  函数 update
 * 第   83 行  函数 update
 * 第   93 行  函数 delete
 * 第   96 行  函数 delete
 */

package com.jiancai.category

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.CategoryCmd
import com.jiancai.shared.dto.CategoryDto

/**
 * 品类 / 型号库 Port：S05 唯一对外入口。
 *
 * 出参一律是共享契约的 [CategoryDto]（铁律 2），不暴露持久化结构；
 * 入参是 [CategoryCmd]（`id` 为空 = 新建，非空 = 修改）。
 *
 * 事务口径（铁律 4）：
 * - 带 [TxContext] 的重载复用调用方事务，供「型号库变更 + 其它模块写入」原子提交；
 * - 便捷重载内部仍走 `TxRunner.run {}`，不存在非事务写入路径。
 *
 * 权限口径（铁律 7）：本 Port 的**任何参数都不是调用者身份**
 * （没有 token、没有操作者、没有角色），因此模块内不可能判权限；
 * 「仅管理员可改型号库」由 S13 api-gateway 在转发前 `verify → ADMIN` 拦截（否则 403）。
 *
 * 读方法同时给 S08 order / S06 pricing 使用：下单与定价只通过本 Port 取型号数据，
 * 禁止跨模块直读 `category` / `category_batch_tag` 表（铁律 3 / 10）。
 */
interface CategoryPort {

    /**
     * 型号列表，按「品类名 + 型号」升序（稳定展示顺序）。
     *
     * @param enabledOnly `true` 只返回启用中的型号（录入页下拉框用）；`false` 返回全部（管理员维护页用）
     */
    fun list(tx: TxContext, enabledOnly: Boolean = false): List<CategoryDto>

    /** [list] 的便捷重载：内部自开一个只读事务。 */
    fun list(enabledOnly: Boolean = false): List<CategoryDto>

    /**
     * 按 id 取单个型号。
     *
     * @throws CategoryException 型号不存在（`E404`）
     */
    fun get(tx: TxContext, id: Long): CategoryDto

    /** [get] 的便捷重载：内部自开一个只读事务。 */
    fun get(id: Long): CategoryDto

    /**
     * 新建型号。`cmd.id` 必须为空（带 id 调用会按 `E422` 拒绝，避免「新建」被误用成覆盖写）。
     *
     * 服务端负责：裁剪空白、批号类型去重、装箱规格校验、`created_at` 产出、唯一性兜底。
     *
     * @throws CategoryException 入参非法（`E422`）或「品类 + 型号」重复（`E409`）
     */
    fun create(tx: TxContext, cmd: CategoryCmd): CategoryDto

    /** [create] 的便捷重载：内部自开一个写事务。 */
    fun create(cmd: CategoryCmd): CategoryDto

    /**
     * 修改型号（全量覆盖：名称 / 型号 / 单位 / 批号类型 / 装箱规格 / 启用状态）。
     *
     * @throws CategoryException `cmd.id` 为空（`E422`）、型号不存在（`E404`）、
     *   或改成与其它型号重名（`E409`）
     */
    fun update(tx: TxContext, cmd: CategoryCmd): CategoryDto

    /** [update] 的便捷重载：内部自开一个写事务。 */
    fun update(cmd: CategoryCmd): CategoryDto

    /**
     * 删除型号（硬删除，批号类型一并删除）。
     *
     * 有历史单据的型号建议改用 `update(cmd.copy(enabled = false))` 停用：
     * 订单行保存了品类名 / 型号快照，删除不会破坏历史展示，但会失去下拉可选性。
     *
     * @throws CategoryException 型号不存在（`E404`）
     */
    fun delete(tx: TxContext, id: Long)

    /** [delete] 的便捷重载：内部自开一个写事务。 */
    fun delete(id: Long)
}
