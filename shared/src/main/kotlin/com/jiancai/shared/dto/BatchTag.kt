/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   19 行  对象 BatchTagRules
 * 第   21 行  函数 normalize
 * 第   29 行  函数 comparisonKey
 * 第   38 行  函数 same
 */

package com.jiancai.shared.dto

/**
 * 批号口径（唯一真源）：去首尾空白；比较时 ASCII 大小写不敏感。
 *
 * 口径与 S08 的 SQLite `COLLATE NOCASE` 对齐：只折叠 ASCII `A-Z`，非 ASCII 字符原样比较。
 * 批号字段在 DTO 中仍是 `String`（字段签名只增不改），服务端各模块统一经本对象做归一与比较，
 * 客户端只展示服务端下发的批号写法，不自行 trim / 比较。
 */
object BatchTagRules {
    /** 归一化：裁剪首尾空白（[WireFormat.BATCH_TAG_TRIM_WHITESPACE] 为唯一口径开关）。 */
    fun normalize(raw: String): String =
        if (WireFormat.BATCH_TAG_TRIM_WHITESPACE) raw.trim() else raw

    /**
     * 比较键：归一化 + ASCII 大小写折叠（`'A'..'Z'` → 小写），与 SQLite `NOCASE` 行为一致。
     *
     * 例：`" A1 "` 与 `"a1"` 的比较键都是 `"a1"`。
     */
    fun comparisonKey(raw: String): String {
        val normalized = normalize(raw)
        if (!WireFormat.BATCH_TAG_IGNORE_CASE) return normalized
        return buildString(normalized.length) {
            normalized.forEach { char -> append(if (char in 'A'..'Z') char + 32 else char) }
        }
    }

    /** 两个批号按同一口径是否为同一批次。 */
    fun same(left: String, right: String): Boolean = comparisonKey(left) == comparisonKey(right)
}
