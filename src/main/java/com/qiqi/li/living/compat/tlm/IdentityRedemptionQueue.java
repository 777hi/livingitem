package com.qiqi.li.living.compat.tlm;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** 按对象身份暂存一次性兑换记录。 */
final class IdentityRedemptionQueue<K, V> {
    record Entry<K, V>(K key, V value) {}

    private final IdentityHashMap<K, V> entries = new IdentityHashMap<>();

    void putIfAbsent(K key, V value) {
        entries.putIfAbsent(key, value);
    }

    List<Entry<K, V>> drain() {
        List<Entry<K, V>> drained = new ArrayList<>(entries.size());
        entries.forEach((key, value) -> drained.add(new Entry<>(key, value)));
        entries.clear();
        return drained;
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }
}
