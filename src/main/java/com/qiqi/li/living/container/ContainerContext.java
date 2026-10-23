package com.qiqi.li.living.container;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 容器上下文 —— 活物品功能与容器之间的完整交互接口。
 *
 * <p>这是一个组合接口，继承所有容器能力接口：
 * <ul>
 *   <li>{@link SlotInfoProvider} — 物品读写 + 槽位能力查询</li>
 *   <li>{@link ContainerSync} — 客户端数据同步</li>
 *   <li>{@link ContainerIdentity} — 容器身份标识 + 世界信息</li>
 * </ul>
 *
 * <p>注意：tick 级临时状态（槽位互斥、级联防护、快照、流体数据）
 * 已移至 {@link TickContext}，不再属于此接口。</p>
 *
 * <p>功能类应优先依赖最小接口（如 {@link SlotInfoProvider}），
 * 只有需要完整容器能力时才依赖此接口。</p>
 *
 * @see LivingContainer 基础物品读写
 * @see SlotInfoProvider 槽位能力查询
 * @see ContainerSync 客户端同步
 * @see ContainerIdentity 身份标识
 * @see TickContext Tick 级临时状态
 */
public interface ContainerContext extends SlotInfoProvider, ContainerSync, ContainerIdentity {

    default boolean isValidSlot(int logicalSlot) {
        return logicalSlot >= 0 && logicalSlot < getSize();
    }

    /**
     * 逻辑槽位 → **物理槽位**（handler / 菜单 / 网络包用的索引）。默认恒等。
     *
     * <p>⭐ 为什么需要这一层（2026-10-21 真机「渲染和逻辑差了一格」）：
     * 功能类（熔炉燃料 = {@code slot + width}、漏斗两端…）用的是<b>逻辑槽</b>
     * （= 容器自己的存储格编号 0..size-1）；而 <b>GUI 槽号、{@code ClientboundContainerSetSlotPacket}、
     * 客户端 tooltip 的 {@code Slot#getContainerSlot()}、运行时遥测包的键</b>都是<b>物理槽</b>
     * （= handler 的真实索引，含前置装备槽）。两者只有在「容器有前置非存储槽」时才不同——
     * 目前唯一例子是马/驴/骡（索引 0 = 马鞍，见 {@code EntityContainerContext.SADDLE_OFFSET}）。</p>
     *
     * <p>⚠️ 任何把逻辑槽交给<b>对外</b>通道的地方都必须过本方法，否则显示会整体偏位。</p>
     */
    default int toPhysicalSlot(int logicalSlot) {
        return logicalSlot;
    }

    /**
     * 物理槽位 → **逻辑槽位**（容器存储格编号）。默认恒等，与 {@link #toPhysicalSlot} 互为反函数。
     *
     * <p>用于<b>反向</b>边界：GUI 交互（倒/汲水、空槽点击）手里拿的是 {@code Slot#getContainerSlot()}
     * （物理），而 {@code ContainerFluidData} 的源键、功能类的槽位计算都是逻辑槽
     * ⇒ 不过本方法会把水倒进「上一格」（真机「差一格」的同源第二例）。</p>
     */
    default int toLogicalSlot(int physicalSlot) {
        return physicalSlot;
    }

    /**
     * 查询容器级持久数据（不创建，不存在返回 {@code null}）。
     *
     * <p>默认返回 {@code null} —— 不支持容器级数据的实现（如掉落物容器）用
     * 「返回 null」表达「我没有」，与 {@link TickableContainerContext} 的空实现哲学一致。</p>
     */
    default <T> T peekContainerData(ContainerDataKey<T> key) {
        return null;
    }

    /**
     * 查询或创建容器级持久数据。
     *
     * <p>默认实现<b>不存储</b>（创建后直接返回）—— 只有支持跨 tick 持久的实现
     * （{@code SimpleContainerContext}）才覆写它。</p>
     */
    default <T> T getOrCreateContainerData(ContainerDataKey<T> key) {
        return key.create();
    }

