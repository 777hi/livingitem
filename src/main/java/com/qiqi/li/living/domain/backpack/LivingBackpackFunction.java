package com.qiqi.li.living.domain.backpack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

import com.qiqi.li.living.api.LivingItemActivation;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerLivingItemHandler;
import com.qiqi.li.living.container.ContainerUuids;
import com.qiqi.li.living.container.ItemFormContainerContext;
import com.qiqi.li.living.container.ItemFormContainers;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.logging.ModLog;

/**
 * <b>活背包</b> —— 让「本身是容器」的物品（精妙背包 / 潜影盒）内部的活物品也 tick
 * （2026-10-22）。
 *
 * <h3>它就是一个普通的活物品功能</h3>
 * <p>不做任何框架特殊化：{@link #canApply} 认领「物品形态容器」，
 * {@link #tick} 里对每个背包构建内部 ctx 再 {@link ContainerLivingItemHandler#processContext}
 * —— <b>完全复用现有 tick 管线</b>（与活潜影箱设计稿 §2.3 同款「功能内部递归」）。
 * 框架的 {@code scanAndGroupLivingItems} 会把它当活物品扫出来，不需要新扫描器、不需要 tag 判定。</p>
 *
 * <h3>活化门槛顺带解决三件事</h3>
 * <ul>
 *   <li>性能：没活化的背包不进 {@code grouped}，零开销</li>
 *   <li>判定：{@code isLivingItem} 已覆盖，不需要「是不是容器物品」的额外判据</li>
 *   <li>玩法：要玩家主动活化才动，不是所有背包物品都在跑</li>
 * </ul>
 *
 * <h3>嵌套与环</h3>
 * <p>活背包里再放活背包 ⇒ {@code processContext} 会再次调到本功能。两道闸：</p>
 * <ol>
 *   <li><b>深度上限</b> {@link #MAX_DEPTH}（现在 1）：先只做单层，
 *       等活潜影箱立项一起放开（设计稿 §6）。</li>
 *   <li><b>环检测</b>：同一条递归链上出现过的 UUID 不再进入
 *       （A 里有 B、B 里有 A 时靠它断开）。</li>
 * </ol>
 * <p>两者都是 {@link ThreadLocal} —— 递归在同一线程同一调用栈内。</p>
 */
public final class LivingBackpackFunction implements LivingItemFunction {

    /** 递归深度上限（含第 0 层）。先写死 1，等活潜影箱立项再放开。 */
    private static final int MAX_DEPTH = 1;

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Set<UUID>> CHAIN = ThreadLocal.withInitial(HashSet::new);

    @Override
    public boolean canApply(ItemStack stack) {
        return ItemFormContainers.isContainerItem(stack);
    }

    @Override
    public String getFunctionId() {
        return "living_backpack";
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext container, TickContext tick, Level level) {
        if (level == null || level.isClientSide) return;
        if (DEPTH.get() >= MAX_DEPTH) return;   // 闸一：嵌套上限

        for (SlotEntry entry : entries) {
            ItemStack stack = entry.stack();
            ItemFormContainers.Source source = ItemFormContainers.find(stack);
            if (source == null) continue;

            IItemHandler handler = source.contentHandler(stack);
            if (handler == null) {
                ModLog.CONTAINER.debug("[活背包] 拿不到内容视图，跳过：{}", stack.getItem());
                continue;
            }

            UUID uuid = ContainerUuids.getOrCreateItem(stack);
            if (uuid == null) continue;
            if (!CHAIN.get().add(uuid)) {
                ModLog.CONTAINER.warn("[活背包] 环检测命中，跳过：{}", uuid);
                continue;   // 闸二：同一条链上重复出现
            }

            DEPTH.set(DEPTH.get() + 1);
            try {
                ItemFormContainerContext inner = new ItemFormContainerContext(
                    handler, uuid, level, source.columns(stack));
                ContainerLivingItemHandler.processContext(inner, level);
            } catch (RuntimeException e) {
                // 一个背包炸了不该让其它背包和宿主容器跟着停（I-C7：留痕但不静默）
                ModLog.CONTAINER.error("[活背包] 内部 tick 失败，uuid=" + uuid, e);
            } finally {
                DEPTH.set(DEPTH.get() - 1);
                CHAIN.get().remove(uuid);
            }
        }
    }

    /**
     * 取消活化：<b>清理干净</b>（用户 2026-10-22 定调）。
     *
     * <p>{@link ContainerUuids#discard} 会先删 SavedData 那份容器级数据、再剥 UUID ——
     * 只剥 UUID 会把数据留成永远打不开的孤儿条目。</p>
     */
    @Override
    public boolean onDeactivated(ItemStack stack, Level level, @Nullable Player player,
                                 LivingItemActivation.Via via) {
        ContainerUuids.discard(stack);
        return true;
    }
}
