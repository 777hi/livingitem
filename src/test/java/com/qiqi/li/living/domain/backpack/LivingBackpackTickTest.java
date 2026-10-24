package com.qiqi.li.living.domain.backpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerAnchor;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ItemFormContainers;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.testutil.FakeContainerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * {@link LivingBackpackFunction#tick} 的三道闸（2026-10-24 终审补）。
 *
 * <p>这个功能是整个容器兼容专题里<b>唯一带递归 + ThreadLocal + 环检测</b>的部件 ——
 * 原测试只覆盖了「认领 / 功能 ID / 取消活化」，<b>tick 本身一行没测</b>。</p>
 *
 * <p>本类守三件事：</p>
 * <ol>
 *   <li><b>宿主锚点传递</b>：内层 ctx 的 {@code getHostAnchor()} 必须是宿主的 {@code getAnchor()}
 *       （「物品在箱子里，就在箱子那里」）。</li>
 *   <li><b>深度闸</b>：{@code MAX_DEPTH = 1} ⇒ 背包里的背包不再被处理（最内层内容不 tick）。</li>
 *   <li><b>逐条目隔离</b>：一个背包的来源抛异常，不能带走同批其它背包。</li>
 * </ol>
 *
 * <p>⚠️ <b>环检测目前不可达</b>：{@code MAX_DEPTH = 1} 的深度闸在环检测之前就返回了。
 * 它是为「将来放开嵌套」准备的（设计稿 §6），所以这里不测 —— 测不到，也不该假装测到了。</p>
 */
class LivingBackpackTickTest {

    /** 探针物品：没人认领、也不是容器，只在测试里当「背包内容物」。 */
    private static final ItemStack PROBE_ITEM = living(new ItemStack(Items.SHULKER_SHELL));

    /**
     * 探针功能：记录它拿到的容器上下文。
     *
     * <p>⚠️ 只认 {@link #PROBE_ITEM} —— 不能用木棍/纸/箱子这些被既有测试断言「无功能」的物品，
     * 否则会串味（{@code registerFunction} 是全局静态的）。</p>
     */
    private static final class ProbeFunction implements LivingItemFunction {
        final List<ContainerAnchor> hostAnchors = new ArrayList<>();
        final List<ContainerContext> contexts = new ArrayList<>();

        @Override
        public boolean canApply(ItemStack stack) {
            return stack.is(Items.SHULKER_SHELL);
        }

        @Override
        public String getFunctionId() {
            return "test_probe";
        }

        @Override
        public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {
            hostAnchors.add(context.getHostAnchor());
            contexts.add(context);
        }
    }

    private static final ProbeFunction PROBE = new ProbeFunction();

    /**
     * 背包来源：认领潜行盒，内容<b>按物品栈</b>查表（不在表里 ⇒ 抛异常，用来测隔离）。
     *
     * <p>⚠️ 不能用一个「next」字段：同批多个背包会共用同一个 handler，
     * 那样根本模拟不出「某一个来源坏了」。</p>
     */
    private static final class FakeSource implements ItemFormContainers.Source {
        final java.util.IdentityHashMap<ItemStack, IItemHandler> contents = new java.util.IdentityHashMap<>();

        @Override
        public boolean isContainerItem(ItemStack stack) {
            return stack.is(Items.SHULKER_BOX);
        }

        @Override
        public IItemHandler contentHandler(ItemStack stack) {
            IItemHandler handler = contents.get(stack);
            if (handler == null) throw new IllegalStateException("模拟来源故障");
            return handler;
        }
    }

    private static final FakeSource SOURCE = new FakeSource();

    @BeforeAll
    static void registerProbe() {
        LivingItemManager.registerFunction(PROBE);
    }

    @BeforeEach
    void setUp() {
        // ⚠️ 必须在每个用例前注册：@AfterEach 会清空注册表（避免污染其它测试类）
        ItemFormContainers.register(SOURCE);
    }

    @AfterEach
    void tearDown() {
        ItemFormContainers.clearForTest();
        PROBE.hostAnchors.clear();
        PROBE.contexts.clear();
        SOURCE.contents.clear();
    }

    private static ItemStack living(ItemStack stack) {
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    /** 一个装着指定内容的背包物品栈。 */
    private static ItemStack backpackWith(ItemStack... contents) {
        ItemStackHandler handler = new ItemStackHandler(Math.max(1, contents.length));
        for (int i = 0; i < contents.length; i++) {
            handler.setStackInSlot(i, contents[i]);
        }
        ItemStack backpack = living(new ItemStack(Items.SHULKER_BOX));
        SOURCE.contents.put(backpack, handler);
        return backpack;
    }

    // ── 1. 宿主锚点传递 ──

    @Test
    @DisplayName("宿主锚点：内层 ctx 拿到宿主的锚点（方块容器 ⇒ AtBlock 同一坐标）")
    void tick_passesHostAnchorToInnerContext() {
        BlockPos hostPos = new BlockPos(12, 64, -3);
        FakeContainerContext host = new FakeContainerContext(27, 9).withBlockPos(hostPos);
        ItemStack backpack = backpackWith(PROBE_ITEM.copy());
        host.set(0, backpack);

        new LivingBackpackFunction().tick(
            List.of(new LivingItemFunction.SlotEntry(0, backpack)),
            host, new TickContext(host), mock(ServerLevel.class));

        assertEquals(1, PROBE.hostAnchors.size(), "背包内容物必须被 tick 到（否则这个测试什么也没验）");
        ContainerAnchor anchor = PROBE.hostAnchors.get(0);
        assertTrue(anchor instanceof ContainerAnchor.AtBlock,
            "宿主是方块容器 ⇒ 内层拿到的宿主锚点必须是 AtBlock，实际：" + anchor);
        assertEquals(hostPos, ((ContainerAnchor.AtBlock) anchor).pos(),
            "「物品在箱子里，就在箱子那里」—— 坐标必须就是宿主的");
    }

    @Test
    @DisplayName("宿主锚点：宿主没有锚点（物品形态）⇒ 内层拿到 null，不是瞎猜一个")
    void tick_withoutHostAnchor_passesNull() {
        FakeContainerContext host = new FakeContainerContext(27, 9);   // 无 blockPos、无主人
        ItemStack backpack = backpackWith(PROBE_ITEM.copy());
        host.set(0, backpack);

        new LivingBackpackFunction().tick(
            List.of(new LivingItemFunction.SlotEntry(0, backpack)),
            host, new TickContext(host), mock(ServerLevel.class));

        assertEquals(1, PROBE.hostAnchors.size());
        assertEquals(null, PROBE.hostAnchors.get(0), "宿主无锚点 ⇒ 传 null（功能自己决定怎么办）");
    }

    // ── 2. 深度闸 ──

    @Test
    @DisplayName("深度闸：背包里的背包不再处理（MAX_DEPTH=1 ⇒ 最内层内容不 tick）")
    void tick_stopsAtDepthLimit() {
        FakeContainerContext host = new FakeContainerContext(27, 9).withBlockPos(new BlockPos(0, 64, 0));
        ItemStack innerBackpack = backpackWith(PROBE_ITEM.copy());
        ItemStack outerBackpack = backpackWith(innerBackpack);
        host.set(0, outerBackpack);

        new LivingBackpackFunction().tick(
            List.of(new LivingItemFunction.SlotEntry(0, outerBackpack)),
            host, new TickContext(host), mock(ServerLevel.class));

        assertEquals(0, PROBE.contexts.size(),
            "第一层背包会处理，但它里面的背包必须被深度闸挡住 ⇒ 最内层的探针物品一次都不该被 tick"
            + "（放开嵌套时这条会变红 —— 那时要同时补环检测测试）");
    }

    // ── 3. 逐条目隔离 ──

    @Test
    @DisplayName("隔离：一个背包的来源抛异常 ⇒ 不带走同批其它背包")
    void tick_isolatesFailurePerEntry() {
        FakeContainerContext host = new FakeContainerContext(27, 9).withBlockPos(new BlockPos(0, 64, 0));

        // 第一个背包的来源会抛（SOURCE.next = null），第二个正常
        ItemStack broken = living(new ItemStack(Items.SHULKER_BOX));
        ItemStack good = backpackWith(PROBE_ITEM.copy());
        host.set(0, broken);
        host.set(1, good);

        assertDoesNotThrow(() -> new LivingBackpackFunction().tick(
            List.of(
                new LivingItemFunction.SlotEntry(0, broken),
                new LivingItemFunction.SlotEntry(1, good)),
            host, new TickContext(host), mock(ServerLevel.class)),
            "坏来源只该让它自己那一个背包跳过 —— 抛出去会带走整个容器的 tick");

        assertEquals(1, PROBE.contexts.size(),
            "第二个背包必须照常处理（隔离是按条目的，不是按批次的）");
    }
}
