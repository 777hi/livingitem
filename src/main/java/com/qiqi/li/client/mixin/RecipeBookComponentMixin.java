package com.qiqi.li.client.mixin;

import com.qiqi.li.client.gui.LivingChestRecipeTab;
import com.qiqi.li.client.util.LivingChestTabState;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 配方书组件 (RecipeBookComponent) 的 Mixin —— 在配方书中挂载「活箱子」标签页。
 *
 * <p>只负责两件事：@Shadow 访问原版私有状态、把注入点事件委托给
 * {@link LivingChestRecipeTab}（标签按钮 / 内容网格 / 分页 / 搜索 / 点击存取的全部逻辑所在）。
 * 数据流详见 docs/tech/common/recipe-book-tech.md。</p>
 *
 * <p>注入点共 7 处：initVisuals（TAIL 建标签 + updateCollections 前补材料）、
 * render（popPose 前画网格 —— 此时变换矩阵仍有效；TAIL 画标签按钮，保证在所有原版内容之后）、
 * mouseClicked（HEAD 优先处理标签/翻页/槽位；RETURN 原版处理过点击则自动退出）、
 * updateStackedContents（updateCollections 前补材料）。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {

    // ==================== Shadow（访问原版私有成员） ====================

    /** 原版配方书的所有标签按钮（活箱子标签定位在末尾之后，垂直堆叠） */
    @Shadow
    @Final
    private List<RecipeBookTabButton> tabButtons;

    /** 当前选中的原版标签按钮（切到活箱子标签时需取消其选中态） */
    @Shadow
    private RecipeBookTabButton selectedTab;

    /** 配方书容器宽高与 X 偏移（用于居中定位） */
    @Shadow
    private int width;

    @Shadow
    private int height;

    @Shadow
    private int xOffset;

    /** 材料堆叠计数器（活箱子物品须计入，配方系统才识别） */
    @Shadow
    private StackedContents stackedContents;

    /** 原版配方书搜索框（活箱子网格的内容过滤读取它的文本） */
    @Shadow
    private EditBox searchBox;

    @Shadow
    protected abstract boolean isVisible();

    // ==================== 委托视图 ====================

    /**
     * 活箱子标签页视图。首次 {@code initVisuals} 时才创建 ——
     * 创建前所有注入点都在此早退（等价于改造前对按钮实例的 null 检查时序）。
     */
    @Unique
    private LivingChestRecipeTab livingChestTabView;

    // ==================== 初始化 ====================

    @Inject(method = "initVisuals", at = @At("TAIL"))
    private void onInitVisuals(CallbackInfo ci) {
        if (this.livingChestTabView == null) {
            this.livingChestTabView = new LivingChestRecipeTab();
        }
        this.livingChestTabView.onInitVisuals(
            (this.width - 147) / 2 - this.xOffset - 30,
            (this.height - 166) / 2 + 3,
            this.tabButtons.size());
    }

    // ==================== 渲染 ====================

    @Inject(method = "render", at = @At(
        value = "INVOKE",
        target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"
    ))
    private void onRenderBeforePop(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (this.livingChestTabView == null) return;
        if (!this.livingChestTabView.beforePopRender(this.isVisible())) return;

        int i = (this.width - 147) / 2 - this.xOffset;
        int j = (this.height - 166) / 2;
        this.livingChestTabView.renderContents(guiGraphics, i, j, mouseX, mouseY,
            this.searchBox == null ? "" : this.searchBox.getValue());
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderTail(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (this.livingChestTabView == null || !this.isVisible()) return;

        this.livingChestTabView.renderTabButton(guiGraphics, mouseX, mouseY, partialTick);
    }

    // ==================== 鼠标事件 ====================

    /**
     * HEAD 阶段拦截，优先级：活箱子标签 &gt; 翻页按钮 &gt; 内容槽位。
     * 命中即 {@code setReturnValue(true)} 短路原版处理。
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (this.livingChestTabView == null) return;

        if (this.livingChestTabView.onTabClicked(mouseX, mouseY, button, this.selectedTab)) {
            cir.setReturnValue(true);
            return;
        }

        if (LivingChestTabState.isActive() && this.isVisible()) {
            if (this.livingChestTabView.onPageButtonClicked(mouseX, mouseY, button)) {
                cir.setReturnValue(true);
                return;
            }

            if (this.livingChestTabView.onSlotClicked(mouseX, mouseY, button)) {
                cir.setReturnValue(true);
            }
        }
    }

    /**
     * RETURN 阶段：原版处理了本次点击 ⇒ 视为切回原版视图，自动退出活箱子标签。
     * 点击搜索框不算（防误退，见 {@link #isClickOnSearchBox}）。
     */
    @Inject(method = "mouseClicked", at = @At("RETURN"))
    private void onMouseClickedReturn(double mouseX, double mouseY, int button,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (this.livingChestTabView == null || !this.isVisible()) return;

        if (isClickOnSearchBox((int) mouseX, (int) mouseY)) return;

        this.livingChestTabView.deactivateIfVanillaClick(cir.getReturnValueZ(), this.selectedTab);
    }

    /** 点击是否落在搜索框区域内（仅该区域内点击不触发自动退出） */
    @Unique
    private boolean isClickOnSearchBox(int clickX, int clickY) {
        if (this.searchBox == null || !this.searchBox.isVisible()) {
            return false;
        }

        int boxX = this.searchBox.getX();
        int boxY = this.searchBox.getY();
        int boxWidth = this.searchBox.getWidth();
        int boxHeight = this.searchBox.getHeight();

        return clickX >= boxX && clickX <= boxX + boxWidth &&
               clickY >= boxY && clickY <= boxY + boxHeight;
    }

    // ==================== 配方系统集成 ====================

    /**
     * 把活箱子物品计入 {@link StackedContents}，配方系统才能识别（活箱子有钻石 ⇒ 钻石装备显示可制作）。
     *
     * <p>⚠️ 原版有两条重建 stackedContents 的路径，注入缺一不可：
     * {@code initVisuals}（打开配方书 / 切换可见性）与 {@code updateStackedContents}
     * （背包变动 / 点击槽位）。只注入后者会导致刚打开配方书时活箱子材料不被识别。</p>
     */
    @Inject(method = "updateStackedContents", at = @At(
        value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;updateCollections(Z)V"
    ))
    private void beforeUpdateCollections(CallbackInfo ci) {
        accountLivingChestItems();
    }

    /** 同上 —— initVisuals 路径的补充（此路径此前未注入，曾导致打开配方书时材料丢失） */
    @Inject(method = "initVisuals", at = @At(
        value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;updateCollections(Z)V"
    ))
    private void beforeInitVisualsCollections(CallbackInfo ci) {
        accountLivingChestItems();
    }

    @Unique
    private void accountLivingChestItems() {
        for (ItemStack chestItem : LivingChestRecipeTab.collectLivingChestItems()) {
            if (!chestItem.isEmpty()) {
                this.stackedContents.accountStack(chestItem);
            }
        }
    }
}
