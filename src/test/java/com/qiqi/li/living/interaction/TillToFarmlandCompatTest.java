package com.qiqi.li.living.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.farmland.Tillables;
import com.qiqi.li.testutil.FakeHoe;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 活锄头跨模组兼容回归守卫 —— 规则层（客户端拦截）+ 处理器层（服务端写入）两层。
 *
 * <p>回归背景（2026-09-16）：原先活锄头是「6 种原版锄头各注册一条精确规则」，
 * 模组锄头既进不了规则表（客户端不拦截），也过不了服务端的
 * {@code instanceof HoeItem}。改为「通配条目 + {@code canPerformAction(HOE_TILL)}
 * 谓词」后，两类锄头走同一条路径——本测试把这条契约钉死。</p>
 *
 * <p>静态注册表用 {@code InteractionRegistry.clearForTest()} 隔离（包级可见，
 * 故本测试必须与注册表同包）。</p>
 */
class TillToFarmlandCompatTest {

    /** 模组锄头替身：不继承 HoeItem，只声明 HOE_TILL 能力 */
    private static final Item MOD_HOE = FakeHoe.create();

    private static final List<Item> TILLABLE_SOILS = List.of(
        Items.DIRT, Items.GRASS_BLOCK, Items.DIRT_PATH, Items.COARSE_DIRT, Items.ROOTED_DIRT);

    @BeforeEach
    void setUp() {
        InteractionRegistry.clearForTest();
        // 复刻生产注册（LivingItem.commonSetup 活耕地段）
        InteractionRegistry.registerHandler("till_to_farmland", new TillToFarmlandHandler());
        for (Item tillable : Tillables.tillableTargets()) {
            InteractionRegistry.register(new InteractionEntry(tillable, null, 1, "till_to_farmland",
                false, Tillables::canTillWith));
        }
    }

    @AfterAll
    static void tearDown() {
        InteractionRegistry.clearForTest();
    }