    /**
     * 容器所属玩家 —— 仅玩家背包 / 末影箱有；方块容器返回 {@code null}。
     *
     * <p>供领域侧取「容器的主人」（如把容器级数据落到 Player attachment）而<b>不必
     * {@code instanceof} 具体实现类</b>（2026-10-08 计划 ⑤ 补；对齐
     * {@code docs/buffer/living-tool-design.md} §2.1 ③ 的建议）。</p>
     */
    default Player getOwnerPlayer() {
        return null;
    }

    /**
     * 本容器在世界里的<b>参考物</b>（{@link ContainerAnchor}）；没有则 {@code null}。
     *
     * <p>默认三选一：有方块位置 ⇒ {@code AtBlock}；有主人 ⇒ {@code AtPlayer}；否则 {@code null}。
     * <b>实体容器与掉落物必须覆写</b>（它们的 {@code getBlockPos()} 刻意是 null，
     * 且没有主人 —— 不覆写就会退化成「没有锚点」）。</p>
     *
     * <p>⚠️ 物品形态容器（活背包）默认 {@code null} 是<b>正确的</b>：它自己不在世界里，
     * 要靠宿主告诉它。继承宿主的接线见 docs/buffer/container-anchor-design.md §4（未实施）。</p>
     *
     * <p>⚠️ 本方法只回答「是谁/在哪」，<b>不回答「用哪个点」</b>—— 取点由调用方按用途决定
     * （射线要眼睛、应力要下方、爆炸要中心）。</p>
     */
    /**
     * <b>宿主</b>继承来的锚点 —— 只有物品形态容器有值，其余恒 {@code null}。
     *
     * <p>语义：「物品在箱子里，就在箱子那里」。这是<b>物理事实</b>，不是约定。</p>
     *
     * <p>⚠️ <b>默认不让功能用</b>：若 {@link #getAnchor()} 直接返回继承值，那么活工具会
     * 开始在背包里替你挖矿、活水车会变成随身发电机、活信标会变成随身光环 ——
     * 这些是**加强玩法**，不是修复，必须逐个拍板。</p>
     *
     * <p>⇒ 谁要用谁显式拿。目前只有<b>活 TNT 的爆炸点</b>用（
     * {@code ExplosionComponent#ignite} 取「自己的锚点，没有就用宿主的」）。</p>
     */
    @Nullable
    default ContainerAnchor getHostAnchor() {
        return null;
    }

    @Nullable
    default ContainerAnchor getAnchor() {
        BlockPos pos = getBlockPos();
        if (pos != null) return new ContainerAnchor.AtBlock(pos);
        Player owner = getOwnerPlayer();
        if (owner != null) return new ContainerAnchor.AtPlayer(owner);
        return null;
    }

    /**
     * 容器级持久数据的**持久宿主**列表（{@code IAttachmentHolder}：方块实体或实体）。
     *
     * <p>2026-10-21 实体容器（船/矿车/带箱驴骡）立项时加：流体 / 电力相位等容器级数据
     * 挂在附件上，而附件宿主原来只有 BE 一种。BE 与 Entity 在 NeoForge 里
     * <b>同为 {@code IAttachmentHolder}</b>（{@code getData}/{@code setData} 同签名）
     * ⇒ 领域侧的 writeback 只需问本方法要宿主列表，不必 {@code instanceof} 具体 ctx 类。</p>
     *
     * <p><b>为什么是列表</b>：大箱子的容器级数据每 tick 写给<b>两半</b> BE
     * （{@code ContainerFluidWriteback} 原实现逐个写）⇒ 返回列表而不是单个宿主。</p>
     *
     * <p>默认<b>空列表</b> = 「无持久宿主」：掉落物容器（单栈、无容器级数据）、
     * 测试替身之类。领域侧见空即跳过落盘。</p>
     *
     * <p>⚠️ 与 {@link #getOwnerPlayer()} 分工：后者答「数据归谁」（Player attachment
     * 的宿主是人），本方法答「数据存哪」（附件宿主是 BE 或实体）。</p>
     */
    default List<? extends IAttachmentHolder> getDataHosts() {
        return List.of();
    }

