package com.qiqi.li.client.render;

import java.util.HashSet;
import java.util.Set;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.farmland.CropClassifier;
import com.qiqi.li.living.domain.farmland.FarmlandPlantComponent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 活耕地的「生长槽大图」渲染：上方生长槽为空时，用世界级管线绘制作物当前阶段的完整方块模型。
 *
 * <p>2026-10-29 从 {@code AbstractContainerScreenMixin} 抽出并改挂
 * {@code ContainerScreenEvent.Render.Foreground}（原因见 {@link LivingContainerOverlay}：
 * 照抄型第三方界面不调用 {@code super.render}，注入在 {@code render} 里的字节码永远不执行）。</p>
 *
 * <p><b>种子图标叠加不在此处</b>——2026-09-16 迁到
 * {@link LivingFarmlandSeedDecorator}（IItemDecorator）：原实现依赖 {@code leftPos}/{@code topPos}，
 * 而 HUD 快捷栏走 {@code Gui.renderHotbar} → {@code renderItemDecorations}，<b>不经过容器界面</b>。
 * 生长槽大图无法同样迁移——它需要「同容器正上方一格槽位」的邻居关系，
 * 而装饰器只拿得到 {@code xOffset/yOffset}。</p>
 */
public final class LivingFarmlandOverlay {

    private LivingFarmlandOverlay() {}

    /** 画当前界面里所有活耕地的生长槽。位姿须已重置为 identity（见类注释）。 */
    public static void render(GuiGraphics guiGraphics, AbstractContainerScreen<?> screen) {
        int leftPos = screen.getGuiLeft();
        int topPos = screen.getGuiTop();

        // 生长槽认领表：同列两块耕地中间恰隔一空行时，两块都把中间空槽当生长槽
        // → 同槽双画重叠（2026-09-14 终审修复）——per-frame 先到先得
        Set<Slot> claimedGrowthSlots = new HashSet<>();

        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !stack.is(Items.FARMLAND)) continue;
            if (!LivingItemManager.isLivingItem(stack)) continue;

            FarmlandPlantComponent plant = FarmlandPlantComponent.of(stack);
            if (!plant.isPlanted()) continue;

            Block cropBlock = CropClassifier.getBlockFromSeed(plant.cropSeed());
            if (cropBlock == null) continue;

            // 按坐标匹配（同容器 + 恰在正上方一格）而非索引算术——天然适应箱子/背包/创造各布局；
            // 「空槽即画」自动覆盖全部状态：生长中显苗、成熟产出后被真实物品覆盖、取走后显成熟形态。
            renderCropInGrowthSlot(guiGraphics, screen, slot, cropBlock, plant.age(),
                claimedGrowthSlots, leftPos, topPos);
        }
    }

    private static Slot findSlotAbove(AbstractContainerScreen<?> screen, Slot lowerSlot) {
        for (Slot other : screen.getMenu().slots) {
            if (other == lowerSlot) continue;
            if (other.container != lowerSlot.container) continue;
            if (other.x != lowerSlot.x || other.y != lowerSlot.y - 18) continue;
            return other;
        }
        return null;
    }

    private static void renderCropInGrowthSlot(GuiGraphics guiGraphics, AbstractContainerScreen<?> screen,
                                               Slot farmlandSlot, Block cropBlock, int age,
                                               Set<Slot> claimedGrowthSlots, int leftPos, int topPos) {
        Slot growthSlot = findSlotAbove(screen, farmlandSlot);
        if (growthSlot == null || !growthSlot.getItem().isEmpty()) {
            return;   // 无生长槽（顶行）或被占用（含成熟产出）→ 让位/等待
        }
        // 同帧已被其它耕地认领的生长槽（同列隔空行双耕地场景）→ 本块让位防双画
        if (!claimedGrowthSlots.add(growthSlot)) return;

        // 下部件：作物当前 age 的方块状态，世界级管线原样渲染
        // （茎逐段生长几何 + age 染色、任意模组模型——无每作物特判）。
        // displayStateFor：火把花类「maxAge 超属性值域」怪癖——成熟态越界时
        // 回退收获形态方块默认态（花方块），否则成熟后生长槽空白
        BlockState lower = CropClassifier.displayStateFor(cropBlock, age);
        if (lower != null) {
            LivingIconRenderHelper.renderBlockIcon(guiGraphics, lower,
                leftPos + growthSlot.x, topPos + growthSlot.y);
        }

        // 多格上部件三模式互斥（属性结构本互斥，防御双注册双画）：
        // 命中其一即跳过其余——半部件/注册式上部件 与 柱状段 不会叠加
        // 两格高上部件：生长槽正上方的空槽渲染上半个模型（纯视觉，不影响 tick）
        BlockState upper = CropClassifier.getUpperCompanion(cropBlock, age);
        if (upper != null) {
            Slot upperSlot = findSlotAbove(screen, growthSlot);
            if (upperSlot != null && upperSlot.getItem().isEmpty()) {
                LivingIconRenderHelper.renderBlockIcon(guiGraphics, upper,
                    leftPos + upperSlot.x, topPos + upperSlot.y);
            }
            return;
        }

        // 柱状多段作物（第三种多格形态：同方块属性分段，如 KC 水稻 location 三段柱）：
        // 各段从生长槽正上方逐格向上渲染，空槽才画、到顶/被占自动截断
        for (BlockState part : CropClassifier.getColumnParts(cropBlock, age)) {
            Slot partSlot = findSlotAbove(screen, growthSlot);
            if (partSlot == null || !partSlot.getItem().isEmpty()) break;
            LivingIconRenderHelper.renderBlockIcon(guiGraphics, part,
                leftPos + partSlot.x, topPos + partSlot.y);
            growthSlot = partSlot;   // 下一段从这段正上方继续
        }
    }
}
