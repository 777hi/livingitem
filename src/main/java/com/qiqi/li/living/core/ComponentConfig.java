package com.qiqi.li.living.core;

import java.util.HashMap;
import java.util.Map;

/**
 * 组件配置参数容器 —— 为组件提供类型安全的配置参数访问。
 *
 * 每个组件在注册到 LivingFunctionConfig 时可以携带配置参数。
 * 组件通过 get() 方法按 key 读取参数，支持类型转换和默认值。
 *
 * 使用示例：
 * <pre>
 * // 创建配置
 * ComponentConfig config = ComponentConfig.of("total_ticks", 200);
 *
 * // 读取配置
 * int total = config.get("total_ticks", Integer.class, 200);  // 返回 200
 * String name = config.get("name", String.class, "default");  // 返回 "default"
 * </pre>
 */
public class ComponentConfig {

    /** 参数存储 */
    private final Map<String, Object> params = new HashMap<>();

    /**
     * 获取配置参数值。
     *
     * @param key 参数键名
     * @param type 期望的类型
     * @param defaultValue 默认值（参数不存在时返回）
     * @return 参数值或默认值
     */
    public <T> T get(String key, Class<T> type, T defaultValue) {
        Object value = params.get(key);
        if (value == null) return defaultValue;
        return type.cast(value);
    }

    /**
     * 设置配置参数值。
     *
     * @param key 参数键名
     * @param value 参数值
     */
    public void set(String key, Object value) {
        params.put(key, value);
    }

    /** 创建包含单个参数的配置 */
    public static ComponentConfig of(String key, Object value) {
        ComponentConfig config = new ComponentConfig();
        config.set(key, value);
        return config;
    }

    /** 创建包含两个参数的配置 */
    public static ComponentConfig of(String k1, Object v1, String k2, Object v2) {
        ComponentConfig config = new ComponentConfig();
        config.set(k1, v1);
        config.set(k2, v2);
        return config;
    }

    /** 创建包含三个参数的配置 */
    public static ComponentConfig of(String k1, Object v1, String k2, Object v2,
                                      String k3, Object v3) {
        ComponentConfig config = new ComponentConfig();
        config.set(k1, v1);
        config.set(k2, v2);
        config.set(k3, v3);
        return config;
    }

    /** 创建空配置 */
    public static ComponentConfig empty() {
        return new ComponentConfig();
    }
}