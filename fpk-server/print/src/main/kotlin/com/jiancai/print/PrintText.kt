/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   22 行  对象 PrintText
 * 第   25 行  类 Align
 * 第   33 行  函数 displayWidth
 * 第   49 行  函数 take
 * 第   68 行  函数 fit
 * 第   86 行  函数 wrap
 * 第  111 行  函数 sanitize
 * 第  114 行  函数 isWide
 */

package com.jiancai.print

/**
 * 80mm 热敏纸的等宽文本工具。
 *
 * 热敏打印机按「半角字符」计算列宽：ASCII 占 1 格，中文 / 全角标点占 2 格。
 * 本对象是 S12 唯一的宽度 / 截断 / 填充实现，避免排版代码各写一套 `length`（中文会算错）。
 */
internal object PrintText {

    /** 对齐方式。 */
    enum class Align { LEFT, CENTER, RIGHT }

    /**
     * 显示宽度：ASCII = 1，CJK / 全角 = 2。
     *
     * 只覆盖收据会用到的区间（CJK、假名、谚文、全角符号、常见 emoji）；
     * 代理对按一个码点处理，不会把 emoji 截成半个。
     */
    fun displayWidth(text: String): Int {
        var width = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            width += if (isWide(codePoint)) 2 else 1
            index += Character.charCount(codePoint)
        }
        return width
    }

    /**
     * 截断到 [width] 个半角格：超宽时整码点丢弃（不会留下半个中文 / 半个代理对）。
     *
     * 换行 / 制表符会被替换成空格，保证一行就是一行。
     */
    fun take(text: String, width: Int): String {
        require(width >= 0) { "列宽不能为负：$width" }
        val clean = sanitize(text)
        if (width == 0) return ""
        val builder = StringBuilder()
        var used = 0
        var index = 0
        while (index < clean.length) {
            val codePoint = clean.codePointAt(index)
            val codePointWidth = if (isWide(codePoint)) 2 else 1
            if (used + codePointWidth > width) break
            builder.appendCodePoint(codePoint)
            used += codePointWidth
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }

    /** 截断 + 按 [align] 补空格到精确 [width]。 */
    fun fit(text: String, width: Int, align: Align): String {
        val clipped = take(text, width)
        val padding = width - displayWidth(clipped)
        return when (align) {
            Align.LEFT -> clipped + " ".repeat(padding)
            Align.RIGHT -> " ".repeat(padding) + clipped
            Align.CENTER -> {
                val left = padding / 2
                " ".repeat(left) + clipped + " ".repeat(padding - left)
            }
        }
    }

    /**
     * 按显示宽度折行（长地址 / 备注用）。
     *
     * 中文按整字断开；不保证不切断英文单词（收据上半角单词很短，切断可接受）。
     */
    fun wrap(text: String, width: Int): List<String> {
        require(width > 0) { "折行宽度必须大于 0：$width" }
        val clean = sanitize(text).trim()
        if (clean.isEmpty()) return listOf("")

        val lines = mutableListOf<String>()
        val builder = StringBuilder()
        var used = 0
        var index = 0
        while (index < clean.length) {
            val codePoint = clean.codePointAt(index)
            val codePointWidth = if (isWide(codePoint)) 2 else 1
            if (used + codePointWidth > width && builder.isNotEmpty()) {
                lines += builder.toString().trim()
                builder.clear()
                used = 0
            }
            builder.appendCodePoint(codePoint)
            used += codePointWidth
            index += Character.charCount(codePoint)
        }
        lines += builder.toString().trim()
        return lines
    }

    private fun sanitize(text: String): String =
        text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')

    private fun isWide(codePoint: Int): Boolean =
        codePoint in 0x1100..0x115F ||
            codePoint in 0x2E80..0xA4CF ||
            codePoint in 0xAC00..0xD7A3 ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFE10..0xFE19 ||
            codePoint in 0xFE30..0xFE6F ||
            codePoint in 0xFF00..0xFF60 ||
            codePoint in 0xFFE0..0xFFE6 ||
            codePoint in 0x1F300..0x1FAFF ||
            codePoint in 0x20000..0x3FFFD
}
