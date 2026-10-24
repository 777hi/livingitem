package com.qiqi.li.living.domain.tnt;
import com.qiqi.li.living.components.LivingComponents;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.tnt.ExplosionData;
import com.qiqi.li.living.domain.tnt.LivingTntData;

/**
 * 活 TNT 的功能实现。
 *
 * <p>每 tick 检测所在槽位的红石信号：收到信号即点燃并启动引信倒计时，归零时经
 * {@link ExplosionComponent#ignite} 触发爆炸；同时维持红石账本以放行红石层的驱动守卫（容器里只有活 TNT 时也是如此）。</p>
 */
public class LivingTntFunction implements LivingItemFunction {

    public static final String ID = "living_tnt";

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.TNT) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public boolean supportsDroppedItem() {
        // 爆炸点走 ContainerAnchor.AtEntity ⇒ 炸在原地（见 ExplosionComponent#ignite）
        return true;
    }

    @Override
    public String getFunctionId() { return ID; }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;

        // 确保红石账本存在 —— ⚠️ **这行不是冗余代码，别删**：
        // 红石层的驱动守卫（LivingRedstoneFunction.tickContainerData）以「账本已存在」为放行判据之一，
        // 容器里只有活 TNT 时正是靠这行让守卫放行。见
        // docs/buffer/redstone-driver-consolidation-plan.md §5 改动 2。
        // （2026-10-08 计划 ⑤：getSensor 内部即「取或创建账本」，故改走端口 ——
        //  这样 TNT 不必 import redstone 领域，中继由 TickContext 承担。）
        tick.getSensor(context);
        int size = context.getSize();

        for (SlotEntry entry : entries) {
            int slot = entry.slotIndex();
            if (slot < 0 || slot >= size) continue;

            ItemStack stack = entry.stack();
            LivingTntData data = LivingTntData.of(stack);
            ExplosionData explosion = data.explosion();

            if (!explosion.ignited()) {
                int signal = tick.getSensor(context).maxSensedSignal(slot);
                if (signal > 0) {
                    explosion = explosion.ignite();
                    LivingTntData.set(stack, data.withExplosion(explosion));
                    // 与引信递减走同一条「改了就写回」规则（见下方长注释）
                    context.setItem(slot, stack);
                    context.syncSlotToClients(slot, stack);
                }
                continue;
            }

            explosion = explosion.tick();

            if (explosion.isExploded()) {
                if (!ExplosionComponent.ignite(context, level)) {
                    // 无处可炸（容器没有世界锚点）⇒ **熄灭**，不是"停在 0"。
                    //
                    // 为什么必须熄灭：「引信归零 + 没有爆炸对象」这个状态没有任何出口 ——
                    // 留着它就是一颗**只等容器获得位置的定时炸弹**（2026-10-24 实测：
                    // 背包放成方块 ⇒ 下一 tick 瞬爆，玩家早已忘了这回事）。
                    //
                    // ⚠️ **熄灭后会被自动重新点燃吗？会** —— 容器的**内部红石**（背包里放个活拉杆）
                    // 与位置无关：`sensedSignal` 读的是内部 `edgeGrid`，不是外部方块
                    // （外部才走 injectExternalInputs，那部分才需要位置）。
                    // ⇒ 所以"无锚点 ⇒ 收不到信号"是**错的**，别拿它当理由。
                    //
                    // 当前之所以安全：**无锚点在 tick 路径上不可达** —— 物品形态的宿主锚点由
                    // LivingBackpackFunction 传下来（宿主是方块/玩家/船，恒非空）。
                    // （GUI 反查路径确实传 null，但那条路只服务 tooltip / 倒汲 / 槽位设置，不 tick。）
                    // ⚠️ 若将来允许关掉宿主锚点（配置化），**必须在点燃分支先判锚点**，
                    // 否则这里会退化成「80 → 0 → 熄灭 → 立刻重新点燃 → 80」的无限循环。
                    LivingTntData.set(stack, LivingTntData.DEFAULT);
                    context.setItem(slot, stack);
                    context.syncSlotToClients(slot, stack);
                    warnCannotExplodeOnce(context);
                    continue;
                }
                // ⚠️ 消耗由 ignite 完成 —— 它把容器里**所有**活 TNT 槽位写成 EMPTY。
                // 所以这里**必须直接结束**，绝不能往下走到写回：
                //   · 写回 `stack` 会把刚清掉的槽位用本地栈填回去 ⇒ 「炸了但 TNT 还在」
                //     （2026-10-23 真机实录）；
                //   · 也不能只 continue 到下一个条目 —— `entries` 是 tick 前的快照，
                //     其余条目的槽位同样已被 ignite 清空，写回会把它们一起复活。
                return;
            }

            LivingTntData.set(stack, data.withExplosion(explosion));

            // ⚠️ 必须**显式写回**，不能只靠「getItem 返回的栈是活引用」：
            // NBT 背书的容器（精妙背包的 handler）里就地改组件不会触发它自己的保存钩子，
            // 而本函数的修改（引信递减）是唯一的数据来源。工具类早已这么做（writeBack），
            // TNT 此前漏了（2026-10-23 普查）。
            context.setItem(slot, stack);
            context.syncSlotToClients(slot, stack);
        }
    }

    /** 「炸不了」只记一次（按容器键）—— 满足 I-C6（降级必须可观测），又不至于每 tick 刷屏。 */
    private static final Set<String> WARNED_CANNOT_EXPLODE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void warnCannotExplodeOnce(ContainerContext context) {
        String key = context.getCacheKey() != null ? context.getCacheKey() : context.getContainerKey();
        if (key == null || !WARNED_CANNOT_EXPLODE.add(key)) return;
        com.qiqi.li.logging.ModLog.CONTAINER.warn(
            "[活TNT] 引信已归零但无法引爆（容器没有世界锚点）⇒ 已熄灭。容器键={}，容器类={}",
            key, context.getClass().getSimpleName());
    }

    @Override
    public void addToTooltip(Item.TooltipContext context,
                             Consumer<Component> tooltipAdder,
                             TooltipFlag flag,
                             ItemStack stack) {
        LivingTntData data = LivingTntData.of(stack);
        ExplosionData explosion = data.explosion();

        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.tnt.status"));

        if (explosion.ignited()) {
            tooltipAdder.accept(Component.literal("TNT [Fuse: " + explosion.fuseTimer() + " ticks]")
                .withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD));
        } else {
            tooltipAdder.accept(Component.literal("TNT")
                .withStyle(net.minecraft.ChatFormatting.RED));
        }
    }

    @Override
    public Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of(TntComponents.LIVING_TNT_DATA.value());
    }

    @Override
    public Set<DataComponentType<?>> getIgnoredComponentTypes() {
        return Set.of();
    }

    public static boolean isFuseActive(ItemStack stack) {
        return LivingTntData.of(stack).explosion().ignited();
    }

    public static int getFuseTimer(ItemStack stack) {
        ExplosionData e = LivingTntData.of(stack).explosion();
        return e.ignited() ? e.fuseTimer() : -1;
    }

    public static boolean startFuse(ItemStack tntStack) {
        LivingTntData data = LivingTntData.of(tntStack);
        if (data.explosion().ignited()) return false;
        LivingTntData.set(tntStack, data.withExplosion(data.explosion().ignite(80)));
        return true;
    }

}