    /**
     * 本容器的<b>跨形态身份</b>（2026-10-22，活背包 / `D-container-13`）。
     *
     * <p>容器级数据按它在 SavedData 里索引 ⇒ 形态变化时数据零搬运，只让 UUID 跟着走。
     * 方块容器从 BE 附件读（{@code ContainerUuids.ofHost}），物品形态从物品组件读
     * （{@code ContainerUuids.ofItem}）。</p>
     *
     * <p><b>默认 {@code null} = 没有身份</b>：掉落物容器、测试替身、还没懒创建过的容器。
     * 领域侧见 null 就跳过 SavedData 路径（回落到附件 / 内存表）。</p>
     *
     * <p>⚠️ 与 {@link #getDataHosts()} 的分工：后者答「数据挂哪个附件」，
     * 本方法答「数据叫什么名字」。物品形态容器<b>没有附件宿主</b>（物品栈不是
     * {@code IAttachmentHolder}）却有 UUID ⇒ 两件事必须分开问。</p>
     */
    @Nullable
    default java.util.UUID getContainerUuid() {
        return null;
    }

    /**
     * 把某个容器级数据 key 的值<b>归档</b>到本宿主的持久层（`D-container-13`，2026-10-22）。
     *
     * <p>替代领域侧各自写 {@code for (host : getDataHosts()) host.setData(...)} ——
     * 「数据存哪」的知识从此收在 ctx 一处：</p>
     *
     * <table>
     *   <caption>各宿主的归档目标</caption>
     *   <tr><th>宿主</th><th>归档到</th></tr>
     *   <tr><td>方块容器 / 实体容器 / 玩家背包</td><td>{@link #getDataHosts()} 的附件（本 default 实现）</td></tr>
     *   <tr><td><b>物品形态容器</b>（活背包 / 将来的活潜影箱）</td><td><b>SavedData</b>（按容器 UUID 索引；ctx 覆写本方法）</td></tr>
     * </table>
     *
     * <p>⚠️ <b>领域不得假设有 BE</b>：拿不到 {@link IAttachmentHolder} <b>不是异常</b>，
     * 是一种正常宿主。判据是 {@code key.isSaved()}（attachment 与 codec 都声明了）——
     * 与 I-C9「只有带 {@code serialize} 的才搬」同源；应力 / 红石这类运行时数据
     * （{@code persistent} 无附件）在这里静默跳过。</p>
     *
     * <p>为什么不是 default 空实现：那样物品形态 ctx 忘了覆写就静默不落盘
     * （2026-10-22 设计评审记下的失效模式，与 {@code ContainerHandoff.Mover} 的
     * default 事故同族）⇒ 默认走附件路径，覆写是显意的。</p>
     */
    default <T> void persist(ContainerDataKey<T> key, T value) {
        if (!key.isSaved()) return;
        net.neoforged.neoforge.attachment.AttachmentType<T> att = key.attachment();
        if (att == null) return;
        for (var host : getDataHosts()) {
            host.setData(att, value);
        }
    }

    /** 方向编码：与红电 EDGE_* 一致（0=上 1=下 2=左 3=右） */
    int E_UP = 0;
    int E_DOWN = 1;
    int E_LEFT = 2;
    int E_RIGHT = 3;

    /**
     * 解析网格相邻槽位（dir 取 {@link #E_UP}/{@link #E_DOWN}/{@link #E_LEFT}/{@link #E_RIGHT}），
     * 越界或换行返回 -1。
     *
     * <p>非分配版本，供热路径（红电逐方向传播、流体逐方向扩散）按方向调用，
     * 替代原先各 domain 自行实现的同义解析器（如红电私有的 resolveSlot），统一到框架层。</p>
     */
    static int resolveNeighbor(int slot, int dir, int size, int width) {
        int col = slot % width;
        int row = slot / width;
        int newCol = col;
        int newRow = row;
        switch (dir) {
            case E_UP:    newRow = row - 1; break;
            case E_DOWN:  newRow = row + 1; break;
            case E_LEFT:  newCol = col - 1; break;
            case E_RIGHT: newCol = col + 1; break;
            default: return -1;
        }
        if (newCol < 0 || newCol >= width || newRow < 0) return -1;
        int result = newRow * width + newCol;
        return result < size ? result : -1;
    }

