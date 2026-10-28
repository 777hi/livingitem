package com.qiqi.li.living.domain.backpack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
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

    /**
     * 显示<b>容器 ID</b>（容器 UUID 的前 8 位）—— 让玩家能肉眼比对两个背包是不是同一份身份。
     *
     * <h3>为什么需要（2026-10-26，用户定）</h3>
     * <p>创造模式中键复制会连 {@code CONTAINER_UUID} 组件一起复制 ⇒ 两个背包共享同一份
     * 容器级数据与缓存键。这不是我们能拦的（原版复制路径不可枚举，精妙对它的背包
     * 也没防），但玩家需要一条<b>可自查</b>的线索：两个背包 ID 相同 ⇒ 它们是复制品
     * ⇒ 取消活化再重新活化即可拿到新身份。</p>
     *
     * <p>⚠️ 只显示身份，<b>不判断"这是不是复制品"</b> —— 那要遍历全服容器才知道，
     * 成本高且不可靠。给玩家可比的数字，比给一个可能错的结论好。</p>
     *
     * <p>普通提示只显示短码（不占宽度）；完整 UUID 与提示行在<b>高级提示</b>
     * （F3+H）里 —— 与项目其它高级提示同一条口径。</p>
     */
    @Override
    public void addToTooltip(net.minecraft.world.item.Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.backpack.status"));

        UUID uuid = ContainerUuids.ofItem(stack);
        if (uuid == null) {
            // 刚活化、还没 tick 过 ⇒ 身份尚未懒创建。不显示 ID 行，免得给一个空值。
            return;
        }
        tooltipAdder.accept(Component.translatable(
            "tooltip.livingitem.backpack.container_id", shortId(uuid)));
        if (flag.isAdvanced()) {
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.backpack.container_uuid", uuid.toString()));
            tooltipAdder.accept(Component.translatable(
                "tooltip.livingitem.backpack.uuid_hint"));
        }
    }

    /**
     * UUID 前 8 位十六进制 —— 够比对，又不占宽度。
     *
     * <p>⚠️ 用 {@code %016x} 而不是 {@code Long.toHexString}：后者丢前导零，
     * 会让 {@code substring(0, 8)} 越界（UUID 高位恰好很小的概率不低）。</p>
     */
    private static String shortId(UUID uuid) {
        return String.format("%016x", uuid.getMostSignificantBits()).substring(0, 8);
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext container, TickContext tick, Level level) {
        if (level == null || level.isClientSide) return;
        if (DEPTH.get() >= MAX_DEPTH) return;   // 闸一：嵌套上限

        for (SlotEntry entry : entries) {
            // ⚠️ try 必须包住**整个条目**，不能只包 processContext：
            // 联动模块的回调（find / contentHandler / columns）也可能抛，
            // 那些在旧实现里在 try 之外 ⇒ 一个坏来源会带走同批其它背包，
            // 再往上抛就是整个容器（乃至服务端 tick）停摆。2026-10-24 补测试时实测到。
            try {
                tickOne(entry, container, level);
            } catch (RuntimeException e) {
                // 一个背包坏了不该让其它背包和宿主容器跟着停（I-C7：留痕但不静默）
                ModLog.CONTAINER.error(
                    "[活背包] 处理失败，跳过该背包：" + entry.stack().getItem(), e);
            }
        }
    }

    /** 处理一个背包条目（异常由调用方按条目兜住）。 */
    private static void tickOne(SlotEntry entry, ContainerContext container, Level level) {
        ItemStack stack = entry.stack();
        ItemFormContainers.Source source = ItemFormContainers.find(stack);
        if (source == null) return;

        IItemHandler handler = source.contentHandler(stack);
        if (handler == null) {
            ModLog.CONTAINER.debug("[活背包] 拿不到内容视图，跳过：{}", stack.getItem());
            return;
        }

        UUID uuid = ContainerUuids.getOrCreateItem(stack);
        if (uuid == null) return;
        if (!CHAIN.get().add(uuid)) {
            ModLog.CONTAINER.warn("[活背包] 环检测命中，跳过：{}", uuid);
            return;   // 闸二：同一条链上重复出现
        }

        DEPTH.set(DEPTH.get() + 1);
        try {
            // 宿主锚点：「物品在箱子里，就在箱子那里」—— 传下去，但**用不用由功能决定**
            // （目前只有活 TNT 的爆炸点用它；工具/水车/信标若直接用会变成加强玩法）
            // hostSlot：本背包在宿主容器的第几格 —— 同一宿主放两个背包时，渲染上报靠它区分
            // （只按宿主做键会后写覆盖先写，见 ContainerContext#getHostSlot）。
            ItemFormContainerContext inner = new ItemFormContainerContext(
                handler, uuid, level, source.columns(stack), container.getAnchor(), entry.slotIndex());
            // 物品形态没有世界位置：按 UUID 登记本 tick 的活视图，供跨容器「按 UUID 寻址」
            // （活末影箱路由的提取端；见 ItemFormLiveViews 的边界说明）
            com.qiqi.li.living.container.ItemFormLiveViews.register(uuid, handler, level);
            ContainerLivingItemHandler.processContext(inner, level);
        } finally {
            DEPTH.set(DEPTH.get() - 1);
            CHAIN.get().remove(uuid);
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
