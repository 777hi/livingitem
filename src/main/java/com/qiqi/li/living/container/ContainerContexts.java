package com.qiqi.li.living.container;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

import com.qiqi.li.living.util.DoubleChestPositions;

/**
 * 多方块容器<b>边界带身份解析</b>共享内核（Q6，2026-10-04）。
 *
 * <h3>为什么存在</h3>
 * <p>原版 / NeoForge 的多方块容器（大箱子）<b>没有统一身份句柄</b> ——
 * {@code Container} 实例、{@code BlockEntity}+位置、合并 {@code IItemHandler} 三个视图
 * <b>互不可达</b>（{@code CompoundContainer} 不给两半访问器、菜单不暴露位置）。
 * 于是每个需要「菜单槽位 / 同步 / 世界 ↔ 容器状态」的消费者都被迫各解一遍，
 * 教训还散落四处（hopper §10.25/§6.4、container-compatibility、changelog）。</p>
 *
 * <p><b>tick 主链路不乱</b>（上下文以 positions/BEs/合并 handler 单一事实构建）；
 * 乱的是<b>边界带</b> —— GUI 级新功能每个都重新踩一遍。本类把边界带收敛到一处。</p>
 *
 * <h3>收编原则：搬家不是重写</h3>
 * <p>本类的每个方法都是<b>已验证的现成实现</b>的迁移（含各自的坑位注释），
 * 消费者改走本入口（或保留薄委托）。</p>
 *
 * <p>批次 A（2026-10-04）收编服务端 {@link #resolve} / {@link #isViewing}；
 * 批次 B（2026-10-04）补 {@link #ownsContainer}（归属匹配）与 {@link #isSameSlotSpace}（槽位体系探针）；
 * 批次 C（2026-10-04）的客户端槽位解析因 {@code SlotWrapperAccessor} 是<b>客户端 Mixin</b>
 * （common 引用会让专用服务端崩）⇒ <b>不能放本类</b>，实装为
 * {@code com.qiqi.li.client.util.ClientSlotResolve}（被迫分居两侧）。</p>
 */
public final class ContainerContexts {

    private static final Map<Class<?>, Field[]> COMPOUND_FIELDS = new ConcurrentHashMap<>();

    private ContainerContexts() {}

    // ── resolve：菜单槽位 → tick 循环里的那个上下文 ──────────────

    /**
     * 从菜单槽位反查它所属容器的 <b>tick 上下文</b>（容器键与 {@code processContainerAt}
     * 的构建规则一致 ⇒ 同一容器同一键）。不支持的类型返回 {@code null}。
     *
     * <p>规则镜像 {@code ContainerLivingItemHandler.processContainerAt}：</p>
     * <ul>
     *   <li>玩家背包：{@code slot.container == player.getInventory()}</li>
     *   <li>单 BE 容器：{@code slot.container instanceof BlockEntity}</li>
     *   <li>原版大箱子：{@code CompoundContainer}（半箱无公开访问器 ⇒ 反射取两半，
     *       顺序经 {@link DoubleChestPositions#find} 规范化为 LEFT 在前，与 tick 构建同源）</li>
     *   <li>末影箱（F-1，2026-10-05）：{@code slot.container instanceof PlayerEnderChestContainer}
     *       —— 原版末影箱 GUI 的槽位容器<b>不是 BE</b>（这正是它原先走不进上面三分支的原因）
     *       ⇒ 构造 {@link EnderChestContainerContext}，键 = {@code player_<uuid>_ender_chest}，
     *       与 tick 路径 {@code processEnderChest} 同源</li>
     * </ul>
     */
    public static TickableContainerContext resolve(ServerPlayer player, Slot slot) {
        Level level = player.level();
        Container container = slot.container;

        if (container == player.getInventory()) {
            IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
            if (handler == null) return null;
            return new SimpleContainerContext(handler, player.getInventory(), new ArrayList<>(),
                new ArrayList<>(), level);
        }

        if (container instanceof BlockEntity be) {
            if (be.getLevel() == null) return null;
            return beContext(level, List.of(be.getBlockPos()), List.of(be));
        }

        if (container instanceof CompoundContainer) {
            return compoundContext(level, container);
        }

        // 末影箱（F-1，2026-10-05）：原版末影箱 GUI 的槽位容器是 PlayerEnderChestContainer
        // （不是 BE）—— 这正是它走不进上面三分支的原因。与 tick 路径（processEnderChest）同源构造。
        if (container instanceof PlayerEnderChestContainer) {
            PlayerEnderChestContainer enderChest = player.getEnderChestInventory();
            if (enderChest == null) return null;
            return new EnderChestContainerContext(new InvWrapper(enderChest), player, level);
        }

        return null;
    }

