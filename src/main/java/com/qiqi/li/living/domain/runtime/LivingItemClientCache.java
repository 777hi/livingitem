package com.qiqi.li.living.domain.runtime;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端活物品运行时缓存 —— 由 {@link com.qiqi.li.network.LivingItemSyncPacket} 更新。
 *
 * <p>玩家同一时间只能打开一个容器，因此缓存只保存最近一次收到的快照。
 * 容器关闭时通过 {@link #clear()} 清理。</p>
 *
 * <p>本类不依赖 {@link net.minecraft.client.Minecraft}，可在服务端安全加载。
 * 获取当前悬停槽位的功能由客户端辅助类 {@code LivingItemTooltipBridge} 提供。</p>
 */
public class LivingItemClientCache {

    private static String currentContainerKey = "";
    private static Map<Integer, LivingItemRuntimeData> slotData = Map.of();

    /** 当前 tooltip 渲染的运行时数据（由 {@code LivingItemTooltip} 设置）。 */
    private static final ThreadLocal<LivingItemRuntimeData> currentTooltipData = new ThreadLocal<>();

    /**
     * 更新缓存（由网络包线程调用）。
     */
    public static void update(String containerKey, Map<Integer, LivingItemRuntimeData> data) {
        currentContainerKey = containerKey;
        slotData = new HashMap<>(data);
    }

    /**
     * 获取当前容器中指定槽位的运行时数据。
     */
    public static LivingItemRuntimeData get(int slot) {
        return slotData.getOrDefault(slot, LivingItemRuntimeData.EMPTY);
    }

    /**
     * 设置当前 tooltip 渲染的运行时数据（由 {@code LivingItemTooltip} 在调用
     * {@code function.addToTooltip()} 之前设置）。
     */
    public static void setCurrentTooltipData(LivingItemRuntimeData data) {
        currentTooltipData.set(data);
    }

    /**
     * 获取当前 tooltip 渲染的运行时数据。
     * <p>在 {@code function.addToTooltip()} 中调用，用于读取运行时数据替代组件数据。</p>
     */
    public static LivingItemRuntimeData getCurrentTooltipData() {
        LivingItemRuntimeData data = currentTooltipData.get();
        return data != null ? data : LivingItemRuntimeData.EMPTY;
    }

    /**
     * 清理当前 tooltip 渲染的运行时数据。
     */
    public static void clearCurrentTooltipData() {
        currentTooltipData.remove();
    }

    /**
     * 清理缓存（容器关闭时调用）。
     */
    public static void clear() {
        currentContainerKey = "";
        slotData = Map.of();
        clearCurrentTooltipData();
    }

    /**
     * 获取当前缓存的容器键。
     */
    public static String getCurrentContainerKey() {
        return currentContainerKey;
    }
}