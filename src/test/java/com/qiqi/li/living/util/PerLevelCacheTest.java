package com.qiqi.li.living.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

/**
 * {@link PerLevelCache} 的维度隔离契约：读写都只能按维度，永不出现全服单表语义。
 */
class PerLevelCacheTest {

    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceLocation NETHER = ResourceLocation.withDefaultNamespace("nether");

    @Test
    void bucketsAreIsolatedByLevel() {
        PerLevelCache<String, Integer> cache = new PerLevelCache<>();
        cache.bucket(OVERWORLD).put("a", 1);
        cache.bucket(NETHER).put("b", 2);

        assertEquals(Map.of("a", 1), cache.peek(OVERWORLD));
        assertEquals(Map.of("b", 2), cache.peek(NETHER));
        assertEquals(Map.of(), cache.peek(ResourceLocation.withDefaultNamespace("end")));
    }

    @Test
    void takeDetachesBucketFromCache() {
        PerLevelCache<String, Integer> cache = new PerLevelCache<>();
        cache.bucket(NETHER).put("b", 2);

        Map<String, Integer> taken = cache.take(NETHER);
        taken.put("c", 3);   // 已脱离缓存，写它不影响缓存

        assertEquals(Map.of(), cache.peek(NETHER));
        assertEquals(Map.of("b", 2, "c", 3), taken);
    }

    @Test
    void replaceWithEmptyDropsBucket() {
        PerLevelCache<String, Integer> cache = new PerLevelCache<>();
        cache.bucket(OVERWORLD).put("a", 1);

        cache.replace(OVERWORLD, Map.of());

        assertEquals(Map.of(), cache.peek(OVERWORLD));
    }

    @Test
    void dropIfEmptyOnlyRemovesEmptyBucket() {
        PerLevelCache<String, Integer> cache = new PerLevelCache<>();
        cache.bucket(OVERWORLD).put("a", 1);

        cache.dropIfEmpty(OVERWORLD);
        assertEquals(Map.of("a", 1), cache.peek(OVERWORLD));

        cache.bucket(OVERWORLD).clear();
        cache.dropIfEmpty(OVERWORLD);
        assertEquals(Map.of(), cache.peek(OVERWORLD));
    }

    @Test
    void peekIsReadOnly() {
        PerLevelCache<String, Integer> cache = new PerLevelCache<>();
        cache.bucket(OVERWORLD).put("a", 1);

        Map<String, Integer> view = cache.peek(OVERWORLD);
        assertThrows(UnsupportedOperationException.class, () -> view.put("b", 2));
    }

    @Test
    void clearRemovesAllLevels() {
        PerLevelCache<String, Integer> cache = new PerLevelCache<>();
        cache.bucket(OVERWORLD).put("a", 1);
        cache.bucket(NETHER).put("b", 2);

        cache.clear();

        assertTrue(cache.peek(OVERWORLD).isEmpty());
        assertTrue(cache.peek(NETHER).isEmpty());
    }
}
