package com.qiqi.li.living.components;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

import com.qiqi.li.living.container.ContainerKeys;


/**
 * 容器 UUID 的<b>唯一读写入口</b>（2026-10-22，活背包 / `D-container-13`）。
 *
 * <h3>它是什么</h3>
 * <p>容器级数据的<b>跨形态身份</b>：方块形态存 BE 附件、物品形态存物品组件、数据本身在
 * SavedData 按这个 UUID 索引 ⇒ <b>形态变化时数据零搬运，只让 UUID 跟着走</b>。</p>
 *
 * <h3>懒创建（借精妙 {@code getOrCreateContentsUuid}）</h3>
 * <p>第一次真正需要持久化时才生成并写入 —— 不给世界上从未跨形态的空容器增加负担，
 * 老存档也不需要专门迁移命令。读取用 {@code of*}，写入用 {@code getOrCreate*}。</p>
 *
 * <h3>🔴 复制 remove 范式（借精妙 {@code cloneBackpack}）</h3>
 * <p>任何「复制容器物品」的路径（功能内复制 / 模板发放 / GUI 拖出的副本）<b>必须</b>先调
 * {@link #stripForCopy(ItemStack)} 再 copy —— 否则复制品带着同一个 UUID，
 * 两个物品共享一份容器级数据，<b>改一个另一个也变</b>。
 * 与 {@code ContainerHandoff} 搬运 UUID 时同样适用。</p>
 *
 * <p>⚠️ 精妙对「创造模式中键裸 copy」也没防（会共享），我们不背这个锅；
 * 但功能内复制必须防。</p>
 */
public final class ContainerUuids {

    private ContainerUuids() {}

    // ============================================================
    // 读（不创建）
    // ============================================================

    /** 物品上的容器 UUID；没有或空栈返回 {@code null}。 */
    @Nullable
    public static UUID ofItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return stack.get(LivingComponents.CONTAINER_UUID.get());
    }

    /** 宿主（BE / 实体）附件上的容器 UUID；没有返回 {@code null}。 */
    @Nullable
    public static UUID ofHost(@Nullable IAttachmentHolder host) {
        if (host == null) return null;
        return host.getData(LivingComponents.CONTAINER_UUID_ATTACHMENT.get());
    }

    // ============================================================
    // 懒创建
    // ============================================================

    /**
     * 物品侧：读 UUID，没有就生成并写进组件。
     *
     * @return 空栈返回 {@code null}（空栈不该有身份）
     */
    @Nullable
    public static UUID getOrCreateItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        UUID existing = stack.get(LivingComponents.CONTAINER_UUID.get());
        if (existing != null) return existing;

        UUID created = UUID.randomUUID();
        stack.set(LivingComponents.CONTAINER_UUID.get(), created);
        return created;
    }

    /**
     * 宿主侧：读 UUID，没有就生成并写进附件。
     *
     * <p>⚠️ 宿主为 {@code null} 返回 {@code null}（掉落物等无宿主容器不参与持久化）。</p>
     */
    @Nullable
    public static UUID getOrCreateHost(@Nullable IAttachmentHolder host) {
        if (host == null) return null;
        UUID existing = host.getData(LivingComponents.CONTAINER_UUID_ATTACHMENT.get());
        if (existing != null) return existing;

        UUID created = UUID.randomUUID();
        host.setData(LivingComponents.CONTAINER_UUID_ATTACHMENT.get(), created);
        return created;
    }

    // ============================================================
    // 复制范式
    // ============================================================

    /**
     * 🔴 复制容器物品**之前**必须先调它：剥掉 UUID，让复制品在下次懒创建时拿到新身份。
     *
     * <p>反例：直接 {@code stack.copy()} 会把 UUID 带过去 ⇒ 两个物品共享一份容器级数据。</p>
     */
    public static void stripForCopy(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            stack.remove(LivingComponents.CONTAINER_UUID.get());
        }
    }

    // ============================================================
    // 键
    // ============================================================

    /** 容器 UUID → 容器键（{@code cuuid_<uuid>}），与 {@link ContainerKeys#containerUuidOf} 互逆。 */
    public static String keyOf(UUID containerUuid) {
        return ContainerKeys.cuuidKey(containerUuid);
    }
}
