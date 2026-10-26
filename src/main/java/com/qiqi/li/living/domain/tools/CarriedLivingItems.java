package com.qiqi.li.living.domain.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.qiqi.li.living.container.ContainerUuids;
import com.qiqi.li.living.container.HostRef;
import com.qiqi.li.living.container.ItemFormContainers;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 「玩家<b>随身携带</b>的活物品」的唯一判据（2026-10-27）。
 *
 * <h3>它回答什么</h3>
 * <p>被动模式的辅助（挖掘 / 攻击）、远程可见性、背后环，问的都是同一个问题：
 * <b>「这个人身上有哪些活物品」</b>。</p>
 *
 * <h3>为什么需要它</h3>
 * <p>原来三条线各自写成 {@code for (slot : player.getInventory())} —— 那等于把
 * 「随身携带」定义成「物品栏顶层」。**物品形态容器（活背包）打破了这个定义**：
 * 工具在背包里、背包在物品栏里，而工具不在顶层 ⇒ 三条线一起失效。</p>
 *
 * <p>⇒ 这里把定义写成一条：<b>物品栏顶层 + 已活化容器物品的内容（只展开一层）</b>。</p>
 *
 * <h3>为什么返回 {@link Carried} 而不是 {@code ItemStack}</h3>
 * <p>写回需要<b>位置</b>：工具扣耐久是就地改栈（{@code hurtAndBreak}），而容器内容拿到的
 * 可能是副本 ⇒ 不写回就等于「白干活不掉耐久」。位置同时能区分「物品栏第几格」与
 * 「容器内容第几格」，写回时各走各的通道（{@code setItem} / {@code setStackInSlot}）。</p>
 *
 * <h3>两个边界（都是刻意的）</h3>
 * <ul>
 *   <li><b>只展开已活化的容器物品</b>（有 {@code CONTAINER_UUID}）：未活化的背包不进 tick
 *       管线、零开销 —— 展开它等于替玩家把没启用的东西算进战斗力。</li>
 *   <li><b>只展开一层</b>：与 {@code LivingBackpackFunction.MAX_DEPTH = 1} 一致。
 *       背包里的背包不参与，等活潜影箱放开深度时再一起改。</li>
 * </ul>
 *
 * <p>⚠️ 每次调用都会遍历物品栏 + 展开每个已活化容器 ⇒ 调用点应保持在「每 tick 每玩家一次」量级。</p>
 */
public final class CarriedLivingItems {

    /**
     * 一件随身携带的活物品 + 它在哪。
     *
     * @param outerSlot     物品栏槽位（背包 / 工具所在的那一格）；{@code -1} = 客户端从 S2C 补的
     * @param innerSlot     容器内容槽位；{@code -1} = 不在容器里
     * @param fromContainer <b>它是不是住在容器里</b> —— 显式字段，<b>不能从槽位推断</b>。
     *                      <p>⚠️ 客户端从 S2C 补的条目拿不到位置（两个槽位都是 -1），但它在语义上
     *                      仍然是「背包里的工具」⇒ 必须参与「背包只取最高」那条分层。
     *                      曾经用 {@code innerSlot >= 0} 推断 ⇒ 客户端补的条目被判成"顶层"、
     *                      参与相加 ⇒ 客户端比服务端快一倍 ⇒ 服务端拒绝破坏、方块破坏又恢复
     *                      （2026-10-27 真机，日志：客户端 bonus=16 / 服务端 bonus=8）。</p>
     */
    public record Carried(ItemStack stack, int outerSlot, int innerSlot, boolean fromContainer) {

        /** 住在容器里（活背包的内容）—— 参与「背包只取最高」那条分层。 */
        public boolean inContainer() {
            return fromContainer;
        }

        /** 能否写回：客户端从 S2C 补的条目没有位置（{@code outerSlot = -1}）。 */
        public boolean canWriteBack() {
            return outerSlot >= 0;
        }
    }

    private CarriedLivingItems() {
    }

