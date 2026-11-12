package com.qiqi.li.living.container;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.UnaryOperator;

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

    /**
     * 回滚专用日志 —— 只在「恢复失败」时用。
     *
     * <p>回滚失败意味着<b>物品已经不一致</b>（通常表现为复制），必须留痕：
     * {@code I-C6}「降级必须可观测」，{@code I-C7}「不得静默继续」。
     * 与 {@code SlotAccessor.ROLLBACK_LOGGER} 同族，但入口不同（那边是传输引擎的兜底）。</p>
     */
    Logger ROLLBACK_LOGGER = LogUtils.getLogger();

    /**
     * 数量操作原语（{@link #move} / {@link #produce} / {@link #consume} / {@link #transform}）
     * 的降级日志 —— 只在「预算与实收不符」时用（WARN 级）。
     *
     * <p>与 {@link #ROLLBACK_LOGGER} 的分工：那边是「恢复失败 = 可能已复制」的 ERROR，
     * 这边是「差额已被正确记账、无复制面」的可观测留痕（`I-C6`：降级必须可观测）。</p>
     */
    Logger SLOT_OPS_LOGGER = LogUtils.getLogger();

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
     * {@code instanceof} 具体实现类</b>（2026-10-08 计划 ⑤ 补）。</p>
     */
    default Player getOwnerPlayer() {
        return null;
    }

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

    /**
     * 本容器住在<b>宿主的第几格</b> —— 只有物品形态容器有值，其余恒 {@code -1}。
     *
     * <p>语义：「我这个背包在玩家物品栏的第 10 格」。与 {@link #getHostAnchor()} 配套：
     * 锚点答「宿主是谁」，本方法答「我在宿主的哪一格」。</p>
     *
     * <p>⚠️ <b>为什么需要它</b>：一个宿主能装<b>多个</b>容器（玩家身上放两个活背包）。
     * 只按宿主做键，两个背包会撞成同一条记录、后写覆盖先写 ⇒ 客户端只渲染出一个
     * （2026-10-26 发现的 K2 上报覆盖）。槽位是它们的唯一区分：<b>一个槽位只能放一件物品</b>。</p>
     *
     * <p>⚠️ 不能用容器 UUID 代替：创造模式中键复制的两个背包<b>共享同一个 UUID</b>
     * （组件被一起复制），而它们必然在不同槽位。</p>
     */
    default int getHostSlot() {
        return -1;
    }

    /**
     * 本容器在世界里的<b>参考物</b>（{@link ContainerAnchor}）；没有则 {@code null}。
     *
     * <p>默认三选一：有方块位置 ⇒ {@code AtBlock}；有主人 ⇒ {@code AtPlayer}；否则 {@code null}。
     * <b>实体容器与掉落物必须覆写</b>（它们的 {@code getBlockPos()} 刻意是 null，
     * 且没有主人 —— 不覆写就会退化成「没有锚点」）。</p>
     *
     * <p>⚠️ 物品形态容器（活背包）默认 {@code null} 是<b>正确的</b>：它自己不在世界里，
     * 要靠宿主告诉它（继承值见 {@link #getHostAnchor()}，已实施 `D-container-15`）。</p>
     *
     * <p>⚠️ 本方法只回答「是谁/在哪」，<b>不回答「用哪个点」</b>—— 取点由调用方按用途决定
     * （射线要眼睛、应力要下方、爆炸要中心）。</p>
     */
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

        // ── 持久层二选一（2026-10-29 口径）──
        // 有身份（容器 UUID）⇒ 真身只有一份，按 UUID 存 SavedData。
        //   理由：身份是<b>跨形态稳定</b>的，方块↔物品切换时只有它跟得过去 ⇒ 数据跟着身份走，
        //   形态切换零搬运（`ContainerHandoff` 只需搬 UUID，不必搬数据）。
        //   ⚠️ 2026-10-29 之前这里只写附件，而物品形态写 SavedData ⇒ 方块形态期间的变化
        //   全留在附件里、随方块蒸发（真机：活背包拾取后活流体丢失）。
        // 无身份 ⇒ 只能跟方块走，写 BE 附件。
        java.util.UUID uuid = getContainerUuid();
        if (uuid != null) {
            ContainerDataSavedData data = ContainerDataSavedData.get();
            if (value == null || key.isEmpty(value)) {
                data.remove(uuid, key);
            } else {
                data.put(uuid, key, value);
            }
            return;
        }

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

    /**
     * 回滚一个槽到期望内容，并<b>校验回滚本身</b>。
     *
     * <p>⭐ 为什么回滚也要校验：后端在「写入」上违约时，<b>回滚同样是写入</b>
     * ⇒ 它也可能失败。失败后槽里留着不该有的东西，表现通常是<b>物品复制</b>，
     * 而且完全静默。{@code BackendViolation} 已经是"出了事"，但"出事之后连补救都没成"
     * 必须升级为可见的 ERROR（`I-C6` / `I-C7`）。</p>
     *
     * <p>⚠️ 到这里已经无法自动恢复（后端不老实是外部原因）—— 目标是<b>丢必留痕</b>，
     * 让排查者知道"哪一格、期望什么、实际什么"，而不是"物品莫名其妙多了"。</p>
     */
    static void rollbackSlot(ContainerContext ctx, int slot, ItemStack expected, String why) {
        ctx.setItem(slot, expected);
        ItemStack actual = ctx.getItem(slot);
        if (!ItemStack.matches(actual, expected)) {
            ROLLBACK_LOGGER.error(
                "槽位回滚失败（{}）：槽位 {} 期望 {}，实际 {} —— 可能已复制，需人工核查",
                why, slot, expected, actual);
        }
    }

    // ==================== 一步整栈移动（I-C12，2026-11-03） ====================
    //
    // 三个方法构成「同容器精确位移」的最小能力。设计稿见 buffer/move-api-minimal.md。
    // 与 SlotAccessor 的分工：**共享数量内核，不共享入口** —— SlotAccessor 服务传输引擎
    // 的跨容器/过滤器场景，这里服务同容器精确位移（活塞）。不要为复用把后者拖进装饰器体系。

    /**
     * 移动成功后的通知钩子 —— 实现方据此把 {@code from} / {@code to} 登记进本 tick 的
     * 「已移动槽」集合（见 {@code TickContext#movedSlots}）。
     *
     * <p>默认空实现：不参与 tick 编排的上下文（测试替身 / 无 TickContext）不需要登记。</p>
     *
     * <p>⭐ <b>为什么必须登记</b>：扫描阶段的槽→物品映射在移动之后已经过期。
     * 不登记 ⇒ 同 tick 内后续功能仍按旧 entry 操作那个槽（对着空槽执行），
     * 或读到属于上一台机器的运行时数据。</p>
     */
    default void onSlotsMoved(int from, int to) { }

    /**
     * 这个槽在本 tick 内是否被移动过 —— 用于让旧信息（SlotEntry / runtime）失效。
     *
     * <p>默认 {@code false}（没有 tick 概念的上下文永远「没被移动」）。</p>
     */
    default boolean isSlotMovedThisTick(int slot) {
        return false;
    }

    /**
     * 预检查：目标槽能否<b>完整接收整栈</b>（准入 + 容量）。<b>不改任何状态</b>。
     *
     * <p>{@link #moveStack} 执行时仍会重新验证 —— 预检与执行之间容器可能已被改动。</p>
     */
    default boolean canAcceptAll(int to, ItemStack stack) {
        if (!isValidSlot(to) || stack.isEmpty()) return false;
        return simulateInsertItem(to, stack) >= stack.getCount();
    }

    /**
     * 「假定槽位清空后能否完整接纳整栈」（准入 + 容量）—— {@link #swap} 的配套预检。
     *
     * <p>与 {@link #canAcceptAll} 的差异：那边走 {@link #simulateInsertItem}，槽位非空时按
     * 「现有内容之上合并」作答（异类直接拒绝）—— 表达不了「槽内原有物品即将让位」的互换场景。
     * 本判据 = 纯准入 + 数量上界（与写入截断口径同源）；实现若还有 simulateInsertItem
     * 独有的准入桥接（如原版 {@code Container.canPlaceItem}），应覆写本方法补齐。</p>
     */
    default boolean canFullyReceive(int slot, ItemStack stack) {
        if (!isValidSlot(slot) || stack.isEmpty()) return false;
        return isItemValid(slot, stack)
            && stack.getCount() <= Math.min(getSlotLimit(slot), stack.getMaxStackSize());
    }

    /**
     * 一步整栈移动：把 {@code from} 槽的全部物品移到 {@code to} 槽。
     *
     * <p><b>语义</b>：整栈、不拆数量、不合并（目标必须为空）。成功时
     * {@code Success.moved} 恒等于操作开始时的源栈数量。</p>
     *
     * <p><b>不变量（I-C12）</b>：成功时两式同时成立 ——</p>
     * <pre>
     * after(from) == before(from) - moved
     * after(to)   == before(to)   + moved
     * </pre>
     * <p>⇒ 写目标后<b>必须回读核验</b>，核验通过才清源；源清不掉则撤销目标（防复制）。
     * 这不是防御性编程，是 `I-C12` 的判据：只核验目标增量不替代核验源扣减。</p>
     *
     * <p><b>不发布中间状态</b>：{@link ContainerSync#syncSlotToClients} 在 tick 内只登记脏槽，
     * 真正发包在 tick 末的 {@code flushDirtySlots}（读最终值）⇒ 调用方在单 tick 内完成动作即可。</p>
     *
     * @param from 源槽（逻辑槽号）
     * @param to   目标槽（逻辑槽号）
     */
    default MoveResult moveStack(int from, int to) {
        if (!isValidSlot(from) || !isValidSlot(to) || from == to) {
            return new MoveResult.Rejected(MoveResult.Reason.INVALID_ADDRESS);
        }
        ItemStack source = getItem(from);
        if (source.isEmpty()) {
            return new MoveResult.Rejected(MoveResult.Reason.SOURCE_EMPTY);
        }
        ItemStack target = getItem(to);
        if (!target.isEmpty()) {
            return new MoveResult.Rejected(MoveResult.Reason.TARGET_OCCUPIED);
        }
        int amount = source.getCount();

        // I-C12 三维度必须逐维回答：容量（canAcceptAll 内含准入）+ 抽取权限 + 写入能力。
        // ⚠️ 漏掉抽取权限 = 把「只进不出」的槽（输出槽 / 过滤槽）里的货搬走；
        //    漏掉写入能力判别 = 把「永远不可能成功」说成「后端违约」，排查方向全错。
        if (!supportsExactWrite(from) || !supportsExactWrite(to)) {
            return new MoveResult.Unsupported(MoveResult.Reason.EXACT_WRITE_UNSUPPORTED);
        }
        if (simulateExtractItem(from, amount) < amount) {
            return new MoveResult.Rejected(MoveResult.Reason.SOURCE_NOT_EXTRACTABLE);
        }
        if (!canAcceptAll(to, source)) {
            // 无法区分「准入拒绝」与「容量不足」时统一报这档，不猜（见 MoveResult.Reason）
            return new MoveResult.Rejected(MoveResult.Reason.TARGET_INSUFFICIENT);
        }

        // ① 写目标（整栈覆盖，走 I-C3 的 setStackInSlot 路径，不被整库重定向挪槽）
        setItem(to, source.copy());
        ItemStack afterTarget = getItem(to);
        if (!ItemStack.matches(afterTarget, source)) {
            rollbackSlot(this, to, target, "目标写入不符（期望 " + amount + "，实际 "
                + afterTarget.getCount() + "）");
            return new MoveResult.BackendViolation(
                "目标写入不符：期望 " + amount + "，实际 " + afterTarget.getCount());
        }

        // ② 核验通过后才清源；源清不掉 ⇒ 撤销目标，绝不留下「两份」
        setItem(from, ItemStack.EMPTY);
        ItemStack afterSource = getItem(from);
        if (!afterSource.isEmpty()) {
            rollbackSlot(this, to, target, "源槽未能清空（残留 " + afterSource.getCount() + "）");
            return new MoveResult.BackendViolation(
                "源槽未能清空（残留 " + afterSource.getCount() + "）—— 已撤销目标写入");
        }

        syncSlotToClients(from, afterSource);
        syncSlotToClients(to, afterTarget);
        onSlotsMoved(from, to);
        return new MoveResult.Success(amount, new UndoToken(from, to, source, target, amount));
    }

    /**
     * 撤销一步移动（{@link #moveStack} 的逆操作）。
     *
     * <p><b>先校验、后恢复</b>：只有当两槽仍处于本步的<b>预期结果</b>时才执行，
     * 否则返回 {@code false} 且<b>不覆盖</b>。</p>
     *
     * <p>⚠️ <b>不能假设「同 tick 内没人动过」</b>（2026-11-03 活塞侧指出）：setter 会同步触发
     * 监听器，第三方逻辑可能响应并改变内容。校验不能省，也不能简化成「反正没人动」。</p>
     *
     * @return true = 已恢复到移动前状态；false = 未恢复（状态已被改动 / 地址失效 / 恢复后校验失败）
     */
    default boolean undo(UndoToken token) {
        if (token == null) return false;
        int from = token.from();
        int to = token.to();
        if (!isValidSlot(from) || !isValidSlot(to)) return false;

        ItemStack expectedSource = token.expectedSourceAfter();
        ItemStack expectedTarget = token.expectedTargetAfter();
        if (!ItemStack.matches(getItem(from), expectedSource)
                || !ItemStack.matches(getItem(to), expectedTarget)) {
            return false;   // 已被第三方改动 ⇒ 不覆盖（覆盖等于第二次破坏）
        }

        setItem(from, token.sourceBefore().copy());
        setItem(to, token.targetBefore().copy());

        boolean restored = ItemStack.matches(getItem(from), token.sourceBefore())
                        && ItemStack.matches(getItem(to), token.targetBefore());
        if (!restored) {
            // 回滚也是写入 ⇒ 它同样可能失败。失败 = 链没能恢复，必须留痕（I-C6 / I-C7）
            ROLLBACK_LOGGER.error(
                "undo 恢复失败：from={} 期望 {} 实际 {}；to={} 期望 {} 实际 {}",
                from, token.sourceBefore(), getItem(from),
                to, token.targetBefore(), getItem(to));
        }
        syncSlotToClients(from, getItem(from));
        syncSlotToClients(to, getItem(to));
        if (restored) onSlotsMoved(from, to);   // 撤销同样是移动 ⇒ 旧信息同样过期
        return restored;
    }

    /**
     * 一步整栈互换：两槽内容对调 —— 活魂符「换位」动词的槽位原语（2026-11-12 拍板，
     * 设计稿见 {@code buffer/活魂符换位玩法设计.md}）。
     *
     * <p><b>语义</b>：双方非空、整栈互换、不拆分不合并（一空一非空走 {@link #moveStack}）。
     * 成功时 {@code Success.moved} = 移入 {@code to} 的数量（即原 {@code from} 栈数量）。
     * <b>不返回撤销凭据</b>：swap 的逆操作就是再次 swap（成功路径零参数可逆），
     * 与 {@link UndoToken}「清源填目标」的单向快照语义不匹配。</p>
     *
     * <p><b>I-C12 三维度逐维回答</b>：容量与准入用 {@link #canFullyReceive}（互换场景
     * 两槽都被占用，{@link #canAcceptAll} 的「现有内容之上合并」模拟不适用）；
     * 抽取权限双向必查；精确写双向必查（整栈覆盖语义，与 moveStack 同档）。</p>
     *
     * <p><b>验证式原子</b>：写入后逐槽回读，任一步不符即恢复双槽到操作前快照并报
     * {@code BackendViolation}；末尾再做一次双槽终验，防「后续写入引发回调改动先前槽」。</p>
     *
     * <p>活物品隔离由<b>调用方</b>把关（与 {@link #move} 同责）：内核不认「活物品」概念。</p>
     */
    default MoveResult swap(int from, int to) {
        if (!isValidSlot(from) || !isValidSlot(to) || from == to) {
            return new MoveResult.Rejected(MoveResult.Reason.INVALID_ADDRESS);
        }
        ItemStack source = getItem(from).copy();   // 即将移入 to；快照防 setItem 覆盖抽干活引用
        ItemStack target = getItem(to).copy();     // 即将移入 from
        if (source.isEmpty() || target.isEmpty()) {
            return new MoveResult.Rejected(MoveResult.Reason.SOURCE_EMPTY);
        }
        if (!supportsExactWrite(from) || !supportsExactWrite(to)) {
            return new MoveResult.Unsupported(MoveResult.Reason.EXACT_WRITE_UNSUPPORTED);
        }
        if (simulateExtractItem(from, source.getCount()) < source.getCount()
                || simulateExtractItem(to, target.getCount()) < target.getCount()) {
            return new MoveResult.Rejected(MoveResult.Reason.SOURCE_NOT_EXTRACTABLE);
        }
        if (!canFullyReceive(to, source) || !canFullyReceive(from, target)) {
            return new MoveResult.Rejected(MoveResult.Reason.TARGET_INSUFFICIENT);
        }

        // ① 写 to ← source；回读不符 → 恢复 to（from 未动，无需恢复）
        setItem(to, source.copy());
        if (!ItemStack.matches(getItem(to), source)) {
            rollbackSlot(this, to, target, "swap 目标写入不符（期望 " + source.getCount() + "）");
            syncSlotToClients(to, getItem(to));
            return new MoveResult.BackendViolation("swap: to 槽写入被重定向（槽位 " + to + "）");
        }
        // ② 写 from ← target；from 此刻的期望内容就是 target（未被动过），回读不符 ⇒ 双槽恢复
        setItem(from, target.copy());
        if (!ItemStack.matches(getItem(from), target)) {
            rollbackSlot(this, from, source, "swap 源写入不符（恢复 from）");
            rollbackSlot(this, to, target, "swap 源写入不符（恢复 to）");
            syncSlotToClients(from, getItem(from));
            syncSlotToClients(to, getItem(to));
            return new MoveResult.BackendViolation("swap: from 槽写入不符（槽位 " + from + "）—— 已恢复双槽");
        }
        // ③ 终验：② 的写入可能经 setter 回调改动 to；不符即恢复双槽
        if (!ItemStack.matches(getItem(to), source)) {
            rollbackSlot(this, to, target, "swap 终验不符（恢复 to）");
            rollbackSlot(this, from, source, "swap 终验不符（恢复 from）");
            syncSlotToClients(from, getItem(from));
            syncSlotToClients(to, getItem(to));
            return new MoveResult.BackendViolation("swap: 终验发现 to 槽被回调改动 —— 已恢复双槽");
        }
        if (!ItemStack.matches(getItem(from), target)) {
            rollbackSlot(this, to, target, "swap 终验不符（恢复 to）");
            rollbackSlot(this, from, source, "swap 终验不符（恢复 from）");
            syncSlotToClients(from, getItem(from));
            syncSlotToClients(to, getItem(to));
            return new MoveResult.BackendViolation("swap: 终验发现 from 槽被回调改动 —— 已恢复双槽");
        }

        syncSlotToClients(from, getItem(from));
        syncSlotToClients(to, getItem(to));
        onSlotsMoved(from, to);
        return new MoveResult.Success(source.getCount(), null);
    }

    // ==================== 槽位数量操作原语（I-C12 契约化，2026-11-05） ====================
    //
    // 四个原语把「数量增减 = 先写、再无条件扣源」收编成唯一入口（TODO「物品传输」1-②③）：
    //   move      槽→槽搬运（允许部分交付与合并，两槽守恒由内核强制）
    //   produce   虚拟源→槽（产物放置：熔炉产出 / 耕地掉落 / 酿造残留物）
    //   consume   槽→虚空（输入消费：熔炉 / 酿造的材料与燃料）
    //   transform 槽内原地替换（酿造瓶槽一类「不可拆整槽变换」，I-C13）
    //
    // 与 {@link #moveStack} 的分工：moveStack 是活塞的「整栈纯位移」（不拆、不合并、
    // 要求精确写）；move 是「按数量、可合并、允许部分交付」的通用搬运。
    // 与 SlotAccessor 的分工不变（见上方「共享数量内核，不共享入口」）。

    /**
     * 按数量移动：把至多 {@code amount} 个物品从 {@code from} 搬到 {@code to}，
     * 等价于 {@code move(from, to, amount, MovePolicy.STANDARD)}。
     */
    default MoveResult move(int from, int to, int amount) {
        return move(from, to, amount, MovePolicy.STANDARD);
    }

    /**
     * 按数量移动（指定口径）—— 允许<b>部分交付</b>（{@code Success.moved ≤ amount}）与
     * <b>同类合并</b>（目标非空时必须与源同物品同组件）。
     *
     * <p><b>不变量（I-C12）</b>：成功时两式同时成立 ——</p>
     * <pre>
     * after(from) == before(from) - moved
     * after(to)   == before(to)   + moved
     * </pre>
     * <p>⇒ 写目标后回读只认真实增量、<b>只扣实际进入量</b>；源扣减不成立 ⇒
     * 整拍撤销目标写入（目标快照先于写入 —— {@code setItem} 覆盖语义会抽干活引用）。
     * 目标写入被容器重定向成别的东西时同样恢复原状。</p>
     *
     * <p><b>与 {@link #moveStack} 的语义差异</b>：那边「要么全成要么不动」（{@code moved}
     * 恒等于源栈数量、目标必须空）；这边差额方向只会是「少搬」（预算钳住上限），
     * 少搬的部分原样留在源槽。不设 {@code EXACT_WRITE} 门：{@code setItem} 的回退路径
     * + 回读核验足够，无精确写的后端照样能走通。</p>
     *
     * <p>活物品隔离由<b>调用方</b>把关（与 SlotAccessor 入口同责）：内核不认「活物品」概念。</p>
     *
     * @param amount 请求搬运量（≤0 按 {@code INVALID_ADDRESS} 拒绝）
     * @param policy 容量/权限口径，见 {@link MovePolicy}
     */
    default MoveResult move(int from, int to, int amount, MovePolicy policy) {
        if (!isValidSlot(from) || !isValidSlot(to) || from == to || amount <= 0) {
            return new MoveResult.Rejected(MoveResult.Reason.INVALID_ADDRESS);
        }
        ItemStack source = getItem(from);
        if (source.isEmpty()) {
            return new MoveResult.Rejected(MoveResult.Reason.SOURCE_EMPTY);
        }
        ItemStack target = getItem(to);
        if (!target.isEmpty() && !ItemStack.isSameItemSameComponents(source, target)) {
            return new MoveResult.Rejected(MoveResult.Reason.TARGET_OCCUPIED);
        }

        // 抽取权限（STANDARD）：容器不让取的货不得搬走。PHYSICAL 不问（A3，遗留②未拍板）。
        int extractable;
        if (policy == MovePolicy.STANDARD) {
            extractable = Math.min(amount, simulateExtractItem(from, amount));
            if (extractable <= 0) {
                return new MoveResult.Rejected(MoveResult.Reason.SOURCE_NOT_EXTRACTABLE);
            }
        } else {
            extractable = Math.min(amount, source.getCount());
        }

        // 容量预算：STANDARD 问容器（I-C14①，含准入）；PHYSICAL 只问槽位上限（A3）。
        int planned;
        if (policy == MovePolicy.PHYSICAL) {
            long space = (long) getSlotLimit(to) - target.getCount();
            if (space <= 0) {
                return new MoveResult.Rejected(MoveResult.Reason.TARGET_INSUFFICIENT);
            }
            planned = (int) Math.min(extractable, space);
        } else {
            int budget = simulateInsertItem(to, source.copyWithCount(extractable));
            if (budget <= 0) {
                // 异类占用已在上方挡掉 ⇒ 这里是准入拒绝或容量 0；统一报这档，不猜
                return new MoveResult.Rejected(MoveResult.Reason.TARGET_INSUFFICIENT);
            }
            planned = Math.min(extractable, budget);
        }

        // 撤销快照：setItem 是覆盖语义，回退必须用副本
        ItemStack targetSnapshot = target.copy();

        // ① 写目标（空槽覆盖 / 同类合并）
        ItemStack expectedTarget = target.isEmpty()
            ? source.copyWithCount(planned)
            : target.copyWithCount(target.getCount() + planned);
        setItem(to, expectedTarget);
        ItemStack afterTarget = getItem(to);
        int actual;
        if (ItemStack.matches(afterTarget, expectedTarget)) {
            actual = planned;
        } else if (!afterTarget.isEmpty() && ItemStack.isSameItemSameComponents(afterTarget, source)) {
            // 少放不回滚：放入部分是真移动，只扣实际量（差额方向只会是少搬，无复制面）
            actual = Math.max(0, afterTarget.getCount() - target.getCount());
            if (actual < planned) {
                SLOT_OPS_LOGGER.warn("move: 目标槽 {} 计划写入 {}，实际 {} —— 源槽只扣实际量",
                    to, planned, actual);
            }
        } else {
            rollbackSlot(this, to, targetSnapshot,
                "目标写入被重定向（实际 " + afterTarget + "）");
            syncSlotToClients(to, getItem(to));
            return new MoveResult.BackendViolation("目标写入被重定向（槽位 " + to + "）");
        }
        if (actual <= 0) {
            return new MoveResult.BackendViolation(
                "目标写入零落（计划 " + planned + "，容器 simulate 与实写不符）");
        }

        // ② 源侧只扣实际进入量；扣减不成立 ⇒ 目标已增 + 源未减 = 复制 ⇒ 整拍撤销
        ItemStack remaining = actual >= source.getCount()
            ? ItemStack.EMPTY
            : source.copyWithCount(source.getCount() - actual);
        setItem(from, remaining);
        ItemStack afterSource = getItem(from);
        if (!ItemStack.matches(afterSource, remaining)) {
            rollbackSlot(this, to, targetSnapshot,
                "源槽 " + from + " 写回不符（期望余 " + remaining.getCount()
                    + "，实际 " + afterSource.getCount() + "）");
            syncSlotToClients(from, getItem(from));
            syncSlotToClients(to, getItem(to));
            return new MoveResult.BackendViolation(
                "源槽写回不符（期望余 " + remaining.getCount() + "，实际 "
                    + afterSource.getCount() + "）—— 已撤销目标写入");
        }

        syncSlotToClients(from, afterSource);
        syncSlotToClients(to, afterTarget);
        onSlotsMoved(from, to);
        return new MoveResult.Success(actual, new UndoToken(from, to, source, target, actual));
    }

    /**
     * 产物放置：把至多 {@code amount} 个 {@code prototype} 放入槽（空槽或同类合并），
     * 返回<b>实际放入量</b>。来源是配方 / 掉落等虚拟源，没有源槽。
     *
     * <p><b>预算 = {@code simulateInsertItem}</b>（`I-C14①` 容量权威，含准入）——
     * 调用方（熔炉 {@code calculateOutputSpace} / 耕地 / 酿造 {@code placeRemainder}）
     * 的产量预算已经用同一口径，写入与预算从此一致。</p>
     *
     * <p><b>差额只 WARN 不回滚</b>：差额只可能来自容器 simulate 撒谎（说能收 N、实收 M&lt;N）；
     * 已放入的 M 个是真产物，回滚反而销毁；无源槽 ⇒ 无复制面（差额方向只会是少产出，
     * 预算钳住上限）。WARN = `I-C6` 丢必留痕。写入被重定向成别的东西时恢复原状按零处理。</p>
     *
     * @return 实际放入量（0 = 没放进去；余量归调用方处置）
     */
    default int produce(int slot, ItemStack prototype, int amount) {
        if (!isValidSlot(slot) || prototype.isEmpty() || amount <= 0) return 0;
        int budget = simulateInsertItem(slot, prototype.copyWithCount(amount));
        if (budget <= 0) return 0;
        int planned = Math.min(amount, budget);

        ItemStack target = getItem(slot);
        ItemStack expected = target.isEmpty()
            ? prototype.copyWithCount(planned)
            : target.copyWithCount(target.getCount() + planned);
        setItem(slot, expected);
        ItemStack after = getItem(slot);
        int actual;
        if (ItemStack.matches(after, expected)) {
            actual = planned;
        } else if (!after.isEmpty() && ItemStack.isSameItemSameComponents(after, prototype)) {
            actual = Math.max(0, after.getCount() - target.getCount());
            if (actual < planned) {
                SLOT_OPS_LOGGER.warn("produce: 槽位 {} 计划放入 {}×{}，实际 {} —— 余量 {} 个未落地",
                    slot, planned, prototype.getItem(), actual, planned - actual);
            }
        } else {
            rollbackSlot(this, slot, target,
                "produce 写入被重定向（实际 " + after + "）");
            syncSlotToClients(slot, getItem(slot));
            return 0;
        }
        syncSlotToClients(slot, after);
        return actual;
    }

    /**
     * 输入消费：从槽扣至多 {@code maxUnit} 个，返回<b>实际扣减量</b>。来源槽 → 虚空
     * （被配方消耗），没有目标槽。
     *
     * <p><b>查抽取权限</b>（{@code simulateExtractItem}，`I-C12` 第三维）：容器不让取的货
     * 功能不得私吞 —— 对齐 {@link #moveStack} 先例。</p>
     *
     * <p><b>写回不符 ⇒ 按「没扣到」处理</b>：恢复原状返回 0，调用方本拍不推进。
     * consume 无目标槽，坏方向只有「少扣」，不存在复制面；恢复原状是为了不留下
     * 「扣了一半」的中间态（`I-C13`：写回不得覆盖未参与本次操作的数量）。</p>
     *
     * @return 实际扣减量（0 = 一件没扣到，槽原样）
     */
    default int consume(int slot, int maxUnit) {
        if (!isValidSlot(slot) || maxUnit <= 0) return 0;
        ItemStack source = getItem(slot);
        if (source.isEmpty()) return 0;
        int planned = Math.min(maxUnit, source.getCount());
        planned = Math.min(planned, simulateExtractItem(slot, planned));
        if (planned <= 0) return 0;

        int left = source.getCount() - planned;
        ItemStack expected = left <= 0 ? ItemStack.EMPTY : source.copyWithCount(left);
        setItem(slot, expected);
        ItemStack after = getItem(slot);
        if (!ItemStack.matches(after, expected)) {
            SLOT_OPS_LOGGER.warn("consume: 槽位 {} 写回不符（期望余 {}，实际 {}）—— 按「没扣到」处理",
                slot, left, after.getCount());
            rollbackSlot(this, slot, source, "consume 写回不符");
            syncSlotToClients(slot, getItem(slot));
            return 0;
        }
        syncSlotToClients(slot, after);
        return planned;
    }

    // ==================== 原数量组件提交 ====================

    /** 组件写回能力，默认不支持；不沿用数量操作的抽插回退。 */
    default boolean supportsItemComponentWrite(int slot) {
        return false;
    }

    /** 直接覆盖接缝，仅供组件提交及恢复使用；不截断、不抽插、不发布同步。 */
    default boolean trySetItemExact(int slot, ItemStack stack) {
        return false;
    }

    /** 已观察到组件写入或恢复后的通知，发布最终槽状态。 */
    default void onItemComponentsChanged(int slot, ItemStack stack) {
        syncSlotToClients(slot, stack);
    }

    /**
     * 原物品、原数量的组件提交：独立快照、旧值重验、精确写回与回读确认。
     * 不支持或旧值已变时不写；部分写坏仅尝试精确恢复，恢复失败必须留痕。
     * 返回 true 才可按新状态交付资源；本方法不修改调用方快照。
     */
    default boolean commitItemComponents(int slot, ItemStack before, ItemStack after) {
        if (!isValidSlot(slot) || before == null || after == null
                || !supportsItemComponentWrite(slot)) return false;
        ItemStack original = before.copy();
        ItemStack expected = after.copy();
        if (original.isEmpty() || expected.isEmpty()
                || original.getItem() != expected.getItem()
                || original.getCount() != expected.getCount()) return false;
        ItemStack current = getItem(slot).copy();
        if (!ItemStack.matches(current, original)) return false;
        if (ItemStack.matches(expected, original)) return true;

        boolean written = attemptComponentWrite(slot, expected);
        ItemStack actual = getItem(slot).copy();
        if (written && ItemStack.matches(actual, expected)) {
            onItemComponentsChanged(slot, actual);
            return true;
        }
        SLOT_OPS_LOGGER.warn("组件提交失败：槽位 {}，未确认期望状态，停止结算", slot);
        if (!ItemStack.matches(actual, original)) {
            attemptComponentWrite(slot, original);
            actual = getItem(slot).copy();
            if (!ItemStack.matches(actual, original)) {
                ROLLBACK_LOGGER.error("组件提交恢复失败：槽位 {} 期望 {}，实际 {} —— 需人工核查",
                    slot, original, actual);
            }
            onItemComponentsChanged(slot, actual);
        }
        return false;
    }

    private boolean attemptComponentWrite(int slot, ItemStack expected) {
        try {
            return trySetItemExact(slot, expected.copy());
        } catch (RuntimeException e) {
            SLOT_OPS_LOGGER.warn("组件写入异常：槽位 {}，将核验实际内容", slot, e);
            return false;
        }
    }

    /**
     * 原地整槽替换 —— 「不可拆整槽变换」（`I-C13`：一槽一瓶，要么整槽成要么不动）。
     * 供酿造瓶槽一类「把 A 换成 B、数量不增减」的变换使用。
     *
     * <p>{@code fn} 收到的是<b>副本</b>，不得改活引用（{@code setItem} 覆盖语义会抽干
     * 槽内活引用；变换结果必须来自 fn 的返回值而不是原地修改）。</p>
     *
     * <p>写后回读不符 ⇒ 还原并返回 {@code false}，调用方按「本槽没换成」处理
     * （酿造 = 整批中止不扣材料）。</p>
     *
     * @return true = 已替换（或 fn 判定无需变化）；false = 未替换（槽原样）
     */
    default boolean transform(int slot, UnaryOperator<ItemStack> fn) {
        if (!isValidSlot(slot)) return false;
        ItemStack before = getItem(slot);
        ItemStack expected = fn.apply(before.copy());
        if (ItemStack.matches(expected, before)) return true;   // fn 判定无需变化
        setItem(slot, expected);
        ItemStack after = getItem(slot);
        if (!ItemStack.matches(after, expected)) {
            rollbackSlot(this, slot, before,
                "transform 写入不符（期望 " + expected + "，实际 " + after + "）");
            syncSlotToClients(slot, getItem(slot));
            return false;
        }
        syncSlotToClients(slot, after);
        return true;
    }
}