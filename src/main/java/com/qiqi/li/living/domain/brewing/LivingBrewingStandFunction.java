package com.qiqi.li.living.domain.brewing;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.model.Pos2D;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * 活酿造台的功能实现。
 *
 * <p>把原版酿造台方块虚拟化进容器槽位：容器里放一个「活酿造台」物品，它就在槽位间自动酿造。
 * 5 个功能槽 = <b>3 个瓶子 + 燃料（烈焰粉）+ 材料</b>，默认布局见
 * {@link BrewingDirectionData#DEFAULT_BREWING}。</p>
 *
 * <h3>当前阶段：第一段（纯框架接入）</h3>
 * <p>本阶段<b>不做任何酿造逻辑</b> —— 只让物品成为「活物品」并具备 5 槽位方向配置能力
 * （含斜向布局），用全量回归证明没改坏别的东西。酿造流程（{@code PotionBrewing} 适配层 /
 * 批次状态机 / 次数制燃料 / 堆叠加速）在第二段实现。</p>
 *
 * <h3>已定案的关键设计（见 docs/buffer/living-brewing-stand-design.md §3）</h3>
 * <ul>
 *   <li><b>D1</b> 固定 5 个方向槽位，默认布局「瓶-瓶-瓶 / 燃料-台-材料」，方向可自定义</li>
 *   <li><b>D2</b> 燃料对齐原版（烈焰粉 = 20 次）且<b>落盘</b>（第二段）</li>
 *   <li><b>D3</b> 保留堆叠；堆叠<b>只加速酿造过程本身</b>，燃料与材料消耗不变</li>
 *   <li><b>D4</b> 中途换材料 = 整批作废（同原版）</li>
 *   <li><b>D5</b> 不做自定义图标（用原版图标 + 活物品标记）</li>
 *   <li><b>D6</b> 活漏斗投料 = 普通槽位模型（与活熔炉同构，零额外代码）</li>
 * </ul>
 */
public class LivingBrewingStandFunction implements LivingItemFunction, HasDirection {

    public static final String ID = "living_brewing_stand";

    /**
     * 槽位名，顺序 = 方向输入顺序：上排 3 瓶 → 燃料 → 材料。
     * （与原版酿造台的槽位编号 0/1/2=瓶、3=材料、4=燃料不同 —— 那是方块内部索引，与本层无关。）
     */
    public static final String[] SLOT_NAMES = {"bottle1", "bottle2", "bottle3", "fuel", "material"};

    public static final BrewingDirectionData DEFAULT_DIRECTION = BrewingDirectionData.DEFAULT_BREWING;

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.BREWING_STAND) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    /**
     * 【第一段：纯框架接入】<b>本阶段不做任何酿造逻辑</b>（空实现）。
     *
     * <p>第二段将在此实现：解析 5 个方向槽 → 用 {@code level.potionBrewing()} 查配方
     * （该入口已在启动时聚合原版 + 所有模组的酿造配方 ⇒ 零兼容代码）→ 推进 400 tick 批次
     * → 同时转化最多 3 瓶 → 消耗 1 次燃料与 1 份材料；瞬态进度写运行时缓存。</p>
     */
    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        // 第二段实现（批次状态机 + PotionBrewing 适配 + 次数制燃料）
    }

    /**
     * 悬浮提示：列出 5 个槽位当前方向。
     *
     * <p>第一段只显示方向（让玩家不必开容器就能核对配置）；第二段再补酿造状态
     * （进度 / 燃料次数 / 当前配方）。</p>
     */
    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder,
                             TooltipFlag flag, ItemStack stack) {
        BrewingDirectionData dir = LivingBrewingStandData.of(stack).direction();
        if (dir.directions().isEmpty()) {
            dir = DEFAULT_DIRECTION;
        }
        for (String slotName : SLOT_NAMES) {
            Pos2D d = dir.getDirection(slotName);
            if (d != null && !d.isNone()) {
                tooltipAdder.accept(Component.translatable(
                    "tooltip.livingitem.direction.slot",
                    Component.translatable("slot.livingitem." + slotName),
                    d.getSymbol()
                ).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    // ==================== HasDirection ====================

    @Override
    public int getDirectionKeyCount() {
        return SLOT_NAMES.length;
    }

    @Override
    public String[] getDirectionSlotNames() {
        return SLOT_NAMES;
    }

    /**
     * 活酿造台<b>需要斜向布局</b>（上排 3 瓶 = ↖ ↑ ↗）—— 这是九宫格对角键（Q/E/Z/C）
     * 在框架里「按能力放行」的唯一声明点（见 {@code HasDirection#supportsDiagonal}）。
     */
    @Override
    public boolean supportsDiagonal() {
        return true;
    }

    @Override
    public boolean updateSlotDirection(ItemStack stack, String slotName, Pos2D direction) {
        if (stack == null || stack.isEmpty() || slotName == null || direction == null) return false;
        LivingBrewingStandData data = LivingBrewingStandData.of(stack);
        BrewingDirectionData dir = data.direction();
        if (dir.directions().isEmpty()) {
            dir = DEFAULT_DIRECTION;   // 首次配置：以默认布局为底（其余槽位保持默认）
        }
        LivingBrewingStandData.set(stack, data.withDirection(dir.withDirection(slotName, direction)));
        return true;
    }

    @Override
    public Pos2D getSlotDirection(ItemStack stack, String slotName) {
        if (stack == null || stack.isEmpty() || slotName == null) return null;
        // 组件缺失时 of() 返回 DEFAULT ⇒ 未配置的活酿造台也会显示默认布局的方向
        return LivingBrewingStandData.of(stack).direction().getDirection(slotName);
    }

    @Override
    public Set<DataComponentType<?>> getOwnedComponentTypes() {
        return Set.of(BrewingStandComponents.LIVING_BREWING_STAND_DATA.value());
    }

    // 注：不覆盖 getIgnoredComponentTypes —— 方向是**真实数据**，参与堆叠比较；
    // 燃料次数（第二段加入）同理**不能**放进忽略清单，否则不同燃料值的物品合并后会静默丢数据。
}