    /**
     * @param accept       过滤（调用方自己组合「是不是活工具 / 活武器」等条件）
     * @param skipMainHand 跳过主手那一格（原版已把主手计入，再算一次会翻倍）
     */
    public static List<Carried> of(Player player, Predicate<ItemStack> accept, boolean skipMainHand) {
        Inventory inventory = player.getInventory();
        List<Carried> out = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (skipMainHand && slot == inventory.selected) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (accept.test(stack)) {
                out.add(new Carried(stack, slot, -1, false));
            }
            if (!(player instanceof ServerPlayer)) {
                // ⚠️ 客户端读不到容器内容（服务端 SavedData，`container-overview` §0.1 推论 1）
                //    ⇒ 不本地展开，改由 K2 的 S2C 补（见下方）。本地展开会拿到"时有时无"的
                //    缓存副本，导致两端速度不一致 ⇒ 挖掘进度条卡住（2026-10-27 真机）。
                continue;
            }
            collectFromContainer(stack, slot, accept, out);
        }
        if (!(player instanceof ServerPlayer)) {
            collectFromHostCache(player, accept, out);
        }
        return out;
    }

    /**
     * 客户端补充：从 K2 的 S2C 里取「宿主是这个玩家」的条目。
     *
     * <p>服务端已经把这些工具（含完整 ItemStack / 附魔）发过来了，客户端直接用于算速度 ——
     * 这样<b>两端看到的是同一份工具集合</b>，不会出现「客户端不加成、服务端加成」的错位。</p>
     *
     * <p>⚠️ 这些条目<b>不可写回</b>（{@code outerSlot = -1}）：客户端读不到容器 handler，
     * 而扣耐久本来也只在服务端发生。</p>
     */
    private static void collectFromHostCache(Player player, Predicate<ItemStack> accept,
                                             List<Carried> out) {
        ResourceLocation dimension = player.level().dimension().location();
        for (LivingToolHostPacket.Entry entry : LivingToolHostClientCache.get(dimension)) {
            if (entry.host().kind() != HostRef.Kind.PLAYER) {
                continue;
            }
            if (!player.getUUID().equals(entry.host().entityId())) {
                continue;
            }
            for (LivingToolHostPacket.ToolRay ray : entry.tools()) {
                if (accept.test(ray.stack())) {
                    out.add(new Carried(ray.stack(), -1, -1, true));
                }
            }
        }
    }

    /**
     * 把改过的栈写回它原来的位置。
     *
     * <p>⚠️ 容器内容走 {@link IItemHandlerModifiable#setStackInSlot}（`I-C3`：写回 ≠ 插入）；
     * handler 不支持精确写回时<b>放弃</b>并留痕 —— 不退回 {@code insertItem}，
     * 那会把物品塞到别的格子里。</p>
     */
    public static void writeBack(Player player, Carried carried, ItemStack updated) {
        if (!carried.canWriteBack()) {
            return;   // 客户端从 S2C 补的条目：位置未知，且扣耐久只在服务端发生
        }
        if (!carried.inContainer()) {
            player.getInventory().setItem(carried.outerSlot(), updated);
            return;
        }
        ItemStack container = player.getInventory().getItem(carried.outerSlot());
        ItemFormContainers.Source source = ItemFormContainers.find(container);
        if (source == null) {
            return;
        }
        IItemHandler handler = source.contentHandler(container);
        if (handler instanceof IItemHandlerModifiable modifiable) {
            modifiable.setStackInSlot(carried.innerSlot(), updated);
        }
    }

    /** 物品形态容器（活背包）里的一层内容；不是已活化的容器就什么都不做。 */
    private static void collectFromContainer(ItemStack stack, int outerSlot,
                                             Predicate<ItemStack> accept, List<Carried> out) {
        if (!ItemFormContainers.isContainerItem(stack)) {
            return;
        }
        if (ContainerUuids.ofItem(stack) == null) {
            return;   // 未活化 ⇒ 不是活容器（活化门槛即性能闸门）
        }
        IItemHandler handler = contentHandlerOf(stack);
        if (handler == null) {
            return;
        }
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack inner = handler.getStackInSlot(i);
            if (!inner.isEmpty() && accept.test(inner)) {
                out.add(new Carried(inner, outerSlot, i, true));
            }
        }
    }

    @Nullable
    private static IItemHandler contentHandlerOf(ItemStack containerStack) {
        ItemFormContainers.Source source = ItemFormContainers.find(containerStack);
        return source == null ? null : source.contentHandler(containerStack);
    }
}