    private static ItemStack living(Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @Nullable
    private static InteractionEntry find(ItemStack trigger, ItemStack target) {
        return InteractionRegistry.findInteraction(trigger, target, 1, false);
    }

    // ---------- 规则层：客户端拦截面 ----------

    @Test
    @DisplayName("各可耕土 + 活原版锄头 → 命中 till_to_farmland")
    void allTillableSoilsMatchWithLivingHoe() {
        ItemStack hoe = living(Items.IRON_HOE, 1);
        for (Item soil : TILLABLE_SOILS) {
            InteractionEntry matched = find(hoe, living(soil, 1));
            assertEquals("till_to_farmland", matched == null ? null : matched.actionId(),
                "可耕土应被活锄头拦截，实际未命中=" + soil);
        }
    }

    @Test
    @DisplayName("模组锄头（活化后）同样命中 —— 跨模组兼容的核心用例")
    void modHoeMatches() {
        InteractionEntry matched = find(living(MOD_HOE, 1), living(Items.DIRT, 1));
        assertEquals("till_to_farmland", matched == null ? null : matched.actionId(),
            "不继承 HoeItem 的模组锄头必须命中（原实现只认 6 种原版锄头）");
    }

    @Test
    @DisplayName("非活锄头不拦截 —— 通配条目靠谓词自查活物品，否则会吞原版操作")
    void nonLivingHoeDoesNotMatch() {
        assertNull(find(new ItemStack(Items.IRON_HOE), living(Items.DIRT, 1)),
            "非活锄头不应拦截（原版拿起/分堆要照常）");
        assertNull(find(new ItemStack(MOD_HOE), living(Items.DIRT, 1)),
            "非活模组锄头同样不拦截");
    }

    @Test
    @DisplayName("非锄头活物品 / 不可耕目标 / 非活目标 → 均不拦截")
    void nonMatchingCombinationsReturnNull() {
        ItemStack hoe = living(Items.IRON_HOE, 1);

        assertNull(find(living(Items.DIAMOND_SWORD, 1), living(Items.DIRT, 1)), "活剑不是锄头");
        assertNull(find(hoe, living(Items.STONE, 1)), "活石头不可耕");
        assertNull(find(hoe, new ItemStack(Items.DIRT)), "非活目标不触发");
        assertNull(find(ItemStack.EMPTY, living(Items.DIRT, 1)), "空手不触发");
    }

    // ---------- 处理器层：服务端写入 ----------

    /**
     * 驱动一次 {@code TillToFarmlandHandler.handle}，返回写入槽位的物品；未写入返回 null。
     *
     * <p>{@code player.containerMenu} 是 public 字段，mock 实例可直接赋值；
     * {@code player.level()} 在 mock 下返回 null，{@code hurtAndBreak} 内部判
     * {@code instanceof ServerLevel} 不成立即静默返回，不会 NPE。</p>
     */
    @Nullable
    private static ItemStack invokeHandle(ItemStack target, ItemStack carried, boolean creative) {
        Slot slot = Mockito.mock(Slot.class);
        Mockito.when(slot.getItem()).thenReturn(target);

        AbstractContainerMenu menu = Mockito.mock(AbstractContainerMenu.class);
        Mockito.when(menu.getCarried()).thenReturn(carried);

        ServerPlayer player = Mockito.mock(ServerPlayer.class);
        player.containerMenu = menu;
        Mockito.when(player.isCreative()).thenReturn(creative);

        new TillToFarmlandHandler().handle(player, slot);

        ArgumentCaptor<ItemStack> captor = ArgumentCaptor.forClass(ItemStack.class);
        Mockito.verify(slot, Mockito.atMostOnce()).set(captor.capture());
        return captor.getAllValues().isEmpty() ? null : captor.getAllValues().get(0);
    }

    @Test
    @DisplayName("活模组锄头 + 活泥土×3 → 写入活耕地×3（保留活标记与堆叠数）")
    void modHoeTillsDirtStack() {
        ItemStack result = invokeHandle(living(Items.DIRT, 3), living(MOD_HOE, 1), false);

        assertTrue(result != null && result.is(Items.FARMLAND), "产物应为耕地，实际=" + result);
        assertEquals(3, result.getCount(), "堆叠数应保留");
        assertTrue(LivingItemManager.isLivingItem(result), "产物必须保留活标记");
    }

    @Test
    @DisplayName("活模组锄头 + 活砂土 → 写入活泥土（不是耕地，与原版一致）")
    void modHoeTillsCoarseDirtIntoDirt() {
        ItemStack result = invokeHandle(living(Items.COARSE_DIRT, 1), living(MOD_HOE, 1), false);

        assertTrue(result != null && result.is(Items.DIRT), "砂土产物应为泥土，实际=" + result);
        assertFalse(result.is(Items.FARMLAND), "砂土一步耕不出耕地（要再耕一跳）");
        assertTrue(LivingItemManager.isLivingItem(result), "产物必须保留活标记");
    }

    @Test
    @DisplayName("非活锄头 / 活剑 / 非活目标 → 槽位不变（不写入）")
    void rejectedCombinationsWriteNothing() {
        assertNull(invokeHandle(living(Items.DIRT, 1), new ItemStack(Items.IRON_HOE), false),
            "非活锄头不写入");
        assertNull(invokeHandle(living(Items.DIRT, 1), living(Items.DIAMOND_SWORD, 1), false),
            "活剑不是锄头");
        assertNull(invokeHandle(new ItemStack(Items.DIRT), living(MOD_HOE, 1), false),
            "非活目标不写入（否则会把普通土也耕了）");
        assertNull(invokeHandle(living(Items.STONE, 1), living(MOD_HOE, 1), false),
            "不可耕目标不写入");
    }

    @Test
    @DisplayName("创造模式同样校验光标（只免耐久消耗，不免锄头校验）")
    void creativeStillValidatesCarried() {
        assertNull(invokeHandle(living(Items.DIRT, 1), new ItemStack(MOD_HOE), true),
            "创造模式下非活锄头也不生效");

        ItemStack creativeResult = invokeHandle(living(Items.GRASS_BLOCK, 1), living(MOD_HOE, 1), true);
        assertTrue(creativeResult != null && creativeResult.is(Items.FARMLAND),
            "创造模式下活锄头仍可耕，实际=" + creativeResult);
    }
}
