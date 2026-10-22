package com.qiqi.li.living.container;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 物品形态容器的上下文（2026-10-22，活背包）—— 把一件「本身是容器」的物品
 * 包装成可 tick 的 {@link ContainerContext}。
 *
 * <h3>与其它 ctx 的三处不同</h3>
 * <table>
 *   <caption>差异点</caption>
 *   <tr><th></th><th>方块 / 实体容器</th><th>物品形态容器</th></tr>
 *   <tr><td>身份</td><td>位置 / 实体 UUID</td><td><b>容器 UUID</b>（{@code cuuid_<uuid>}）</td></tr>
 *   <tr><td>数据宿主</td><td>BE / 实体附件</td><td><b>无</b>（物品栈不是 {@code IAttachmentHolder}）</td></tr>
 *   <tr><td>持久层</td><td>附件</td><td><b>SavedData</b>（覆写 {@link #persist}）</td></tr>
 * </table>
 *
 * <p>⇒ 这正是 `D-container-13` 说的「拿不到 {@code IAttachmentHolder} 不是异常，
 * 是一种正常宿主」的第一个实现。</p>
 */
public final class ItemFormContainerContext extends SimpleContainerContext {

    private final UUID containerUuid;

    /**
     * @param handler     内容的活视图（由 {@link ItemFormContainers.Source#contentHandler} 提供）
     * @param containerUuid 容器身份（懒创建自物品组件）
     * @param level       世界（tick 用）
     */
    public ItemFormContainerContext(IItemHandler handler, UUID containerUuid, Level level) {
        // 显式键：cuuid_<uuid>，与 ContainerKeys.containerUuidOf 互逆
        super(handler, null, java.util.List.of(), java.util.List.of(), level,
            ContainerKeys.cuuidKey(containerUuid));
        this.containerUuid = containerUuid;
    }

    @Override
    public UUID getContainerUuid() {
        return containerUuid;
    }

    /**
     * 持久层是 SavedData（`D-container-13` 的覆写点）。
     *
     * <p>⚠️ 不覆写会落到 default 的「遍历 hosts 写附件」——而 hosts 恒为空 ⇒
     * <b>数据静默不落盘</b>（LRU 回收即丢）。这是本类存在的主要理由。</p>
     */
    @Override
    public <T> void persist(ContainerDataKey<T> key, T value) {
        if (!key.isSaved()) return;
        ContainerDataSavedData data = ContainerDataSavedData.get();
        if (value == null || isEmptyValue(value)) {
            data.remove(containerUuid, key);
        } else {
            data.put(containerUuid, key, value);
        }
    }

    /**
     * 判断「空值」—— 各领域的 EMPTY 语义不同（流体有 {@code isEmpty()}，相位是 record）。
     *
     * <p>⚠️ 用反射猜语义太脆；这里只处理已知的「有 {@code isEmpty()} 方法」的类型，
     * 其余一律当作非空写入（宁可多写一条，也不要误删数据）。</p>
     */
    private static boolean isEmptyValue(Object value) {
        try {
            var m = value.getClass().getMethod("isEmpty");
            if (m.getReturnType() == boolean.class) {
                return (boolean) m.invoke(value);
            }
        } catch (ReflectiveOperationException ignored) {
            // 没有 isEmpty ⇒ 当作非空
        }
        return false;
    }

    @Override
    public String toString() {
        return "ItemFormContainerContext(" + ContainerKeys.cuuidKey(containerUuid) + ")";
    }

    /** 无宿主是<b>正常</b>的（物品栈不是附件持有者）—— 与 {@code D-container-13} 一致。 */
    @Override
    public java.util.List<net.neoforged.neoforge.attachment.IAttachmentHolder> getDataHosts() {
        return java.util.List.of();
    }

    /** 物品形态没有世界位置。 */
    @Nullable
    @Override
    public net.minecraft.core.BlockPos getBlockPos() {
        return null;
    }
}
