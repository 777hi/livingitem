package com.qiqi.li.living.domain.tools;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 活工具 FakePlayer 缓存（{@code L26 = d}）：<b>按（维度, 主人 UUID）共享实例</b>。
 *
 * <h3>为什么可以共享</h3>
 * FakePlayer 在活工具场景里是<b>无状态执行器</b>：
 * <ul>
 *   <li>破坏进度不在它身上 —— 存在活工具的 {@code LivingToolMemory} / 进度组件里</li>
 *   <li>服务端 tick 单线程串行 —— 不存在并发冲突</li>
 *   <li>每次使用前只需三项配置：{@code setPos}、{@code setOnGround(true)}、
 *       {@code setItemInHand}</li>
 * </ul>
 * 所以「3 把活镐子同时挖」= 一个假玩家轮流拿 3 把镐子，无需 3 个实例。
 * （每个活工具一个实例的话，塞满的大箱子就是 27 个 ServerPlayer，太重。）
 *
 * <h3>键为什么含主人 UUID</h3>
 * 主人 UUID 决定 FakePlayer 的身份（{@code L25}），而 GameProfile 构造后不好改，
 * 故不同主人必须各持一个实例。
 */
public final class LivingToolFakePlayerCache {

    private static final Map<CacheKey, LivingToolFakePlayer> CACHE = new HashMap<>();

    private LivingToolFakePlayerCache() {
    }

    /**
     * 取（或创建）指定维度 + 主人的 FakePlayer。
     *
     * @param level 目标维度（决定 FakePlayer 的 {@code ServerLevel}）
     * @param owner 主人 UUID；null = 无主人（回退 UUID）
     */
    public static LivingToolFakePlayer get(ServerLevel level, @Nullable UUID owner) {
        CacheKey key = new CacheKey(level.dimension(), owner);
        return CACHE.computeIfAbsent(key, k -> new LivingToolFakePlayer(level, owner));
    }

    /** 清理某个维度的实例（维度卸载时）。 */
    public static void clearDimension(ResourceKey<Level> dimension) {
        Iterator<Map.Entry<CacheKey, LivingToolFakePlayer>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().dimension().equals(dimension)) {
                it.remove();
            }
        }
    }

    /** 全清（服务端停止时调用，避免跨存档泄漏）。 */
    public static void clear() {
        CACHE.clear();
    }

    public static int size() {
        return CACHE.size();
    }

    private record CacheKey(ResourceKey<Level> dimension, @Nullable UUID owner) {
    }
}
