package com.qiqi.li.living.domain.sponge;

import java.util.List;
import java.util.Set;

import com.qiqi.li.living.api.FluidSourceLedger;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * 活海绵 —— 流体存储活物品（设计稿 docs/buffer/活海绵.md：容器水网的移动式储液罐）。
 *
 * <h3>实施分段（§5）</h3>
 * <ul>
 *   <li>第一段：组件 + 注册 + 组件归属声明；</li>
 *   <li>第二段（本段）：吸收（上下左右四邻直吸 + 逐源原子，§2.2，记账内核均分入堆）；</li>
 *   <li>第三段：物品流体能力（{@code SpongeItemFluidHandler}，一堆一罐）+ GUI 汲 +
 *       {@code ContainerFluidHandler} 退役执行。</li>
 * </ul>
 */
public class LivingSpongeFunction implements LivingItemFunction {

    /** 水节拍（原版 {@code getTickDelay} = 5t，引擎 §3.6 既有派生口径）——吸收心跳锁相周期。 */
    private static final long WATER_BEAT_TICKS = 5L;

    /** 一个派生源的定值（mmB）= 1000 mB（网络源永远整源，fluid tech §10 退役后口径不变）。 */
    private static final long SOURCE_MM_B = 1_000L * 1_000L;

    /** 吸收方向序（上 → 下 → 左 → 右，规则 4；空海绵遇混流体取首个可吸流体）。 */
    private static final int[] ABSORB_DIRS = {
        ContainerContext.E_UP, ContainerContext.E_DOWN, ContainerContext.E_LEFT, ContainerContext.E_RIGHT
    };

    @Override
    public boolean canApply(ItemStack stack) {
        if (!LivingItemManager.isLivingItem(stack)) return false;
        return stack.is(Items.SPONGE);
    }

    @Override
    public String getFunctionId() {
        return "living_sponge";
    }

    @Override
    public void addToTooltip(net.minecraft.world.item.Item.TooltipContext context,
                             java.util.function.Consumer<net.minecraft.network.chat.Component> tooltipAdder,
                             net.minecraft.world.item.TooltipFlag flag,
                             ItemStack stack) {
        // 反馈三问 ①（设计稿 §7）：{fluid, 堆总量} 是决策量（汲不汲 / 灌不灌）⇒ 服务端权威组件；
        // 干态与原版海绵静态同形（铜灯 unlit 同款代价）—— 干态不显示内容行
        LivingSpongeData d = LivingSpongeData.of(stack);
        if (!d.isWet()) return;
        net.minecraft.network.chat.Component name = net.minecraft.network.chat.Component.translatable(
            d.fluid().getDescriptionId());
        // 量与容量同取「整堆」口径：与能力面（getFluidInTank / getTankCapacity）及
        // 铜灯电量行一致，两者都随 count 线性放大 —— 写死每枚容量会让 count>1
        // 显示成「128000 / 64000 mB」（分子超分母）。
        int count = stack.getCount();
        tooltipAdder.accept(net.minecraft.network.chat.Component.nullToEmpty(""));
        tooltipAdder.accept(net.minecraft.network.chat.Component.translatable(
            "tooltip.livingitem.sponge.content",
            name.getString(),
            d.totalMmB(count) / 1000,
            LivingSpongeData.totalCapacityMmB(count) / 1000));
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext container, TickContext tick, Level level) {
        // 节拍门：世界轴锁相心跳（gameTime 跟存档走，跨存档天然安全；每 5t 恰好触发一次）
        if (level.getGameTime() % WATER_BEAT_TICKS != 0) return;

        // 契约层账本（FluidPresence 姊妹契约）：sponge 域不 import 水领域（R3 保持为零）
        FluidSourceLedger ledger = tick.getSourceLedger();
        if (ledger == null) return;

        int size = container.getSize();
        int width = container.getWidth();
        if (size <= 0 || width <= 0) return;

        for (SlotEntry entry : entries) {
            absorbAround(entry, ledger, size, width);
        }
    }

    /**
     * 四邻直吸 + 逐源原子（设计稿 §2.2）：按固定方向序逐格独立判定 —— 非派生源 / 异种邻居
     * 只跳过自己、不挡其他邻居（无「层」无「墙」）；空海绵按方向序取首个可吸流体（单 {@code fluid}
     * 组件的必然）；每个可吸源先过快满判据，装不下一个完整源即本拍停（每个源增量相同，
     * 后面必然同样装不下）；删源账本即时（规则 10），同拍后处理的海绵不再看见。
     */
    private void absorbAround(SlotEntry entry, FluidSourceLedger ledger, int size, int width) {
        ItemStack stack = entry.stack();
        LivingSpongeData data = LivingSpongeData.of(stack);
        // 快满判据的均分份额：floor(源量 / count)（规则 4/11；count ≤ 64 ⇒ 恒 > 0）
        long share = SOURCE_MM_B / stack.getCount();

        for (int dir : ABSORB_DIRS) {
            int neighbor = ContainerContext.resolveNeighbor(entry.slotIndex(), dir, size, width);
            if (neighbor < 0) continue;
            FluidType sourceFluid = ledger.sourceAt(neighbor);
            if (sourceFluid == null) continue;                        // 非派生源：该格跳过
            if (data.isWet() && data.fluid() != sourceFluid) continue; // 异种邻居：跳过不挡（规则 5）
            if (data.mmB() + share > LivingSpongeData.MAX_MM_B) break; // 装不下完整源 ⇒ 本拍停（⑪）
            ledger.removeSource(neighbor);                            // 账本即时删（规则 10）
            // ⚠️ 单次构造：干海绵首口定流体 + 存量入账必须同一步 —— 两步 wither 会让
            //    中间态 (fluid, 0) 被干/湿不变量归一回干态（首口蒸发，守恒事故）。
            data = new LivingSpongeData(data.isWet() ? data.fluid() : sourceFluid, data.mmB() + share);
        }
        // 一次落账：均分残余（SOURCE_MM_B mod count）按「宁损勿造」丢弃（内核纪律，§3）；
        // 无吸收时 set 等价于默认值移除 = no-op。
        LivingSpongeData.set(stack, data);
    }

    @Override
    public Set<DataComponentType<?>> getOwnedComponentTypes() {
        // ⚠️ 必须列全：漏列的组件在取消活化后变成孤儿数据（设计稿规则 9）。
        return Set.of(SpongeComponents.LIVING_SPONGE_DATA.value());
    }
}
