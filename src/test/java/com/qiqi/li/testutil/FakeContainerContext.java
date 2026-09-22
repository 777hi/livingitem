package com.qiqi.li.testutil;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.mockito.Mockito;

import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.ContainerLivingItemHandler;

/**
 * 内存容器上下文 —— 单元测试用的 {@link ContainerContext} 替身。
 *
 * <p>用简单的 {@code ItemStack} 数组模拟容器，不依赖 Level、BlockEntity
 * 或 IItemHandler，使纯逻辑（红石传播、槽位解析等）可以脱离游戏环境测试。</p>
 *
 * <p>用法：
 * <pre>
 * FakeContainerContext ctx = new FakeContainerContext(27, 9);
 * ctx.set(4, new ItemStack(Items.REDSTONE));
 * </pre>
 *
 * <p>如需驱动依赖全局时钟的逻辑（如红石传播的节拍对齐），
 * 用 {@link #withGameTime(long)} 提供一个可控 game time 的 mock Level。</p>
 */
public class FakeContainerContext implements ContainerContext {

    private final ItemStack[] slots;
    private final int width;
    private final String containerKey;
    private Level level;
    private BlockPos blockPos;

    /** 记录所有 syncSlotToClients 调用的槽位，供断言同步行为 */
    public final List<Integer> syncedSlots = new ArrayList<>();

    public FakeContainerContext(int size, int width) {
        this(size, width, "test_container");
    }

    public FakeContainerContext(int size, int width, String containerKey) {
        this.slots = new ItemStack[size];
        this.width = width;
        this.containerKey = containerKey;
        for (int i = 0; i < size; i++) {
            slots[i] = ItemStack.EMPTY;
        }
    }

    /**
     * 设置一个返回指定 game time 的 mock Level，便于测试依赖全局时钟的逻辑。
     * 偶数 game time 时红石传播会真正执行，奇数时跳帧。
     */
    public FakeContainerContext withGameTime(long gameTime) {
        Level mock = Mockito.mock(Level.class);
        Mockito.when(mock.getGameTime()).thenReturn(gameTime);
        this.level = mock;
        return this;
    }

    /**
     * 提供一个方块位置，便于链式构建测试场景。
     *
     * <p>跨容器传输（{@code CrossContainerTransfer.execute}）第一步就要
     * {@code getBlockPos()}（为 null 直接 return false），因此涉及"容器在世界里"
     * 的用例必须显式提供。默认 null（= 无世界位置的纯内存容器）。</p>
     */
    public FakeContainerContext withBlockPos(BlockPos pos) {
        this.blockPos = pos;
        return this;
    }

    @Override
    public BlockPos getBlockPos() {
        return blockPos;
    }

    /** 放入物品并返回 this，便于链式构建测试场景 */
    public FakeContainerContext set(int slot, ItemStack stack) {
        slots[slot] = stack;
        // 模拟生产契约：物品变动必须使跨 tick 快照缓存失效
        ContainerLivingItemHandler.bumpContainerRevision(this);
        return this;
    }

    /**
     * 直写底层槽位、<b>不</b>触发修订计数（模拟原版玩家点击 / 漏斗 / 掉落物拾取
     * 等途径，它们直接改底层容器而绕开本模组的 {@code setItem}/{@code syncSlotToClients}）。
     *
     * <p>用于回归「容器修订计数停滞」类 bug：内容已变但 rev 不增，稳态跳过永不打破，
     * 容器内信号层集体死掉。</p>
     */
    public FakeContainerContext rawSet(int slot, ItemStack stack) {
        slots[slot] = stack;
        return this;
    }

    @Override
    public Level getLevel() {
        return level;
    }

    @Override
    public int getSize() {
        return slots.length;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public ItemStack getItem(int logicalSlot) {
        if (logicalSlot < 0 || logicalSlot >= slots.length) return ItemStack.EMPTY;
        return slots[logicalSlot];
    }

    @Override
    public void setItem(int logicalSlot, ItemStack stack) {
        if (logicalSlot < 0 || logicalSlot >= slots.length) return;
        slots[logicalSlot] = stack;
        // 模拟生产契约：物品变动必须使跨 tick 快照缓存失效
        ContainerLivingItemHandler.bumpContainerRevision(this);
    }

    @Override
    public int getMaxStackSize() {
        return 64;
    }

    @Override
    public String getContainerKey() {
        return containerKey;
    }

    @Override
    public String getStableKey(int logicalSlot, String functionId) {
        return containerKey + "_slot_" + logicalSlot + "_func_" + functionId;
    }

    @Override
    public void syncSlotToClients(int logicalSlot, ItemStack stack) {
        // 模拟生产契约：DataComponent 状态变化（走 syncSlotToClients，而非 setItem）
        // 同样必须使跨 tick 快照缓存失效
        ContainerLivingItemHandler.bumpContainerRevision(this);
        syncedSlots.add(logicalSlot);
    }
}
