package com.qiqi.li.living.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.living.domain.ender.LivingEnderChestFunction;
import com.qiqi.li.living.domain.farmland.FarmlandBonemealInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 槽位交互注册表 + 统一分发器 —— 活漏斗「货物 × 目标槽」替代语义的唯一入口。
 *
 * <h3>调用点只有两处，且与具体交互无关</h3>
 * <table>
 *   <tr><th>场景</th><th>入口</th><th>货物已知？</th></tr>
 *   <tr><td>容器内 / 跨容器推送 / 邻居间直传</td>
 *       <td>{@link #tryInteract(SlotAccessor, ItemStack, ItemStack, Level)}</td>
 *       <td>是（源槽或模拟提取结果）</td></tr>
 *   <tr><td>跨容器拉取（源在邻居容器）</td>
 *       <td>{@link #tryInteractFromNeighbor(IItemHandler, BlockPos, ItemStack, Level, FilterData)}</td>
 *       <td>否（需遍历邻居槽位找匹配的货物）</td></tr>
 * </table>
 *
 * <p>三处传输分支（{@code TransferPipeline.executeInContainer}、
 * {@code CrossContainerTransfer.tryPushToNeighbor}、{@code pullFromNeighbor}）
 * 都只调上面两个方法之一——<b>新增交互零调用点改动</b>。这是对 2026-09-15
 * 「拉取方向漏施肥分支」那次 bug 的结构性修复：漏一处调用点的机会从
 * 「每个新交互一次」降为「每个调用点一次」，且已有单测逐方向钉住。</p>
 *
 * <h3>协议</h3>
 * 模拟优先（{@code simulateExtract} 试算 → 生效 → 真 {@code extract} 扣货），
 * 与 GUI 活骨粉右键、容器内管道、跨容器两方向完全同款：交互不生效则
 * <b>不扣货、不设冷却</b>，调用方继续走通用插入。
 *
 * <p><b>货物准入</b>：两个入口都先过 {@link #isEligibleCargo}——活物品不作货物
 * （活箱子/活末影箱除外）。施肥属传输语义，故<b>活骨粉（活物品）不会被漏斗施肥</b>；
 * 活骨粉的手动用途在 GUI 右键（那里本就要求活化）。</p>
 *
 * <h3>扩展方式</h3>
 * <pre>{@code
 * // 1. 实现 SlotInteraction（matches 纯谓词 + interact 就地改 target + equals 零空转）
 * // 2. 注册（内置条目在下方静态块，模组自己的可放 commonSetup）
 * SlotInteractions.register(new MyInteraction());
 * }</pre>
 */
public final class SlotInteractions {

    /** 注册的交互条目（按注册顺序匹配，先注册者优先——同一组合不应注册多条）。 */
    private static final List<SlotInteraction> INTERACTIONS =
        Collections.synchronizedList(new ArrayList<>());

    static {
        // 内置：骨粉 → 活耕地 = 施肥（只认普通骨粉——活物品不作货物，见 isEligibleCargo）
        register(new FarmlandBonemealInteraction());
    }

    private SlotInteractions() {}

    /**
     * 注册一条槽位交互。重复注册同一组合时先注册者优先（调用方无需去重）。
     */
    public static void register(SlotInteraction interaction) {
        INTERACTIONS.add(interaction);
    }

    /**
     * <b>活漏斗的货物准入规则（唯一定义点）</b>：活物品不作货物——活箱子/活末影箱除外
     * （它们是存储容器，本身可被搬运）。
     *
     * <p>这是「活物品隔离」在传输层的表述：活物品不被其它活物品当普通物品处理
     * （不传输、不熔炼、不作为燃料）。传输层（{@code TransferPipeline.isTransferableSource}）
     * 与交互层（本类的两个入口）<b>共用这一份定义</b>，避免规则两处漂移。</p>
     *
     * <p><b>为什么交互层也要过这道门</b>（2026-09-15 用户定案）：施肥的语义是
     * 「活漏斗用<b>传输能力</b>把骨粉送进活耕地」——它是传输语义，因此必须受漏斗
     * 自己的货物规则约束。活骨粉是活物品 ⇒ 不是合法货物 ⇒ 漏斗不给它施肥
     * （活骨粉的手动用武之地在 GUI 右键，那里本就要求活化）。</p>
     */
    public static boolean isEligibleCargo(ItemStack stack) {
        if (!LivingItemManager.isLivingItem(stack)) return true;
        return LivingChestFunction.isLivingChest(stack)
            || LivingEnderChestFunction.isLivingEnderChest(stack);
    }

    /**
     * 纯谓词查询：是否存在能接管该组合的交互。
     *
     * <p>无副作用、不创建 Accessor。真正的分配节省在<b>拉取方向</b>
     * （{@link #tryInteractFromNeighbor}）：它在建邻居槽 Accessor <b>之前</b>筛，
     * 绝大多数组合不匹配，每轮最多省 27 次（邻居槽数）。容器内路径
     * （{@code TransferPipeline}）的源槽 Accessor 更早创建、且下方通用路径要复用，
     * 故那里的调用只是廉价早退，不省分配。</p>
     *
     * <p>已内建货物准入（{@link #isEligibleCargo}）：非法货物（活物品，非箱类）恒 false。</p>
     */
    public static boolean canInteract(ItemStack cargo, ItemStack target) {
        if (cargo.isEmpty() || target.isEmpty()) return false;
        if (!isEligibleCargo(cargo)) return false;
        for (SlotInteraction interaction : INTERACTIONS) {
            if (interaction.matches(cargo, target)) return true;
        }
        return false;
    }

    /**
     * 已知货物：源 Accessor + 货物栈 + 目标槽物品。
     *
     * <p>用于容器内管道、跨容器推送、邻居间直传（三者货物都是现成的）。
     * 返回 true 表示某条交互已生效并扣货——调用方应同步涉及槽位并结束本轮传输
     * （漏斗 tick 自然设冷却：一次交互 = 一次传输）。</p>
     *
     * <p>调用方若需避免无谓的 Accessor 分配，可先用 {@link #canInteract} 做廉价筛选。</p>
     *
     * @param source 源槽 Accessor（承载模拟优先协议；过滤器已在其内部生效）
     * @param cargo  源槽货物（真实槽位物品，用于 matches 筛选）
     * @param target 目标槽物品（BE 容器实时引用，交互就地修改）
     */
    public static boolean tryInteract(SlotAccessor source, ItemStack cargo, ItemStack target, Level level) {
        if (source == null || cargo.isEmpty() || target.isEmpty()) return false;
        if (!isEligibleCargo(cargo)) return false;   // 活物品不作货物（隔离规则，唯一定义点）
        if (!(level instanceof ServerLevel serverLevel)) return false;

        for (SlotInteraction interaction : INTERACTIONS) {
            if (!interaction.matches(cargo, target)) continue;
            int amount = interaction.consumeAmount();
            // 模拟优先：先试算，交互生效才真扣（源不足/被过滤 → 空栈 → 交给下一条）
            ItemStack simulated = source.simulateExtract(amount);
            if (simulated.isEmpty()) continue;
            if (interaction.interact(simulated, target, serverLevel)) {
                source.extract(amount, null);
                return true;
            }
        }
        return false;
    }

    /**
     * 未知货物（跨容器拉取）：遍历邻居槽位，找能接管目标槽的货物。
     *
     * <p>拉取方向没有「源槽」——源在邻居容器里，货物得自己找。因此这里对每个
     * 邻居槽位先跑 {@link #canInteract} 廉价筛选（纯谓词，纳秒级），命中才创建
     * 邻居槽 Accessor 并走模拟优先协议。筛选已内建<b>货物准入</b>
     * （{@link #isEligibleCargo}）：活骨粉这类活物品不是合法货物，恒被拒
     * ——施肥属传输语义，受漏斗自己的货物规则约束（2026-09-15 用户定案）。</p>
     *
     * @param filter 邻居槽 Accessor 的过滤数据（漏斗黑白名单，在源侧生效）
     */
    public static boolean tryInteractFromNeighbor(IItemHandler neighborHandler, BlockPos neighborPos,
                                                  ItemStack target, Level level, FilterData filter) {
        if (target.isEmpty()) return false;
        if (!(level instanceof ServerLevel)) return false;

        Container container = ContainerContext.getContainer(level, neighborPos);
        for (int i = 0; i < neighborHandler.getSlots(); i++) {
            ItemStack cargo = neighborHandler.getStackInSlot(i);
            if (cargo.isEmpty() || !canInteract(cargo, target)) continue;
            if (container != null && !container.canTakeItem(container, i, cargo)) continue;
            SlotAccessor source = SlotAccessorFactory.createForNeighbor(neighborHandler, i, filter, level, neighborPos);
            if (tryInteract(source, cargo, target, level)) return true;
        }
        return false;
    }
}
