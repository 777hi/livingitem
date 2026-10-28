package com.qiqi.li.living.container;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

import com.qiqi.li.living.components.LivingComponents;

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
 *
 * <h3>复制品的后果与玩家的自查手段（2026-10-26）</h3>
 * <p>中键复制 ⇒ 两个物品共享同一个 UUID ⇒ <b>容器级数据也共享</b>（改一个另一个也变）。
 * 这与精妙「复制品共享背包内容」是自洽的，所以<b>不额外处理</b> —— 拦截所有复制路径
 * 不可行（中键 / {@code /give} / NBT 粘贴 / 第三方管道），半吊子防护比不防更难查。</p>
 *
 * <p>唯一真实的副作用：<b>取消活化其中一个 ⇒ {@link #discard} 删掉共享的那份数据</b>
 * ⇒ 另一个也丢。玩家自己能修：<b>取消活化再重新活化</b>即拿到新身份。</p>
 *
 * <p>为了让玩家看得出「两个是不是同一份」，活背包的 tooltip 显示容器 ID 短码
 * （{@code LivingBackpackFunction#addToTooltip}）—— 两个背包 ID 相同就是复制品。
 * ⚠️ 只给可比的数字，<b>不替玩家判定「这是复制品」</b>：那要遍历全服容器，成本高且不可靠。</p>
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
        UUID uuid = host.getData(LivingComponents.CONTAINER_UUID_ATTACHMENT.get());
        // 哨兵（全零）⇒ 没有身份。附件默认值不能是 null（见 CONTAINER_UUID_ATTACHMENT 的 javadoc）
        return isAbsent(uuid) ? null : uuid;
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
        UUID existing = ofHost(host);
        if (existing != null) return existing;

        UUID created = UUID.randomUUID();
        host.setData(LivingComponents.CONTAINER_UUID_ATTACHMENT.get(), created);
        return created;
    }

    /** 「没有身份」的判据：null 或附件哨兵（全零 UUID）。 */
    private static boolean isAbsent(@Nullable UUID uuid) {
        return uuid == null || LivingComponents.NO_CONTAINER_UUID.equals(uuid);
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

    /**
     * <b>取消活化时的彻底清理</b>：先删 SavedData 里那份容器级数据，再剥 UUID。
     *
     * <p>⚠️ 顺序不能反、也不能只做一半：只剥 UUID 会把数据留成**孤儿条目**
     * （数据还在硬盘上，但没有任何物品指向它 ⇒ 永远打不开、还占存档）；
     * 只删数据不剥 UUID 则会让物品指向一个空身份（重新活化时被复用，看起来"数据凭空消失"）。</p>
     *
     * <p>语义依据（用户 2026-10-22 定调）：<b>取消活化就是要清理干净</b> ——
     * 活流体的容器级数据也一并清，与活熔炉清熔炼进度同构。</p>
     *
     * <p><b>调用点</b>：由拥有容器级数据的 {@code LivingItemFunction#onDeactivated} 调用
     * （活背包 / 将来的活潜影箱），<b>不放进 {@code clearLivingData}</b> ——
     * 后者在 {@code api}（L0 契约层），依赖本类会给 L0 → L2 再加一条违规。
     * 这条分工是安全的：UUID 只由 {@link #getOrCreateItem} 生成，而它只被活物品功能调用
     * ⇒ <b>有 UUID 的物品必然有对应的 Function，{@code onDeactivated} 一定会跑</b>。</p>
     */
    public static void discard(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        UUID uuid = ofItem(stack);
        if (uuid != null) {
            ContainerDataSavedData.get().remove(uuid);
            // ⚠️ 只删 SavedData 不够：内存层（容器级数据 + 槽位追踪 + 运行时缓存）按缓存键另存一份，
            //    而物品形态没有位置可供 removeDataByPos 反查 ⇒ 必须按键作废（2026-10-28 修）。
            ContainerLivingItemHandler.removeDataByKey(keyOf(uuid));
        }
        stripForCopy(stack);
    }

    // ============================================================
    // 键
    // ============================================================

    /** 容器 UUID → 容器键（{@code cuuid_<uuid>}），与 {@link ContainerKeys#containerUuidOf} 互逆。 */
    public static String keyOf(UUID containerUuid) {
        return ContainerKeys.cuuidKey(containerUuid);
    }
}
