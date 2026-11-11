/*
 * ===== 本文件代码地图（自动生成，改代码后请重跑 docs/tools/gen-code-map.ps1） =====
 * ===== 行号为本文件真实起始行，格式：行号 / 种类 / 名称 =====
 * 第   22 行  接口 ConfigPort
 * 第   33 行  函数 getMerchantName
 * 第   40 行  函数 getConfig
 */

package com.jiancai.config

import com.jiancai.shared.dto.ConfigDto

/**
 * 部署配置 Port：S01 唯一对外入口（客户端只能读，不能传）。
 *
 * 商家名称是**部署级**信息：由部署方通过服务端环境变量 / JVM 参数提供，
 * 启动时写入本模块的 `deployment_config` 表，之后只读。HTTP 层不提供写入口，
 * 本 Port 也没有 setter——客户端请求永远无法修改部署配置。
 *
 * 返回标量 / DTO，不返回 Entity。实现必须在数据库迁移完成后构造。
 */
interface ConfigPort {

    /**
     * 当前部署的商家名称（非空，启动时已确定）。
     *
     * 读端口不参与跨模块写事务，因此不接收 `TxContext`；唯一的写路径在
     * [ConfigService] 的构造阶段（启动装配），已经通过 `TxRunner.run {}` 包裹。
     * 名称在服务生命周期内不可变，部署配置变更后需重启生效。
     *
     * @return 已去除首尾空白的非空商家名称
     */
    fun getMerchantName(): String

    /**
     * 部署级配置 DTO（当前只有商家名称），供 S13 组装握手 / 配置响应。
     *
     * 与 [getMerchantName] 同源，不额外读库；DTO 定义在 `shared`，两端唯一真源。
     */
    fun getConfig(): ConfigDto
}
