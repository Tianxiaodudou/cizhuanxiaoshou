/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   23 行  接口 NoteReadPort
 * 第   30 行  函数 get
 */

package com.jiancai.print

import com.jiancai.db.TxContext
import com.jiancai.shared.dto.NoteDto

/**
 * 备注单只读来源（S12 消费侧声明的最小 Port）。
 *
 * 为什么需要它：S10 当前只暴露 `create` / `void`，没有读方法；而 `PrintPort.render(noteNo)`
 * 必须拿到备注单内容才能排版。按「接口只增不改」，S12 在这里声明自己需要的最小读契约，
 * 由组合根用薄适配器接上（S10 追加 `get` 后适配器只有一行，不需要改本模块）。
 *
 * 未装配（构造 [PrintService] 时传 null）时，订单打印不受影响，备注单号会按 `E7001` 返回。
 * 本接口只读，不写备注单、不碰备注表（铁律 3 / 10）。
 */
fun interface NoteReadPort {

    /**
     * 按备注单号读取备注单。
     *
     * @return 找不到时返回 null（由 S12 统一翻译成 `E7001`），不要抛「不存在」异常
     */
    fun get(tx: TxContext, noteNo: String): NoteDto?
}
