package com.qiqi.li.living.domain.brewing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerContexts;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.container.TickableContainerContext;
import com.qiqi.li.living.domain.brewing.BrewingStandSegment.BrewingRuntime;
import com.qiqi.li.living.model.DirectionSymbols;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.runtime.ContainerRuntimeCache;
import com.qiqi.li.living.runtime.LivingItemClientCache;
import com.qiqi.li.living.runtime.RuntimeSegments;
import com.qiqi.li.living.transfer.SlotResolver;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 活酿造台的功能实现。
 *
 * <p>把原版酿造台方块虚拟化进容器槽位：容器里放一个「活酿造台」物品，它就在槽位间自动酿造。
 * 5 个功能槽 = <b>3 个瓶子 + 燃料（烈焰粉）+ 材料</b>，默认布局见
 * {@link BrewingDirectionData#DEFAULT_BREWING}。</p>
 *
 * <h3>职责划分（第二段）</h3>
 * <ul>
 *   <li><b>纯逻辑</b>（这一 tick 该发生什么）→ {@link BrewingBatchLogic}（可单测）；</li>
 *   <li><b>本类</b>（世界侧）→ 解析 5 个方向槽、读实时槽内容、把 {@link BrewingBatchLogic.Step}
 *       落到槽位与组件上。</li>
 * </ul>
 *
 * <h3>数据归属（设计稿 §4.2）</h3>
 * <ul>
 *   <li><b>燃料次数</b> → {@code DataComponent}（持久 + 同步，D2）；</li>
 *   <li><b>倒计时 / 本批次材料</b> → 运行时缓存（瞬态 ⇒ 不影响堆叠，{@link BrewingStandSegment}）；</li>
 *   <li><b>批次起始时刻</b>（{@code brewStartTick}）→ {@code DataComponent}（2026-10-12 反馈层：
 *       唯一落盘的进度镜像，客户端据此画原版同款白幕；只在批次开始/中止/完成时写）。</li>
 * </ul>
 *
 * <h3>已定案的关键设计（设计稿 §3）</h3>
 * <ul>
 *   <li><b>D1</b> 固定 5 个方向槽位，默认布局「瓶-瓶-瓶 / 燃料-台-材料」，方向可自定义</li>
 *   <li><b>D2</b> 燃料对齐原版（烈焰粉 = 20 次）且落盘</li>
 *   <li><b>D3 / D11</b> 堆叠只加速过程（{@code step = count}），<b>每批仍只扣 1 燃料 + 1 材料</b></li>
 *   <li><b>D4 / D8</b> 作废 = 不可酿造 / 材料的 {@code Item} 变了（<b>瓶子不参与</b>）</li>
 *   <li><b>D5</b> 不做自定义图标（原版图标 + 活物品标记）</li>
 *   <li><b>D6</b> 活漏斗投料 = 普通槽位模型（与活熔炉同构，零额外代码）</li>
 *   <li><b>D7</b> 燃料在<b>启动</b>扣 1 次、材料在<b>完成</b>扣 1 份</li>
 *   <li><b>D9</b> 材料的合成残留物<b>放回材料槽</b>（照活熔炉先例，不掉落）</li>
 *   <li><b>D10</b> 搬运丢进度、留燃料</li>
 * </ul>
 */
public class LivingBrewingStandFunction implements LivingItemFunction, HasDirection {

    public static final String ID = "living_brewing_stand";

    /**
     * 槽位名，顺序 = 方向输入顺序：上排 3 瓶 → 燃料 → 材料。
     * （与原版酿造台的槽位编号 0/1/2=瓶、3=材料、4=燃料不同 —— 那是方块内部索引，与本层无关。）
     */
    public static final String[] SLOT_NAMES = {"bottle1", "bottle2", "bottle3", "fuel", "material"};

    /** 3 个瓶子槽的名字（上排从左到右）。 */
    private static final String[] BOTTLE_NAMES = {"bottle1", "bottle2", "bottle3"};

    public static final BrewingDirectionData DEFAULT_DIRECTION = BrewingDirectionData.DEFAULT_BREWING;

    @Override
    public boolean canApply(ItemStack stack) {
        return stack.is(Items.BREWING_STAND) && LivingItemManager.isLivingItem(stack);
    }

    @Override
    public String getFunctionId() {
        return ID;
    }

    @Override
    public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
        if (level.isClientSide) return;
        PotionBrewing brewing = BrewingRecipes.of(level);

        for (SlotEntry entry : entries) {
            int slot = entry.slotIndex();
            if (!context.isValidSlot(slot)) continue;
            ItemStack stack = entry.stack();

            LivingBrewingStandData data = LivingBrewingStandData.of(stack);
            boolean dirty = false;
            BrewingDirectionData dir = data.direction();
            if (dir.directions().isEmpty()) {
                dir = DEFAULT_DIRECTION;          // 首次 tick：落盘默认布局（与活熔炉同款）
                data = data.withDirection(dir);
                dirty = true;
            }

            // ── 解析 5 个方向槽 ──
            int size = context.getSize();
            int width = context.getWidth();
            int[] bottleSlots = new int[3];
            for (int i = 0; i < 3; i++) {
                bottleSlots[i] = SlotResolver.resolve(slot, dir.getDirection(BOTTLE_NAMES[i]), size, width);
            }
            int fuelSlot = SlotResolver.resolve(slot, dir.getDirection("fuel"), size, width);
            int ingredientSlot = SlotResolver.resolve(slot, dir.getDirection("material"), size, width);

            // ── 读运行时（瞬态）──
            // 缓存键（世界容器带维度前缀，见 ContainerIdentity.getCacheKey）——
            // 传裸 getContainerKey() 会让主世界/下界同坐标的两个箱子串酿造进度（2026-10-12 修）
            String cacheKey = context.getCacheKey();
            BrewingRuntime runtime = ContainerRuntimeCache.get(cacheKey, slot)
                .get(BrewingStandSegment.INSTANCE);
            if (runtime == null) runtime = BrewingRuntime.EMPTY;

            // ── ① 补燃料（对齐原版：fuel <= 0 且燃料槽是烈焰粉 ⇒ 立刻补满 20、吃掉 1 个）──
            int fuel = data.fuel();
            if (fuel <= 0 && fuelSlot >= 0 && isBlazePowder(context.getItem(fuelSlot))) {
                consumeOne(context, fuelSlot);
                fuel = BrewingBatchLogic.FUEL_USES;
                dirty = true;
            }

            // ── ② 实时判定「可酿造」（材料有效 + 至少 1 瓶能配）──
            ItemStack ingredient = ingredientSlot >= 0 ? context.getItem(ingredientSlot) : ItemStack.EMPTY;
            boolean canBrew = isBrewable(brewing, context, bottleSlots, ingredient);

            // ── ③ 推进状态机 ──
            Item currentIngredient = ingredient.isEmpty() ? null : ingredient.getItem();
            int step = BrewingBatchLogic.stepFor(stack.getCount());
            BrewingBatchLogic.Step result = BrewingBatchLogic.advance(
                runtime.brewTime(), runtime.batchIngredient(), canBrew, currentIngredient, fuel, step);

            int nextBrewTime = runtime.brewTime();
            Optional<Item> nextBatchIngredient = runtime.batchIngredient();
            // 白幕用的「批次起始时刻」镜像（唯一落盘的进度数据；-1 = 不在酿造）。
            // 只在批次生命周期事件写 ⇒ 稳态零写入（与熔炉燃烧标志同量级）。
            long brewStart = data.brewStartTick();
            switch (result) {
                case BrewingBatchLogic.Step.Start start -> {
                    fuel--;                                              // D7：启动扣 1 次
                    nextBrewTime = start.brewTime();
                    nextBatchIngredient = Optional.of(start.ingredient());
                    // Start 携带满 400 ⇒ 这一 tick 就是批次的第 0 刻，直接取当前 gameTime
                    brewStart = level.getGameTime();
                    dirty = true;
                }
                case BrewingBatchLogic.Step.Complete ignored -> {
                    doBrew(brewing, context, bottleSlots, ingredientSlot); // D7：完成扣 1 份材料
                    nextBrewTime = 0;
                    nextBatchIngredient = Optional.empty();
                    brewStart = -1L;                                      // 白幕收起（下一批若紧接着开始会重写）
                    dirty = true;
                    playBrewSound(context, level);                        // 完成音效（对齐原版 levelEvent 1035）
                }
                case BrewingBatchLogic.Step.Abort ignored -> {
                    nextBrewTime = 0;                                     // D8：作废（燃料不退、材料不扣）
                    nextBatchIngredient = Optional.empty();
                    brewStart = -1L;                                      // 批次作废 ⇒ 白幕立刻收起
                    dirty = true;
                }
                case BrewingBatchLogic.Step.Continue cont -> nextBrewTime = cont.brewTime();
                case BrewingBatchLogic.Step.Idle ignored -> { /* 无事可做 */ }
            }

            // ── ④ 写运行时（瞬态，不进组件）──
            ContainerRuntimeCache.update(cacheKey, slot, RuntimeSegments.EMPTY.with(
                BrewingStandSegment.INSTANCE, new BrewingRuntime(nextBrewTime, nextBatchIngredient)));

            // ── ⑤ 仅在持久数据变化时写组件（燃料 / 方向 / 批次起始时刻）──
            if (dirty) {
                LivingBrewingStandData.set(stack, data.withFuel(fuel).withBrewStart(brewStart));
                context.syncSlotToClients(slot, stack);
            }
        }
    }

    // ==================== 完成音效（项目第一个活物品音效，2026-10-12） ====================

    /**
     * 批次完成时给「正在照看这台机器的人」播原版酿造完成音（{@code block.brewing_stand.brew}，
     * 对齐原版 {@code BrewingStandBlockEntity.doBrew} 的 {@code levelEvent 1035}）。
     *
     * <p><b>为什么只发查看者、不做半径广播</b>：原版玩家站在方块旁 ⇒ 等价于「开着这个容器的 GUI」；
     * 而活酿造台可能在玩家的背包 / 远处箱子里，按位置广播（16 格）要么听不到（在背包里人走远了）、
     * 要么扰民（不该由远处的路人承担）。⇒ 判定 = {@link ContainerContexts#isViewing}；
     * 背包 / 末影箱容器（无 BE）直接发主人本人 —— 自己的酿造完成自己听见，不管开没开 GUI。</p>
     *
     * <p>位置取容器方块中心；背包容器取玩家自身位置。客户端按距离衰减（{@code ClientboundSoundPacket}
     * 是位置音）⇒ 离箱子远就轻，符合直觉。</p>
     */
    private static void playBrewSound(ContainerContext ctx, Level level) {
        if (level.isClientSide) return;
        List<ServerPlayer> targets = brewSoundTargets(ctx, level);
        if (targets.isEmpty()) return;

        BlockPos pos = ctx.getBlockPos();
        for (ServerPlayer viewer : targets) {
            double x, y, z;
            if (pos != null) {
                x = pos.getX() + 0.5; y = pos.getY() + 0.5; z = pos.getZ() + 0.5;
            } else {
                Vec3 p = viewer.position();                              // 背包容器：音就在人身上
                x = p.x; y = p.y; z = p.z;
            }
            viewer.connection.send(new ClientboundSoundPacket(
                Holder.direct(SoundEvents.BREWING_STAND_BREW), SoundSource.BLOCKS,
                x, y, z, 1.0F, 1.0F, level.random.nextLong()));
        }
    }

    /** 音效目标：背包容器 ⇒ 主人本人；世界容器 ⇒ 正在查看它的玩家（{@code isViewing}）。 */
    private static List<ServerPlayer> brewSoundTargets(ContainerContext ctx, Level level) {
        Player owner = ctx.getOwnerPlayer();
        if (owner != null) {
            return (owner instanceof ServerPlayer sp && sp.connection != null) ? List.of(sp) : List.of();
        }
        // tick 路径传入的运行时类型必为 TickableContainerContext（processContext 的入口契约），
        // 但 tick() 的签名是 ContainerContext ⇒ 这里做带注释的收窄取关联 BE
        if (!(ctx instanceof TickableContainerContext tickable)) return List.of();
        List<Container> instances = new ArrayList<>();
        for (BlockEntity be : tickable.getAssociatedBlockEntities()) {
            if (be instanceof Container c) instances.add(c);
        }
        if (instances.isEmpty()) return List.of();
        var server = level.getServer();
        if (server == null) return List.of();

        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.connection != null && ContainerContexts.isViewing(p, instances)) {
                out.add(p);
            }
        }
        return out;
    }

    // ==================== 世界侧辅助 ====================

    /**
     * 可酿造判定（照抄原版 {@code isBrewable}）：材料有效 + 至少 1 瓶 `hasMix(瓶, 材料)`。
     *
     * <p>⚠️ <b>活物品不作材料、也不作瓶子</b>（隔离规则，与活熔炉 / 活漏斗同族）——
     * 活物品是「设备」不是「耗材 / 载体」：
     * 材料侧会被 {@code consumeOne} 吃掉；瓶子侧会被 {@code mix} <b>原地转化</b>
     * （丢掉活标记与内部状态）⇒ 两侧都必须挡在门外。</p>
     */
    private static boolean isBrewable(PotionBrewing brewing, ContainerContext context,
                                      int[] bottleSlots, ItemStack ingredient) {
        if (!isEligibleIngredient(brewing, ingredient)) return false;
        for (int bottleSlot : bottleSlots) {
            if (bottleSlot < 0) continue;
            ItemStack bottle = context.getItem(bottleSlot);
            if (isEligibleBottle(bottle) && BrewingRecipes.hasMix(brewing, bottle, ingredient)) return true;
        }
        return false;
    }

    /** 材料槽准入：非空 + **非活物品** + 是有效酿造材料。 */
    static boolean isEligibleIngredient(PotionBrewing brewing, ItemStack ingredient) {
        return !ingredient.isEmpty()
            && !LivingItemManager.isLivingItem(ingredient)
            && BrewingRecipes.isIngredient(brewing, ingredient);
    }

    /** 瓶子槽准入：非空 + **非活物品**（活物品作瓶子会被 {@code mix} 原地转化掉）。 */
    static boolean isEligibleBottle(ItemStack bottle) {
        return !bottle.isEmpty() && !LivingItemManager.isLivingItem(bottle);
    }

    /**
     * 完成一个批次（照抄原版 {@code doBrew}）：转化最多 3 瓶 + 扣 1 份材料。
     *
     * <p>空瓶与无关物品都<b>安全</b> —— {@code mix} 对它们原样返回（见 {@link BrewingRecipes#brew}）。</p>
     */
    private static void doBrew(PotionBrewing brewing, ContainerContext context,
                               int[] bottleSlots, int ingredientSlot) {
        if (ingredientSlot < 0) return;
        ItemStack ingredient = context.getItem(ingredientSlot);
        for (int bottleSlot : bottleSlots) {
            if (bottleSlot < 0) continue;
            ItemStack bottle = context.getItem(bottleSlot);
            if (!isEligibleBottle(bottle)) continue;   // 空槽 / 活物品 ⇒ 不转化（活物品会被 mix 原地改掉）
            context.setItem(bottleSlot, BrewingRecipes.brew(brewing, ingredient, bottle));
        }
        consumeOne(context, ingredientSlot);
    }

    /**
     * 从槽位消耗 1 个（燃料 / 材料通用）。
     *
     * <p>有合成残留物时按<b>活熔炉先例</b>把残留物<b>放回槽位</b>（D9）——
     * 原版是掉落到方块坐标，但活物品没有坐标（且玩家背包根本没有坐标）⇒ 无法对齐。
     * ⚠️ 影响面≈0：原版全部酿造材料与烈焰粉都不带合成残留物。</p>
     */
    private static void consumeOne(ContainerContext context, int slot) {
        ItemStack stack = context.getItem(slot);
        if (stack.isEmpty()) return;
        if (stack.hasCraftingRemainingItem()) {
            context.setItem(slot, stack.getCraftingRemainingItem());
            return;
        }
        stack.shrink(1);
        context.setItem(slot, stack.copy());
    }

    /**
     * 燃料槽准入：烈焰粉，且**不是活物品**（隔离规则，与活熔炉同族）。
     *
     * <p>包级可见供 {@code BrewingSlotEligibilityTest} 钉判据 ——
     * 2026-10-11 修「活红石被当材料」时材料/瓶子两侧都补了测试，燃料侧漏了
     * （判据在却无守卫）；任何物品都能活化 ⇒ 活烈焰粉真实可达。</p>
     */
    static boolean isBlazePowder(ItemStack stack) {
        return stack.is(Items.BLAZE_POWDER) && !LivingItemManager.isLivingItem(stack);
    }

    // ==================== Tooltip ====================

    /** 悬浮提示：酿造状态（燃料次数）+ 5 个槽位方向。 */
    @Override
    public void addToTooltip(Item.TooltipContext context, Consumer<Component> tooltipAdder,
                             TooltipFlag flag, ItemStack stack) {
        LivingBrewingStandData data = LivingBrewingStandData.of(stack);
        tooltipAdder.accept(Component.nullToEmpty(""));
        tooltipAdder.accept(Component.translatable("tooltip.livingitem.brewing.status"));

        int fuel = data.fuel();
        if (fuel > 0) {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.brewing.fuel", fuel));
        } else {
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.brewing.no_fuel")
                .withStyle(ChatFormatting.DARK_GRAY));
        }

        // 批次进度：从客户端运行时缓存读（瞬态数据不在组件里；同活熔炉的 tooltip 口径）
        BrewingRuntime runtime = LivingItemClientCache.getCurrentTooltipData()
            .get(BrewingStandSegment.INSTANCE);
        if (runtime != null && runtime.isBrewing()) {
            // ⚠️ brewTime 是**剩余**倒计时（400 → 0）⇒ 进度 = 已走完的比例，不是它本身
            // （写成 brewTime/BREW_TIME 会让刚启动的批次显示「进度 100%」，与文案的「剩 N 秒」自相矛盾）
            int percent = (int) ((BrewingBatchLogic.BREW_TIME - runtime.brewTime())
                * 100.0f / BrewingBatchLogic.BREW_TIME);
            tooltipAdder.accept(Component.translatable("tooltip.livingitem.brewing.progress",
                percent, String.format("%.1f", runtime.brewTime() / 20.0))
                .withStyle(ChatFormatting.GRAY));
        }

        BrewingDirectionData dir = data.direction();
        if (dir.directions().isEmpty()) {
            dir = DEFAULT_DIRECTION;
        }
        for (String slotName : SLOT_NAMES) {
            Pos2D d = dir.getDirection(slotName);
            if (d != null && !d.isNone()) {
                tooltipAdder.accept(Component.translatable(
                    "tooltip.livingitem.direction.slot",
                    Component.translatable("slot.livingitem." + slotName),
                    DirectionSymbols.of(d)
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

    // 注：不覆盖 getIgnoredComponentTypes —— 方向与燃料都是**真实数据**，参与堆叠比较。
    // ⚠️ 燃料**不能**放进忽略清单：那是派生数据才能忽略的，忽略会静默丢数据。
}
