package com.qiqi.li.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一日志工具类。
 * 每个模块有独立的 Logger 实例，可在 log4j2.xml 中按模块配置日志级别。
 * <p>
 * 使用方式：
 * <pre>{@code
 * ModLog.TELEPORT.info("传送开始: player={} dest=({},{},{})", name, x, y, z);
 * ModLog.PERF.warn("区块加载耗时: {}ms", elapsed);
 * }</pre>
 * <p>
 * Logger 名称（可在 log4j2.xml 中配置）：
 * <ul>
 *   <li>{@code living_item.perf} - 性能指标</li>
 *   <li>{@code living_item.teleport} - 传送事件</li>
 *   <li>{@code living_item.container} - 容器操作</li>
 *   <li>{@code living_item.map} - 地图事件</li>
 *   <li>{@code living_item.ender} - 末影箱/通道</li>
 *   <li>{@code living_item.mixin} - Mixin 调试</li>
 *   <li>{@code living_item.general} - 通用 / 生命周期</li>
 * </ul>
 * <p>
 * 日志级别建议：
 * <ul>
 *   <li>PERF: DEBUG（开发时） / INFO（生产时记录慢操作）</li>
 *   <li>TELEPORT: INFO</li>
 *   <li>CONTAINER: WARN</li>
 *   <li>MAP: INFO</li>
 *   <li>ENDER: INFO</li>
 *   <li>MIXIN: DEBUG</li>
 *   <li>GENERAL: INFO</li>
 * </ul>
 */
public final class ModLog {

    public static final Logger PERF      = LoggerFactory.getLogger("living_item.perf");
    public static final Logger TELEPORT  = LoggerFactory.getLogger("living_item.teleport");
    public static final Logger CONTAINER = LoggerFactory.getLogger("living_item.container");
    public static final Logger MAP       = LoggerFactory.getLogger("living_item.map");
    public static final Logger ENDER     = LoggerFactory.getLogger("living_item.ender");
    public static final Logger MIXIN     = LoggerFactory.getLogger("living_item.mixin");
    public static final Logger GENERAL   = LoggerFactory.getLogger("living_item.general");

    private ModLog() {}
}