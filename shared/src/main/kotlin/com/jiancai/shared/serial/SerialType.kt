package com.jiancai.shared.serial

import com.jiancai.shared.enums.DocType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 单号前缀表（唯一真源）：PS 采购单 / PT 采购退货 / ZS 直送单 / ZT 直送退货 / BZ 备注单 / JS 结算单。
 *
 * 单号只由 S07 serial 在事务内生成，形如「前缀 + 年(4) + 月日(4) + 全局序号(4)」，
 * 例如 `PS202611100001`。仅 S08 order 通过 SerialPort 取号；
 * 其它服务端模块与客户端一律禁止拼接、截取或猜测前缀（硬性约束 R8）。
 *
 * 本枚举只承载「前缀 ↔ 单据类型」的对应关系，序号分配逻辑属于 S07，不在契约层。
 */
@Serializable
enum class SerialType(val prefix: String, val docType: DocType) {
    @SerialName("ps")
    PURCHASE("PS", DocType.PURCHASE),

    @SerialName("pt")
    PURCHASE_RETURN("PT", DocType.PURCHASE_RETURN),

    @SerialName("zs")
    DIRECT("ZS", DocType.DIRECT),

    @SerialName("zt")
    DIRECT_RETURN("ZT", DocType.DIRECT_RETURN),

    @SerialName("bz")
    NOTE("BZ", DocType.NOTE),

    @SerialName("js")
    SETTLEMENT("JS", DocType.SETTLEMENT);

    companion object {
        /** 取前缀的唯一入口：按单据类型查，禁止调用方自行写 "PS" 之类的字面量。 */
        fun of(docType: DocType): SerialType =
            values().firstOrNull { it.docType == docType }
                ?: throw IllegalArgumentException("单据类型未映射单号前缀：$docType")
    }
}
