package com.qiqi.li.living.core;

import java.util.HashMap;
import java.util.Map;

public class ComponentConfig {

    private final Map<String, Object> params = new HashMap<>();

    public <T> T get(String key, Class<T> type, T defaultValue) {
        Object value = params.get(key);
        if (value == null) return defaultValue;
        return type.cast(value);
    }

    public void set(String key, Object value) {
        params.put(key, value);
    }

    public static ComponentConfig of(String key, Object value) {
        ComponentConfig config = new ComponentConfig();
        config.set(key, value);
        return config;
    }

    public static ComponentConfig of(String k1, Object v1, String k2, Object v2) {
        ComponentConfig config = new ComponentConfig();
        config.set(k1, v1);
        config.set(k2, v2);
        return config;
    }

    public static ComponentConfig of(String k1, Object v1, String k2, Object v2,
                                      String k3, Object v3) {
        ComponentConfig config = new ComponentConfig();
        config.set(k1, v1);
        config.set(k2, v2);
        config.set(k3, v3);
        return config;
    }

    public static ComponentConfig empty() {
        return new ComponentConfig();
    }
}