    private static TickableContainerContext beContext(Level level, List<BlockPos> positions,
                                                      List<BlockEntity> blockEntities) {
        for (BlockEntity be : blockEntities) {
            if (be instanceof RandomizableContainerBlockEntity rc && rc.getLootTable() != null) return null;
        }
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, positions.get(0), null);
        if (handler == null) return null;
        return new SimpleContainerContext(handler, null, new ArrayList<>(positions),
            new ArrayList<>(blockEntities), level);
    }

    /**
     * 大箱子：反射取 {@code CompoundContainer} 的两半（container1 / container2），
     * 顺序用 {@link DoubleChestPositions#find} 规范化（LEFT 在前，与 tick 构建的容器键一致）。
     */
    private static TickableContainerContext compoundContext(Level level, Container container) {
        BlockEntity[] halves = reflectHalves(container);
        if (halves == null) return null;

        List<BlockPos> doubleChestPos = DoubleChestPositions.find(level, halves[0].getBlockPos());
        if (doubleChestPos.isEmpty()) return null;

        List<BlockEntity> blockEntities = new ArrayList<>();
        for (BlockPos pos : doubleChestPos) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) return null;
            blockEntities.add(be);
        }
        return beContext(level, doubleChestPos, blockEntities);
    }

    /** 反射取 {@code CompoundContainer} 的两个 {@code Container} 半箱字段（结果按类缓存）。 */
    private static BlockEntity[] reflectHalves(Container container) {
        Field[] fields = COMPOUND_FIELDS.computeIfAbsent(CompoundContainer.class, clazz -> {
            List<Field> found = new ArrayList<>();
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getType() == Container.class) {
                    f.setAccessible(true);
                    found.add(f);
                }
            }
            return found.toArray(new Field[0]);
        });
        if (fields.length < 2) return null;
        try {
            Object a = fields[0].get(container);
            Object b = fields[1].get(container);
            if (a instanceof BlockEntity be1 && b instanceof BlockEntity be2) {
                return new BlockEntity[]{be1, be2};
            }
        } catch (IllegalAccessException e) {
            return null;
        }
        return null;
    }

    // ── isViewing：玩家菜单里是否含该容器（合并 2 处重复）──────────

    /**
     * 判断玩家当前打开的菜单里是否包含「该容器关联的 Container 实例」。
     *
     * <p><b>大箱子（v19.1 修复）</b>：原版大箱菜单的槽位容器是 {@code CompoundContainer}，
     * 与两半 BE 实例都不相等 ⇒ 必须额外用其 {@code contains(Container)} 匹配，
     * 否则大箱子里看不到同步。</p>
     *
     * <p>收编自 {@code ContainerRuntimeCache.isViewingContainer} 与
     * {@code FluidFlowServerSync.isViewingContainer} 两份同构实现。</p>
     */
    public static boolean isViewing(ServerPlayer player, Collection<Container> containerInstances) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu) return false;
        for (Slot slot : menu.slots) {
            if (ownsContainer(slot.container, containerInstances)) return true;
        }
        return false;
    }

    /**
     * 玩家当前是否<b>正打开自己的末影箱界面</b>。
     *
     * <p><b>用途（2026-10-06 第 ③ 次泄漏修复）</b>：原版末影箱既不是 BE 也不在玩家
     * {@code Inventory} 里 ⇒ 流体快照派发无法像BE 容器那样用 {@link #isViewing} 过滤
     * 「菜单槽位是否属于该容器」，只能问「这个菜单是不是末影箱」。此前该分支
     * <b>不判 viewer</b>、每 tick 直发主人 ⇒ 玩家关掉末影箱打开普通箱子后，包仍在来，
     * 客户端 {@code chestLikeTarget} 提示被翻成 {@code ENDER_CHEST} ⇒
     * <b>末影箱的水渲染到别的箱子界面上</b>。</p>
     *
     * <p>判据（服务端侧真实容器可达，与 {@code LivingEnderChestFunction} 原有实现逐字一致）：
     * {@code menu instanceof ChestMenu && menu.getContainer() instanceof PlayerEnderChestContainer}。
     * 本类是<b>唯一实现点</b>，活化绑定与快照派发共用。</p>
     */
    public static boolean isViewingEnderChest(Player player) {
        return isEnderChestMenu(player.containerMenu);
    }

    /** 包级私有（供单测构造菜单替身）：菜单是否为末影箱菜单。 */
    static boolean isEnderChestMenu(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu chest && chest.getContainer() instanceof PlayerEnderChestContainer;
    }

    // ── ownsContainer：菜单槽位的容器实例是否属于给定集合 ──────────

    /**
     * 判断「某个菜单槽位的容器实例」是否属于给定容器实例集合。
     *
     * <p><b>大箱子</b>：菜单槽位的容器是 {@code CompoundContainer}(左半BE, 右半BE)
     * 包装对象而非 BE 本体 ⇒ 单靠 {@code contains} 永远不命中，必须再用其自带的
     * {@code contains(Container)} 逐个匹配关联 BE。</p>
     *
     * <p>收编自 {@code SimpleContainerContext.slotBelongsTo}（组件同步归属验证）；
     * 与 {@link #isViewing} 逐槽判据同源 —— 后者即「菜单里任取一槽，是否属于该集合」。</p>
     */
    public static boolean ownsContainer(Container menuContainer, Collection<Container> containerInstances) {
        if (containerInstances.contains(menuContainer)) return true;
        if (menuContainer instanceof CompoundContainer compound) {
            for (Container be : containerInstances) {
                if (compound.contains(be)) return true;
            }
        }
        return false;
    }

    // ── isSameSlotSpace：Container 与 handler 是否共用同一套槽位编号 ──

    /**
     * 槽位体系一致性探针 —— {@code Container} 与 {@code IItemHandler} 是否共用同一套槽位编号。
     *
     * <p>多方块容器（大箱子）的两套槽位体系可能整体错位：{@code Container} 给单个半箱（27 槽），
     * {@code IItemHandler} 给合并（54 槽）。按逻辑槽位读写前先探一次，不过则回退 handler。</p>
     *
     * <p>两级判据，逐级加严，且<b>都不依赖「知道容器由几个方块组成」</b>（三方块 / 四块同样成立）：</p>
     * <ol>
     *   <li><b>槽位数一致</b> —— 不一致必然是「单体 vs 合并」；</li>
     *   <li><b>单槽交叉校验</b> —— 挡住「槽位数相同但映射不同」（如合并顺序相反）：
     *       比对同一槽位在两套体系里的「空/非空 + 物品」是否一致。</li>
     * </ol>
     *
     * <p>收编自 {@code SimpleContainerContext.isSameSlotSpaceAsHandler}（hopper §10.25 修复）。</p>
     */
    public static boolean isSameSlotSpace(Container container, IItemHandler handler, int slot) {
        int size = container.getContainerSize();
        if (size != handler.getSlots() || slot < 0 || slot >= size) return false;

        ItemStack viaContainer = container.getItem(slot);
        ItemStack viaHandler = handler.getStackInSlot(slot);
        if (viaContainer.isEmpty() || viaHandler.isEmpty()) {
            return viaContainer.isEmpty() && viaHandler.isEmpty();
        }
        return ItemStack.isSameItemSameComponents(viaContainer, viaHandler);
    }
}