    static int[] getNeighbors(int slot, int containerSize, int width) {
        int[] result = new int[4];
        int count = fillNeighbors(result, slot, containerSize, width);
        return count == 4 ? result : java.util.Arrays.copyOf(result, count);
    }

    /**
     * 邻居写入调用方提供的缓冲，**返回个数**（2026-10-07 性能收尾）。
     *
     * <p>流体引擎的内层循环（扩散 / 生长 / 前沿反应 / 晋升邻源计数）每格都要取四邻，
     * 原实现每次 {@code new int[count]} ⇒ 万级容器下每拍百万量级的小数组分配。
     * 改为<b>每个相位一个复用缓冲</b>（每容器每拍 1 次分配，而非每格 1 次）⇒ ~200× 减少分配。</p>
     *
     * <p>⚠️ <b>不要用全局/静态共享缓冲</b>：晋升相位会在循环内嵌套调用
     * （{@code countSourceNeighbors}）⇒ 共享缓冲会被覆盖（别名 bug）。
     * 每个相位各自持有一个缓冲即无此问题。</p>
     *
     * @param out 长度 ≥4 的缓冲
     * @return 写入的邻居个数（0..4），有效值在 {@code out[0..count-1]}
     */
    static int fillNeighbors(int[] out, int slot, int containerSize, int width) {
        int i = 0;
        if (slot % width > 0) out[i++] = resolveNeighbor(slot, E_LEFT, containerSize, width);
        if ((slot + 1) % width != 0) out[i++] = resolveNeighbor(slot, E_RIGHT, containerSize, width);
        if (slot >= width) out[i++] = resolveNeighbor(slot, E_UP, containerSize, width);
        if (slot + width < containerSize) out[i++] = resolveNeighbor(slot, E_DOWN, containerSize, width);
        return i;
    }

    /**
     * 取指定位置的原版容器。
     *
     * <p><b>⚠ 大箱子陷阱</b>：本方法返回的是该位置<b>单个方块实体</b>的容器 ——
     * 大箱子（双箱合并）只会拿到<b>一个半箱（27 槽）</b>，而 {@code IItemHandler}
     * 能力返回的是<b>合并后的 54 槽</b>。两者的槽位编号体系<b>错位 27 格</b>
     * （Container 的槽 22 = GUI 的槽 49）。
     *
     * <p>因此任何「用本方法读/写某个<b>逻辑槽位</b>」的调用点，都必须先跑
     * {@code ContainerContexts#isSameSlotSpace} 那样的<b>体系一致性探针</b>
     * （槽位数一致 + 单槽交叉校验）才可安全使用，否则一律回退到 IItemHandler。
     * 典型受害者见 {@code SimpleContainerContext.simulateInsertItem}
     * （活漏斗在大箱子里静默不传输，living-hopper-tech.md §10.25）。</p>
     *
     * <p><b>为什么不在本方法里直接返回合并容器</b>：合并顺序由各 mod 的
     * IItemHandler 决定（未必等于 vanilla 的 {@code ChestBlock.getContainer} 顺序），
     * 猜错会引入<b>新的</b>错位；探针是自适应判据，对三方块 / 四块 / 任意多方块容器
     * 同样成立，无需知道容器结构。若将来确有场景必须拿到「合并后的原版容器」，
     * 建议做成注册式扩展点（内置 Chest 实现 + 第三方可注册），而不是在这里特判。</p>
     */
    static Container getContainer(Level level, BlockPos pos) {
        if (pos == null) return null;
        if (level.getBlockEntity(pos) instanceof Container c) return c;
        return null;
    }
}