package com.qiqi.li.living.domain.battle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.qiqi.li.living.container.SimpleContainerContext;
import com.qiqi.li.living.container.TickContext;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

class LivingBattleFunctionTest {

    private static final class Context extends SimpleContainerContext {
        Context(ItemStackHandler handler, String cacheKey) {
            super(handler, null, List.of(), List.of(), null, cacheKey);
        }
        @Override public int getLiveColumns() { return 3; }
        @Override public void syncSlotToClients(int slot, ItemStack stack) {}
    }

    private final LivingBattleFunction function = new LivingBattleFunction();

    /** 唯一缓存键：容器级数据登记在静态 CONTAINER_DATA，跨测试同键会读到上一个用例的残留。 */
    private Context newContext() {
        return new Context(new ItemStackHandler(9), "battle_test_" + System.nanoTime());
    }

    @Test
    void cheapGateFlags() {
        assertFalse(function.canApply(new ItemStack(net.minecraft.world.item.Items.STONE)),
            "纯容器级逻辑不挂物品");
        assertEquals("living_battle", function.getFunctionId());
        assertEquals(5, function.getPriority(), "prio 5（活地狱门已占 4）");
    }

    @Test
    void registrationTimeDeclaresSelfSustainingCandidate() {
        assertTrue(function.shouldTickWithoutOwnItems(null), "注册期恒真（自维持候选）");
    }

    @Test
    void containerWithoutBattleDataIsCold() {
        Context ctx = newContext();
        assertFalse(function.shouldTickWithoutOwnItems(ctx), "无战局数据 = 静息（廉价门依据）");
    }

    @Test
    void containerWithActiveBattleIsHot() {
        Context ctx = newContext();
        ctx.getOrCreateContainerData(BattleArenaData.KEY)
            .configure("battle_test", new BlockPos(100, 220, 100), 1, 9, 1);
        assertTrue(function.shouldTickWithoutOwnItems(ctx), "有战局 = 持续工作（不进 idle skip）");
    }

    @Test
    void containerWithClearedBattleIsColdAgain() {
        Context ctx = newContext();
        BattleArenaData data = ctx.getOrCreateContainerData(BattleArenaData.KEY);
        data.configure("battle_test", new BlockPos(100, 220, 100), 1, 9, 1);
        data.reset();
        assertFalse(function.shouldTickWithoutOwnItems(ctx), "reset 后 = 静息");
    }

    @Test
    void tickContainerDataWithoutDataIsSilentNoOp() {
        Context ctx = newContext();
        TickContext tick = new TickContext(ctx);
        assertDoesNotThrow(() -> function.tickContainerData(List.of(), ctx, tick),
            "无战局数据的容器每拍都进这里 ⇒ 必须廉价 no-op，不许抛/不许建数据");
    }
}
