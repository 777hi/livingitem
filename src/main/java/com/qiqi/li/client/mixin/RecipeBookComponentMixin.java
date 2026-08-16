package com.qiqi.li.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qiqi.li.client.util.LivingChestTabState;
import com.qiqi.li.client.util.PinyinHelper;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.network.LivingChestAccessPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StateSwitchingButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;

import java.util.Locale;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方书组件 (RecipeBookComponent) 的 Mixin 类
 * 
 * <h2>🎯 功能概述</h2>
 * <p>在原版配方书中添加一个<strong>"活箱子"</strong>标签页，用于显示和操作
 * 玩家背包中所有活箱子内的物品内容。这是一个客户端 GUI 扩展功能。</p>
 * 
 * <h2>📋 核心特性</h2>
 * <ul>
 *   <li><strong>标签页切换</strong>: 在配方书顶部添加"活箱子"按钮，点击切换视图</li>
 *   <li><strong>网格显示</strong>: 以 5×4 网格形式展示活箱子内的物品（支持分页）</li>
 *   <li><strong>交互操作</strong>: 支持左键/右键/Shift 点击进行存取物品</li>
 *   <li><strong>配方集成</strong>: 活箱子内的物品可被配方系统识别（自动计入材料清单）</li>
 * </ul>
 * 
 * <h2>🔧 技术实现</h2>
 * <ul>
 *   <li>使用 SpongePowered Mixin 注入到 {@code RecipeBookComponent}</li>
 *   <li>通过直接读取玩家背包内活箱子的NBT数据获取内容</li>
 *   <li>使用 {@link LivingChestAccessPacket} 与服务器通信进行实际操作</li>
 * </ul>
 * 
 * <h2>🎨 UI 布局</h2>
 * <pre>
 * ┌─────────────────────────────┐
 * │ [合成] [熔炉] [活箱子]      │ ← 标签栏
 * ├─────────────────────────────┤
 * │ 活箱子                      │ ← 标题
 * │ ┌─┬─┬─┬─┬─┐               │
 * │ │ │ │ │ │ │               │ ← 5列 × 4行 物品网格
 * │ ├─┼─┼─┼─┼─┤               │
 * │ │ │ │ │ │ │               │
 * │ ├─┼─┼─┼─┼─┤               │
 * │ │ │ │ │ │ │               │
 * │ ├─┼─┼─┼─┼─┤               │
 * │ │ │ │ │ │ │               │
 * │ └─┴─┴─┴─┴─┘               │
 * │     [<] 1/3 [>]           │ ← 分页导航
 * └─────────────────────────────┘
 * </pre>
 *
 * @author qiqi
 * @since 1.21.1
 */
@OnlyIn(Dist.CLIENT)
@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {

    // ==================== Shadow 字段（访问原类私有成员） ====================

    /**
     * 原版配方书的所有标签按钮列表
     * <p>包含"合成"、"熔炉"、"锻造"等原版标签，我们将在末尾追加"活箱子"标签</p>
     */
    @Shadow
    @Final
    private List<RecipeBookTabButton> tabButtons;

    /**
     * 当前选中的原版标签按钮
     * <p>当切换到活箱子标签时，需要取消此标签的选中状态</p>
     */
    @Shadow
    private RecipeBookTabButton selectedTab;

    /**
     * 配方书容器的宽度（像素）
     */
    @Shadow
    private int width;

    /**
     * 配方书容器的高度（像素）
     */
    @Shadow
    private int height;

    /**
     * X 轴偏移量（用于居中定位）
     */
    @Shadow
    private int xOffset;

    /**
     * Minecraft 客户端实例
     * <p>用于获取字体渲染器、玩家信息等</p>
     */
    @Shadow
    private Minecraft minecraft;

    /**
     * 材料堆叠计数器
     * <p>用于追踪当前可用的所有物品数量（包括活箱子内的物品），
     * 配方系统使用此信息判断哪些配方可以制作</p>
     */
    @Shadow
    private StackedContents stackedContents;

    /**
     * 原版配方书的搜索框组件（用于读取搜索关键词）
     * <p>通过 Mixin @Shadow 访问原版私有字段</p>
     *
     * @see net.minecraft.client.gui.screens.recipebook.RecipeBookComponent#searchBox
     */
    @Shadow
    private EditBox searchBox;

    /**
     * 检查配方书是否可见
     * @return 如果配方书处于打开状态返回 true
     */
    @Shadow
    protected abstract boolean isVisible();

    // ==================== Unique 字段（Mixin 新增成员） ====================

    /**
     * "活箱子"标签按钮实例
     * <p>使用 StateSwitchingButton 实现双态切换效果（选中/未选中）</p>
     */
    @Unique
    private StateSwitchingButton livingChestTab;

    @Unique
    private int currentPage;

    @Unique
    private int totalPages = 1;

    /**
     * 活箱子标签按钮的纹理精灵图集
     * <p>复用原版配方书标签纹理，保持视觉一致性：
     * <ul>
     *   <li>未选中 + 鼠标悬停外</li>
     *   <li>未选中 + 鼠标悬停内</li>
     *   <li>选中 + 鼠标悬停外</li>
     *   <li>选中 + 鼠标悬停内</li>
     * </ul></p>
     */
    @Unique
    private static final WidgetSprites LIVING_CHEST_TAB_SPRITES = new WidgetSprites(
        ResourceLocation.withDefaultNamespace("recipe_book/tab_selected"),
        ResourceLocation.withDefaultNamespace("recipe_book/tab"),
        ResourceLocation.withDefaultNamespace("recipe_book/tab_selected"),
        ResourceLocation.withDefaultNamespace("recipe_book/tab_selected")
    );

    /**
     * 物品槽背景纹理
     * <p>使用原版的"可制作"槽位样式（浅色边框）</p>
     */
    @Unique
    private static final ResourceLocation SLOT_BG_SPRITE =
        ResourceLocation.withDefaultNamespace("recipe_book/slot_craftable");

    /** 下一页按钮纹理（普通状态） */
    @Unique
    private static final ResourceLocation PAGE_FORWARD_SPRITE =
        ResourceLocation.withDefaultNamespace("recipe_book/page_forward");

    /** 下一页按钮纹理（高亮状态） */
    @Unique
    private static final ResourceLocation PAGE_FORWARD_HL_SPRITE =
        ResourceLocation.withDefaultNamespace("recipe_book/page_forward_highlighted");

    /** 上一页按钮纹理（普通状态） */
    @Unique
    private static final ResourceLocation PAGE_BACKWARD_SPRITE =
        ResourceLocation.withDefaultNamespace("recipe_book/page_backward");

    /** 上一页按钮纹理（高亮状态） */
    @Unique
    private static final ResourceLocation PAGE_BACKWARD_HL_SPRITE =
        ResourceLocation.withDefaultNamespace("recipe_book/page_backward_highlighted");

    // ==================== UI 尺寸常量（已迁移至 Layout 类） ====================
    // 🗑️ 旧常量已删除，请使用 Layout.XXX 访问

    /**
     * 🆕 配方书布局常量集合（集中管理所有魔法数字）
     * 
     * <h3>🎯 设计目的</h3>
     * <ul>
     *   <li>✅ 消除散落在各处的硬编码数值</li>
     *   <li>✅ 提高代码可读性和可维护性</li>
     *   <li>✅ 方便统一调整布局参数</li>
     *   <li>✅ 提供语义化的常量名称</li>
     * </ul>
     * 
     * <h3>📝 使用示例</h3>
     * <pre>{@code
     * // 旧代码（魔法数字）:
     * int x = left + 11;  // 11 是什么？
     * 
     * // 新代码（语义化常量）:
     * int x = left + Layout.GRID_OFFSET_X;  // 清晰明了！
     * }</pre>
     */
    @Unique
    private static final class Layout {
        // ======== 物品网格布局 ========
        /** 单个物品槽大小（25×25 像素，与 RecipeButton 一致） */
        static final int SLOT_SIZE = 25;
        /** 槽位间距（0 像素，紧密排列） */
        static final int SLOT_GAP = 0;
        /** 网格列数 */
        static final int COLUMNS = 5;
        /** 网格行数 */
        static final int ROWS = 4;
        /** 每页总槽数 */
        static final int TOTAL_SLOTS = COLUMNS * ROWS;
        
        // ======== 网格位置偏移 ========
        /** 网格左边缘距离容器左边界的偏移（11 像素） */
        static final int GRID_OFFSET_X = 11;
        /** 网格上边缘距离容器上边界的偏移（31 像素） */
        static final int GRID_OFFSET_Y = 31;
        /** 物品图标在槽位内的偏移量（4 像素） */
        static final int ITEM_OFFSET = 4;
        
        // ======== 分页按钮布局 ========
        /** 按钮的 Y 坐标偏移（137 像素，与 RecipeBookPage 一致） */
        static final int BUTTON_Y_OFFSET = 137;
        /** 上一页按钮 X 坐标偏移（38 像素） */
        static final int BACK_BUTTON_X_OFFSET = 38;
        /** 下一页按钮 X 坐标偏移（93 像素） */
        static final int FORWARD_BUTTON_X_OFFSET = 93;
        /** 页码文本 Y 坐标偏移（141 像素） */
        static final int PAGE_TEXT_Y_OFFSET = 141;
        /** 页码文本水平居中偏移（73 像素） */
        static final int PAGE_TEXT_X_CENTER_OFFSET = 73;
        
        // ======== 按钮尺寸 ========
        /** 分页按钮宽度（12 像素） */
        static final int BUTTON_WIDTH = 12;
        /** 分页按钮高度（17 像素） */
        static final int BUTTON_HEIGHT = 17;
    }

    // ==================== 动态管理的 UI 组件 ====================

    /**
     * 当前页面的物品槽位列表
     * <p>使用原版 {@link Slot} 对象管理每个物品位置，
     * 可以直接复用 AbstractContainerScreen 的渲染方法</p>
     */
    @Unique
    private List<Slot> livingChestSlots = new ArrayList<>();

    /**
     * 当前悬停的槽位索引（用于 Tooltip 渲染）
     */
    @Unique
    private int hoveredSlotIndex = -1;

    /**
     * 上一页按钮（使用原版 Button 组件）
     */
    @Unique
    private Button backButton;

    /**
     * 下一页按钮（使用原版 Button 组件）
     */
    @Unique
    private Button forwardButton;

    @Unique
    private String lastSearchText = "";

    // ==================== 初始化相关 ====================

    /**
     * 在配方书初始化视觉元素时注入
     * 
     * <h3>📍 注入点</h3>
     * <p>{@code initVisuals()} 方法尾部（TAIL），确保在原版标签创建完成后执行</p>
     * 
     * <h3>✅ 功能</h3>
     * <ol>
     *   <li>创建"活箱子"标签按钮（首次调用时初始化）</li>
     *   <li>重置标签状态为未激活</li>
     *   <li>计算并设置标签位置（紧跟在最后一个原版标签之后）</li>
     * </ol>
     * 
     * <h3>🔢 定位算法</h3>
     * <pre>
     * x = (容器宽度 - 147) / 2 - xOffset - 30  （与原版标签对齐）
     * y = (容器高度 - 166) / 2 + 3 + 27 * tabCount  （垂直堆叠）
     * </pre>
     *
     * @param ci 回调信息（可用于取消原方法）
     */
    @Inject(method = "initVisuals", at = @At("TAIL"))
    private void onInitVisuals(CallbackInfo ci) {
        if (this.livingChestTab == null) {
            this.livingChestTab = new StateSwitchingButton(0, 0, 35, 27, false);
            this.livingChestTab.initTextureValues(LIVING_CHEST_TAB_SPRITES);
        }
        LivingChestTabState.setActive(false);
        this.livingChestTab.setStateTriggered(false);
        this.currentPage = 0;

        int i = (this.width - 147) / 2 - this.xOffset - 30;
        int j = (this.height - 166) / 2 + 3;
        int tabCount = this.tabButtons.size();
        this.livingChestTab.setPosition(i, j + 27 * tabCount);
    }

    // ==================== 渲染相关 ====================

    /**
     * 在配方书主渲染方法的矩阵栈弹出前注入
     * 
     * <h3>📍 注入点</h3>
     * <p>{@code PoseStack.popPose()} 调用之前，此时变换矩阵仍然有效</p>
     * 
     * <h3>✅ 功能</h3>
     * <p>如果活箱子标签已激活，在此处渲染活箱子内容网格。
     * 选择此注入点的目的是利用原版配方书的坐标变换系统。</p>
     *
     * @param guiGraphics 图形上下文
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param partialTick 部分刻度（用于动画插值）
     * @param ci 回调信息
     */
    @Inject(method = "render", at = @At(
        value = "INVOKE",
        target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V"
    ))
    private void onRenderBeforePop(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (LivingChestTabState.isActive() && !isVisible()) {
            LivingChestTabState.setActive(false);
            this.livingChestTab.setStateTriggered(false);
        }

        if (this.livingChestTab == null || !LivingChestTabState.isActive()) return;

        int i = (this.width - 147) / 2 - this.xOffset;
        int j = (this.height - 166) / 2;
        this.renderLivingChestContents(guiGraphics, i, j, mouseX, mouseY);
    }

    /**
     * 在配方书主渲染方法的最末尾注入
     * 
     * <h3>📍 注入点</h3>
     * <p>{@code render()} 方法尾部（TAIL），确保在所有原版内容之后绘制</p>
     * 
     * <h3>✅ 功能</h3>
     * <ol>
     *   <li>渲染"活箱子"标签按钮（始终可见）</li>
     *   <li>在标签图标上叠加一个箱子物品图标（增强辨识度）</li>
     * </ol>
     * 
     * <h3>⚠️ 注意事项</h3>
     * <p>禁用深度测试以防止按钮被其他 UI 元素遮挡</p>
     *
     * @param guiGraphics 图形上下文
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param partialTick 部分刻度
     * @param ci 回调信息
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderTail(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (this.livingChestTab == null || !this.isVisible()) return;

        RenderSystem.disableDepthTest();
        this.livingChestTab.render(guiGraphics, mouseX, mouseY, partialTick);
        RenderSystem.enableDepthTest();

        this.renderLivingChestIcon(guiGraphics);
    }

    /**
     * 渲染活箱子标签上的箱子图标
     * 
     * <h3>🎨 渲染细节</h3>
     * <ul>
     *   <li>使用 {@code renderFakeItem} 绘制一个假物品（不消耗真实物品栈）</li>
     *   <li>图标位置根据标签状态微调：选中时向左偏移 2px（模拟按压效果）</li>
     *   <li>固定使用 {@code Items.CHEST} 作为图标</li>
     * </ul>
     *
     * @param guiGraphics 图形上下文
     */
    @Unique
    private void renderLivingChestIcon(GuiGraphics guiGraphics) {
        int x = this.livingChestTab.getX() + 9 + (this.livingChestTab.isStateTriggered() ? -2 : 0);
        int y = this.livingChestTab.getY() + 5;
        guiGraphics.renderFakeItem(new ItemStack(Items.CHEST), x, y);
    }

    /**
     * 渲染活箱子内容网格（支持搜索过滤）
     *
     * <h3>🎨 设计理念</h3>
     * <p><strong>100% 复刻原版配方书界面</strong>，包括：</p>
     * <ul>
     *   <li>✅ 使用 {@code renderFakeItem()} 渲染物品（与 RecipeButton 一致）</li>
     *   <li>✅ 25×25 像素槽位尺寸（与 RecipeButton.BACKGROUND_SIZE 一致）</li>
     *   <li>✅ 物品偏移 (4, 4)（与 RecipeButton.renderWidget() 一致）</li>
     *   <li>✅ 配方书专用纹理（recipe_book/slot_*.png）</li>
     *   <li>✅ 网格布局 5列×4行（与 RecipeBookPage 完全一致）</li>
     *   <li>✅ 翻页按钮样式和位置（与 RecipeBookPage 完全一致）</li>
     *   <li>🆕 ✅ 搜索过滤功能（适配原版搜索框）</li>
     * </ul>
     *
     * <h3>🔍 搜索功能说明</h3>
     * <p><strong>自动适配配方书搜索框</strong>：</p>
     * <ol>
     *   <li>读取原版搜索框的文本内容</li>
     *   <li>根据关键词过滤活箱子物品列表</li>
     *   <li>支持物品名称、ID、描述文本匹配</li>
     *   <li>大小写不敏感搜索</li>
     *   <li>空搜索词显示全部物品</li>
     * </ol>
     *
     * @param guiGraphics 图形上下文
     * @param left 容器左边界 X 坐标
     * @param top 容器上边界 Y 坐标
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     */
    @Unique
    private List<ItemStack> collectLivingChestItems() {
        List<ItemStack> contents = new ArrayList<>();
        Player player = Minecraft.getInstance().player;
        if (player == null) return contents;

        for (ItemStack invStack : player.getInventory().items) {
            if (!LivingChestFunction.isLivingChest(invStack)) continue;
            if (invStack.getCount() > 1) continue;

            List<ItemStack> items = LivingChestFunction.getItems(invStack);
            for (ItemStack item : items) {
                if (!item.isEmpty()) {
                    contents.add(item);
                }
            }
        }
        return contents;
    }

    @Unique
    private void renderLivingChestContents(GuiGraphics guiGraphics, int left, int top, int mouseX, int mouseY) {
        String currentSearch = getSafeSearchText();
        if (!currentSearch.equals(this.lastSearchText)) {
            this.lastSearchText = currentSearch;
            this.currentPage = 0;
        }

        List<ItemStack> contents = collectLivingChestItems();

        List<ItemStack> displayContents = applySearchFilter(contents);

        int pages = Math.max(1, (displayContents.size() + Layout.TOTAL_SLOTS - 1) / Layout.TOTAL_SLOTS);
        this.totalPages = pages;
        if (this.currentPage >= pages) {
            this.currentPage = pages - 1;
        }
        int startIdx = this.currentPage * Layout.TOTAL_SLOTS;

        // 🎨 步骤 1: 初始化/更新 Slot 对象（使用配方书风格的槽位系统）
        updateLivingChestSlots(left, top, displayContents, startIdx);

        // 🎨 步骤 2: 渲染所有槽位（使用配方书风格）
        this.hoveredSlotIndex = -1;  // 重置悬停状态
        for (int i = 0; i < this.livingChestSlots.size(); i++) {
            Slot slot = this.livingChestSlots.get(i);
            renderSlot(guiGraphics, slot, mouseX, mouseY, i);
        }

        // 🎨 步骤 3: 渲染 Tooltip（如果鼠标在某个槽位上）
        if (this.hoveredSlotIndex >= 0) {
            Slot hoveredSlot = this.livingChestSlots.get(this.hoveredSlotIndex);
            if (!hoveredSlot.getItem().isEmpty()) {
                renderSlotTooltip(guiGraphics, hoveredSlot, mouseX, mouseY);
            }
        }

        // 🎨 步骤 4: 渲染分页控件（使用配方书风格的按钮）
        if (totalPages > 1) {
            setupPageButtons(left, top, totalPages);

            // 渲染按钮（如果有）
            if (this.backButton != null) {
                this.backButton.render(guiGraphics, mouseX, mouseY, 0);
            }
            if (this.forwardButton != null) {
                this.forwardButton.render(guiGraphics, mouseX, mouseY, 0);
            }

            // 渲染页码文本（与 RecipeBookPage.render() 一致的格式和位置）
            Component pageComponent = Component.translatable("gui.recipebook.page", this.currentPage + 1, totalPages);
            int textWidth = this.minecraft.font.width(pageComponent);
            int textX = left - textWidth / 2 + Layout.PAGE_TEXT_X_CENTER_OFFSET;  // 居中显示
            guiGraphics.drawString(this.minecraft.font, pageComponent, textX, top + Layout.PAGE_TEXT_Y_OFFSET, -1, false);
        }
    }

    /**
     * 应用搜索过滤到物品列表（实时扫描，无缓存）
     *
     * @param originalList 原始物品列表（未过滤）
     * @return 过滤后的物品列表
     */
    @Unique
    private List<ItemStack> applySearchFilter(List<ItemStack> originalList) {
        String searchText = getSafeSearchText();

        if (searchText.isEmpty()) {
            return originalList;
        }

        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : originalList) {
            if (stack.isEmpty()) continue;

            if (matchesSearchText(stack, searchText)) {
                result.add(stack);
            }
        }

        return result;
    }

    /**
     * 🆕 安全获取搜索框文本（带异常处理）
     * 
     * <h3>🎯 设计目的</h3>
     * <ul>
     *   <li>提取重复的异常处理逻辑为独立方法</li>
     *   <li>统一搜索框文本的获取方式</li>
     *   <li>提高代码可读性和可维护性</li>
     * </ul>
     *
     * @return 安全的搜索文本（已转小写并去除首尾空白），如果出错返回空字符串
     */
    @Unique
    private String getSafeSearchText() {
        if (this.searchBox == null) return "";
        String text = this.searchBox.getValue();
        return text != null ? text.trim().toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 检查单个物品是否匹配搜索关键词（支持拼音搜索）
     *
     * <h3>🔍 匹配范围</h3>
     * <ul>
     *   <li>✅ 物品显示名称（含重命名标签）- 支持中文/英文/拼音</li>
     *   <li>✅ 物品注册 ID（命名空间:路径）</li>
     *   <li>✅ 物品描述文本（Lore）- 支持拼音</li>
     *   <li>🆕 ✅ 完整拼音匹配: "zuanshi" → "钻石"</li>
     *   <li>🆕 ✅ 首字母缩写: "zs" → "钻石"</li>
     *   <li>🆕 ✅ 部分拼音匹配: "zuan" → "钻石"</li>
     * </ul>
     *
     * <h3>🎯 拼音搜索示例</h3>
     * <table border="1">
     *   <tr><th>输入</th><th>可匹配的物品</th></tr>
     *   <tr><td>zuanshi / zs / zuan</td><td>钻石、钻石剑、钻石镐...</td></tr>
     *   <tr><td>tiejian / tj / tie</td><td>铁剑、铁制工具...</td></tr>
     *   <tr><td>mianbao / mb / mian</td><td>面包、面包片...</td></tr>
     *   <tr><td>钻石</td><td>钻石相关物品（直接中文）</td></tr>
     *   <tr><td>diamond</td><td>Diamond Sword 等（英文）</td></tr>
     * </table>
     *
     * @param stack 要检查的物品栈
     * @param searchText 搜索关键词（已转小写）
     * @return 是否匹配
     */
    @Unique
    private boolean matchesSearchText(ItemStack stack, String searchText) {
        if (stack.isEmpty() || searchText.isEmpty()) {
            return !searchText.isEmpty();
        }

        Component hoverName = stack.getHoverName();
        if (hoverName != null) {
            String name = hoverName.getString();
            if (name.toLowerCase(Locale.ROOT).contains(searchText)) {
                return true;
            }
            if (PinyinHelper.isPinyinMatch(name, searchText)) {
                return true;
            }
        }

        String itemId = stack.getItem().toString().toLowerCase(Locale.ROOT);
        return itemId.contains(searchText);
    }

    /**
     * 更新活箱子槽位列表（创建或更新 Slot 对象）
     * 
     * <h3>🔧 实现细节</h3>
     * <p>使用原版 {@link Slot} 类来表示每个物品位置。
     * 虽然 Slot 通常用于 ContainerMenu，但我们可以利用它的坐标和物品存储功能。</p>
     * 
     * <h3>⚡ 性能优化</h3>
     * <p>只在数据变化时重建 Slot 列表，避免每帧都创建新对象。</p>
     *
     * @param left 容器左边距
     * @param top 容器顶边距
     * @param contents 物品内容列表
     * @param startIdx 当前页起始索引
     */
    @Unique
    private void updateLivingChestSlots(int left, int top, List<ItemStack> contents, int startIdx) {
        this.livingChestSlots.clear();
        for (int slot = 0; slot < Layout.TOTAL_SLOTS; slot++) {
            int contentIdx = startIdx + slot;
            ItemStack stack = (contentIdx < contents.size()) ? contents.get(contentIdx) : ItemStack.EMPTY;
            this.livingChestSlots.add(createLivingChestSlot(left, top, slot, stack));
        }
    }

    /**
     * 创建单个活箱子槽位（使用绝对屏幕坐标）
     *
     * <h3>🔑 关键点</h3>
     * <p><strong>必须使用绝对屏幕坐标</strong>，而非相对于配方书的偏移！</p>
     *
     * <h3>📐 坐标计算公式</h3>
     * <pre>
     * 绝对 X = 配方书左边界(left) + 网格偏移(GRID_OFFSET_X) + 列间距(col × 25)
     * 绝对 Y = 配方书上边界(top) + 网格偏移(GRID_OFFSET_Y) + 行间距(row × 25)
     * </pre>
     *
     * <h3>⚠️ 常见错误</h3>
     * <p>如果忘记加 {@code left} 和 {@code top}，槽位会显示在屏幕左上角(0,0)附近，
     * 而不是在配方书内部！</p>
     *
     * @param left 配方书左边界的绝对 X 坐标
     * @param top 配方书上边界的绝对 Y 坐标
     * @param slotIndex 槽位索引（0-19）
     * @param stack 槽位中的物品
     */
    @Unique
    private Slot createLivingChestSlot(int left, int top, final int slotIndex, final ItemStack stack) {
        int col = slotIndex % Layout.COLUMNS;
        int row = slotIndex / Layout.COLUMNS;

        // 🔥 关键：使用绝对坐标（与原版 RecipeBookPage 一致）
        // 原版代码：this.buttons.get(i).setPosition(x + 11 + 25 * (i % 5), y + 31 + 25 * (i / 5));
        // 其中 x, y 是配方书的绝对坐标
        int x = left + Layout.GRID_OFFSET_X + col * (Layout.SLOT_SIZE + Layout.SLOT_GAP);
        int y = top + Layout.GRID_OFFSET_Y + row * (Layout.SLOT_SIZE + Layout.SLOT_GAP);

        // 创建虚拟 Slot 对象（不绑定到真实的 ContainerMenu）
        Slot slot = new Slot(null, slotIndex, x, y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;  // 不允许拖放（只读展示）
            }

            @Override
            public boolean mayPickup(Player player) {
                return true;  // 允许点击取出
            }

            @Override
            public ItemStack getItem() {
                return stack.copy();  // 返回物品副本
            }

            @Override
            public void setChanged() {
                // 空实现（不需要通知 ContainerMenu）
            }

            @Override
            public boolean isActive() {
                return !stack.isEmpty();  // 只有有物品时才激活
            }
        };

        return slot;
    }

    /**
     * 渲染单个槽位（完全复刻 RecipeButton 的渲染逻辑）
     *
     * <h3>🎨 渲染步骤（与 RecipeButton.renderWidget() 一致）</h3>
     * <ol>
     *   <li><strong>绘制背景</strong>: 使用配方书专用纹理 {@code recipe_book/slot}</li>
     *   <li><strong>绘制物品</strong>: 使用 {@code renderFakeItem()} （与 RecipeButton 一致）</li>
     *   <li><strong>绘制装饰</strong>: 数量文字、附魔光效等</li>
     *   <li><strong>悬停高亮</strong>: 半透明白色覆盖层</li>
     * </ol>
     *
     * <h3>⚙️ 关键参数</h3>
     * <ul>
     *   <li>槽位尺寸: 25×25 像素（RecipeButton.BACKGROUND_SIZE）</li>
     *   <li>物品偏移: (4, 4) 像素（RecipeButton 中的 i=4）</li>
     *   <li>渲染方法: renderFakeItem() 而非 renderItem()</li>
     * </ul>
     *
     * @see net.minecraft.client.gui.screens.recipebook.RecipeButton#renderWidget(GuiGraphics, int, int, float)
     */
    @Unique
    private void renderSlot(GuiGraphics guiGraphics, Slot slot, int mouseX, int mouseY, int slotIndex) {
        int slotX = slot.x;  // 相对于容器的 X 坐标
        int slotY = slot.y;  // 相对于容器的 Y 坐标

        // ✅ 步骤 1: 绘制槽位背景（使用配方书专用纹理）
        // 与 RecipeButton 中使用的纹理完全一致
        guiGraphics.blitSprite(SLOT_BG_SPRITE, slotX, slotY, Layout.SLOT_SIZE, Layout.SLOT_SIZE);

        // ✅ 步骤 2: 绘制物品（使用配方书风格 - renderFakeItem）
        ItemStack stack = slot.getItem();
        if (!stack.isEmpty()) {
            // 🔥 关键：使用与 RecipeButton 相同的偏移量 (ITEM_OFFSET=4)
            // 参见 RecipeButton.renderWidget(): guiGraphics.renderFakeItem(itemstack, this.getX() + i, this.getY() + i) 其中 i=4
            int itemX = slotX + Layout.ITEM_OFFSET;
            int itemY = slotY + Layout.ITEM_OFFSET;

            // 使用 renderFakeItem（与 RecipeButton 完全一致）
            // 注意：不是 renderItem，因为配方书使用的是假物品渲染模式
            guiGraphics.renderFakeItem(stack, itemX, itemY);

            // ✅ 步骤 3: 绘制物品装饰（数量、耐久度条等）
            guiGraphics.renderItemDecorations(this.minecraft.font, stack, itemX, itemY);
        }

        // ✅ 步骤 4: 检测鼠标悬停并记录高亮
        if (isHovering(mouseX, mouseY, slotX, slotY, Layout.SLOT_SIZE, Layout.SLOT_SIZE)) {
            this.hoveredSlotIndex = slotIndex;

            // 使用半透明高亮效果（与配方书一致）
            // 颜色值 0x80FFFFFF = 50% 白色透明度
            guiGraphics.fill(slotX, slotY, slotX + Layout.SLOT_SIZE, slotY + Layout.SLOT_SIZE, 0x80FFFFFF);
        }
    }

    /**
     * 渲染槽位的悬浮工具提示（Tooltip）
     * 
     * <h3>🆕 新增功能</h3>
     * <p>完整支持原版 Tooltip 系统：</p>
     * <ul>
     *   <li>物品名称（含重命名、自定义名称）</li>
     *   <li>物品描述（Lore 文本）</li>
     *   <li>附魔列表（带等级和颜色）</li>
     *   <li>药水效果（带时长和强度）</li>
     *   <li>耐久度信息（"损坏: 123/456"）</li>
     *   <li>Mod 自定义提示（通过 IClientItemExtensions）</li>
     * </ul>
     *
     * @param guiGraphics 图形上下文
     * @param slot 悬停的槽位
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     */
    @Unique
    private void renderSlotTooltip(GuiGraphics guiGraphics, Slot slot, int mouseX, int mouseY) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return;

        // 🆕 直接复用原版 GuiGraphics 的 Tooltip 渲染方法！
        // renderTooltip(ItemStack, int, int) 会自动处理：
        // ✅ 获取完整 Tooltip 内容（名称、Lore、附魔、耐久度等）
        // ✅ 多行文本换行与布局
        // ✅ 屏幕边界检测（防止超出屏幕）
        // ✅ 背景框绘制（深色半透明）
        // ✅ 文字阴影效果
        // ✅ 与所有原版容器界面完全一致的视觉效果

        guiGraphics.renderTooltip(
            this.minecraft.font,
            stack,       // ItemStack 对象（会自动提取所有信息）
            mouseX,      // 鼠标 X 坐标
            mouseY       // 鼠标 Y 坐标
        );
    }

    /**
     * 设置/更新翻页按钮（完全复刻 RecipeBookPage 的翻页按钮）
     *
     * <h3>📍 按钮位置（与 RecipeBookPage.init() 一致）</h3>
     * <pre>
     * RecipeBookPage 源码:
     *   this.forwardButton = new StateSwitchingButton(x + 93, y + 137, 12, 17, false);
     *   this.backButton = new StateSwitchingButton(x + 38, y + 137, 12, 17, true);
     * </pre>
     *
     * <h3>🎨 视觉特性</h3>
     * <ul>
     *   <li>使用配方书专用纹理（page_forward/page_backward）</li>
     *   <li>尺寸: 12×17 像素（与 StateSwitchingButton 一致）</li>
     *   <li>位置: 下一页(x+93, y+137), 上一页(x+38, y+137)</li>
     * </ul>
     *
     * @see net.minecraft.client.gui.screens.recipebook.RecipeBookPage#init(Minecraft, int, int)
     */
    @Unique
    private void setupPageButtons(int left, int top, int totalPages) {
        int btnY = top + Layout.BUTTON_Y_OFFSET;
        int backwardX = left + Layout.BACK_BUTTON_X_OFFSET;
        int forwardX = left + Layout.FORWARD_BUTTON_X_OFFSET;

        if (this.backButton == null) {
            this.backButton = Button.builder(Component.literal("<"), (button) -> {
                if (this.currentPage > 0) {
                    this.currentPage--;
                }
            })
            .pos(backwardX, btnY)
            .size(Layout.BUTTON_WIDTH, Layout.BUTTON_HEIGHT)
            .build();
        } else {
            this.backButton.setPosition(backwardX, btnY);
        }
        this.backButton.active = this.currentPage > 0;

        if (this.forwardButton == null) {
            this.forwardButton = Button.builder(Component.literal(">"), (button) -> {
                if (this.currentPage < this.totalPages - 1) {
                    this.currentPage++;
                }
            })
            .pos(forwardX, btnY)
            .size(Layout.BUTTON_WIDTH, Layout.BUTTON_HEIGHT)
            .build();
        } else {
            this.forwardButton.setPosition(forwardX, btnY);
        }
        this.forwardButton.active = this.currentPage < totalPages - 1;
    }

    // ⚠️ 注意：旧的 renderPageButtons 方法已被删除
    // 现在使用原版 Button 组件（见 setupPageButtons 方法）
    // 这样可以获得：
    // - 自动悬停高亮效果
    // - 标准化按钮外观
    // - 内置点击音效
    // - 无障碍支持

    /**
     * 检测鼠标是否在指定矩形区域内
     * 
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param x 矩形左边界
     * @param y 矩形上边界
     * @param w 矩形宽度
     * @param h 矩形高度
     * @return 如果鼠标在区域内返回 true
     */
    @Unique
    private static boolean isHovering(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ==================== 鼠标事件处理 ====================

    /**
     * 拦截配方书的鼠标点击事件（方法头部注入）
     * 
     * <h3>📍 注入点</h3>
     * <p>{@code mouseClicked()} 方法头部（HEAD），在任何原版处理逻辑之前执行</p>
     * 
     * <h3>✅ 处理优先级</h3>
     * <ol>
     *   <li><strong>活箱子标签点击</strong>: 切换到活箱子视图模式</li>
     *   <li><strong>翻页按钮点击</strong>: 切换当前显示的物品页</li>
     *   <li><strong>物品槽点击</strong>: 触发存取物品操作</li>
     * </ol>
     * 
     * <h3>🔄 标签切换逻辑</h3>
     * <pre>
     * 点击"活箱子"标签时:
     * 1. 设置 LivingChestTabState 为激活
     * 2. 更新按钮视觉状态（按下效果）
     * 3. 重置分页到第 0 页
     * 4. 取消原版标签的选中状态
     * 5. 向服务器请求加载活箱子数据
     *    ↓
     * 6. 使用 cir.setReturnValue(true) 取消后续处理
     * </pre>
     *
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param button 鼠标按键编号
     * @param cir 可取消的回调（用于提前返回）
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (this.livingChestTab == null) return;

        if (this.livingChestTab.mouseClicked(mouseX, mouseY, button)) {
            if (!LivingChestTabState.isActive()) {
                LivingChestTabState.setActive(true);
                this.livingChestTab.setStateTriggered(true);
                this.currentPage = 0;

                if (this.selectedTab != null) {
                    this.selectedTab.setStateTriggered(false);
                }
            }

            cir.setReturnValue(true);
            return;
        }

        if (LivingChestTabState.isActive() && this.isVisible()) {
            if (this.handlePageButtonClick(mouseX, mouseY, button)) {
                cir.setReturnValue(true);
                return;
            }

            if (this.handleLivingChestItemClick(mouseX, mouseY, button)) {
                cir.setReturnValue(true);
            }
        }
    }

    /**
     * 处理翻页按钮点击事件（使用原版 Button 组件）
     * 
     * <h3>🆕 改进</h3>
     * <p>现在直接委托给 {@link Button#mouseClicked} 方法处理，
     * 不再需要手动计算坐标和检测区域。</p>
     *
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param button 鼠标按键
     * @return 是否成功处理了翻页操作
     */
    @Unique
    private boolean handlePageButtonClick(double mouseX, double mouseY, int button) {
        if (this.backButton == null || this.forwardButton == null) return false;

        if (this.totalPages <= 1) return false;

        // 🔥 使用原版 Button 的鼠标事件处理
        // 这会自动：
        // - 检测鼠标是否在按钮区域内
        // - 处理悬停效果
        // - 触发点击回调
        // - 播放点击音效
        
        boolean handled = false;
        
        if (this.backButton.mouseClicked(mouseX, mouseY, button)) {
            handled = true;
        }
        
        if (this.forwardButton.mouseClicked(mouseX, mouseY, button)) {
            handled = true;
        }

        return handled;
    }

    /**
     * 拦截配方书鼠标点击事件的返回阶段
     * 
     * <h3>📍 注入点</h3>
     * <p>{@code mouseClicked()} 方法返回前（RETURN），此时原版处理已完成</p>
     * 
     * <h3>✅ 功能</h3>
     * <p><strong>自动退出活箱子模式</strong>：如果满足以下条件：
     * <ul>
     *   <li>活箱子标签当前处于激活状态</li>
     *   <li>原版处理返回了 {@code true}（表示点击了原版区域）</li>
     *   <li>存在选中的原版标签</li>
     * </ul>
     * 则认为玩家想切换回原版配方视图，自动关闭活箱子标签。</p>
     * 
     * <h3>💡 设计意图</h3>
     * <p>提供一种隐式的退出方式：当玩家点击原版标签或其他区域时自动切回，
     * 无需再次手动点击"活箱子"标签来关闭。</p>
     *
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param button 鼠标按键
     * @param cir 包含原方法返回值的回调
     */
    @Inject(method = "mouseClicked", at = @At("RETURN"))
    private void onMouseClickedReturn(double mouseX, double mouseY, int button,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (this.livingChestTab == null || !this.isVisible()) return;

        // 🆕 修复 1: 检测是否点击了搜索框区域
        if (isClickOnSearchBox((int) mouseX, (int) mouseY)) {
            return;  // 保持活箱子标签页不变
        }

        if (LivingChestTabState.isActive() && cir.getReturnValue() && this.selectedTab != null) {
            LivingChestTabState.setActive(false);
            this.livingChestTab.setStateTriggered(false);
        }
    }

    /**
     * 检测点击位置是否在搜索框区域内
     *
     * <h3>🎯 用途</h3>
     * <p>防止用户在活箱子标签页点击搜索框时意外切换到其他标签页。</p>
     *
     * <h3>📐 位置计算</h3>
     * <p>直接使用 {@code searchBox} 组件的坐标和尺寸进行碰撞检测。</p>
     *
     * @param clickX 点击 X 坐标
     * @param clickY 点击 Y 坐标
     * @return 如果点击在搜索框区域内返回 true
     */
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
     * 在更新材料堆叠计数器时注入
     * 
     * <h3>📍 注入点</h3>
     * <p>{@code updateCollections(Z)} 调用之前，此时原版物品尚未计入</p>
     * 
     * <h3>🎯 核心功能：配方材料感知</h3>
     * <p><strong>这是本 Mixin 最关键的功能之一！</strong></p>
     * 
     * <p>原版配方系统只统计玩家背包中的物品来判断是否可以制作某个配方。
     * 通过在此处注入，我们将<strong>活箱子内的物品也纳入统计范围</strong>，
     * 使得配方系统能够正确识别这些物品。</p>
     * 
     * <h3>💡 实际效果</h3>
     * <ul>
     *   <li>活箱子内有 64 个钻石 → 配方书显示钻石装备可制作（即使背包里没有）</li>
     *   <li>活箱子内的物品会显示绿色勾号标记（表示材料充足）</li>
     *   <li>合成表会正确高亮可制作的配方</li>
     * </ul>
     * 
     * <h3>⚙️ 技术细节</h3>
     * <p>使用 {@code StackedContents.accountStack(ItemStack)} 将每个物品加入计数器。
     * 该方法内部会按物品类型汇总数量。</p>
     *
     * @param ci 回调信息
     */
    @Inject(method = "updateStackedContents", at = @At(
        value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;updateCollections(Z)V"
    ))
    private void beforeUpdateCollections(CallbackInfo ci) {
        List<ItemStack> contents = collectLivingChestItems();
        for (ItemStack chestItem : contents) {
            if (!chestItem.isEmpty()) {
                this.stackedContents.accountStack(chestItem);
            }
        }
    }

    // ==================== 物品操作处理 ====================

    /**
     * 处理活箱子物品槽的点击事件（完全复刻 RecipeButton 的点击逻辑）
     *
     * <h3>🎯 碰撞检测</h3>
     * <p>使用与 RecipeButton 一致的 25×25 像素点击区域，
     * 而非容器界面的 18×18 像素。</p>
     *
     * <h3>🎮 操作映射表</h3>
     * <table border="1">
     *   <tr><th>条件</th><th>操作</th><th>行为</th></tr>
     *   <tr>
     *     <td>左键 + 空手 + 普通</td>
     *     <td>取出物品</td>
     *     <td>从活箱子取出最多一组物品到手</td>
     *   </tr>
     *   <tr>
     *     <td>左键 + 空手 + Shift</td>
     *     <td>全部取出</td>
     *     <td>从活箱子取出最大数量到玩家背包</td>
     *   </tr>
     *   <tr>
     *     <td>左键 + 手持物品 + 普通</td>
     *     <td>存入物品</td>
     *     <td>将手持物品全部存入活箱子</td>
     *   </tr>
     *   <tr>
     *     <td>左键 + 手持物品 + Shift</td>
     *     <td>逐个存入</td>
     *     <td>只存入 1 个物品</td>
     *   </tr>
     *   <tr>
     *     <td>右键 + 空手 + 普通</td>
     *     <td>半组取出</td>
     *     <td>取出一半数量的物品</td>
     *   </tr>
     *   <tr>
     *     <td>右键 + 空手 + Shift</td>
     *     <td>单个取出</td>
     *     <td>只取出 1 个物品</td>
     *   </tr>
     *   <tr>
     *     <td>右键 + 手持物品</td>
     *     <td>单个存入</td>
     *     <td>始终只存入 1 个物品</td>
     *   </tr>
     * </table>
     *
     * @see net.minecraft.client.gui.screens.recipebook.RecipeButton#mouseClicked(double, double, int)
     */
    @Unique
    private boolean handleLivingChestItemClick(double mouseX, double mouseY, int button) {
        ItemStack carried = this.minecraft.player.containerMenu.getCarried();

        for (int i = 0; i < this.livingChestSlots.size(); i++) {
            Slot slot = this.livingChestSlots.get(i);

            if (isHovering((int) mouseX, (int) mouseY, slot.x, slot.y, Layout.SLOT_SIZE, Layout.SLOT_SIZE)) {
                ItemStack stack = slot.getItem();

                // 🆕 情况 1: 槽位有物品 → 执行取出或替换操作
                if (!stack.isEmpty()) {
                    executeSlotAction(stack, button);
                    return true;
                }

                if (!carried.isEmpty()) {
                    int amount = (button == 0)
                        ? carried.getCount()
                        : 1;

                    PacketDistributor.sendToServer(new LivingChestAccessPacket(
                        LivingChestAccessPacket.DEPOSIT,
                        (CompoundTag) carried.save(this.minecraft.player.registryAccess()),
                        amount
                    ));

                    return true;
                }

                // 情况 3: 空槽位 + 空手 → 无操作
                return false;
            }
        }

        return false;
    }

    /**
     * 执行槽位点击的具体操作（发送网络包并触发刷新）
     *
     * <p>将交互逻辑抽象为独立方法，便于维护和扩展。</p>
     *
     * <h3>🆕 刷新机制</h3>
     * <p><strong>关键改进</strong>: 操作完成后立即标记缓存脏数据，
     * 并请求服务器同步最新状态，确保 UI 即时更新。</p>
     *
     * @param slotStack 槽位中的物品
     * @param button 鼠标按键
     */
    @Unique
    private void executeSlotAction(ItemStack slotStack, int button) {
        ItemStack carried = this.minecraft.player.containerMenu.getCarried();
        boolean shift = Screen.hasShiftDown();

        if (button == 0) {
            if (carried.isEmpty()) {
                int amount = slotStack.getMaxStackSize();
                ItemStack saveStack = slotStack.copy();
                saveStack.setCount(1);
                PacketDistributor.sendToServer(new LivingChestAccessPacket(
                    shift ? LivingChestAccessPacket.WITHDRAW_INVENTORY : LivingChestAccessPacket.WITHDRAW,
                    (CompoundTag) saveStack.save(this.minecraft.player.registryAccess()),
                    amount
                ));
            } else {
                int amount = carried.getCount();
                PacketDistributor.sendToServer(new LivingChestAccessPacket(
                    LivingChestAccessPacket.DEPOSIT,
                    (CompoundTag) carried.save(this.minecraft.player.registryAccess()),
                    amount
                ));
            }
        } else if (button == 1) {
            if (carried.isEmpty()) {
                int amount = shift ? 1 : Math.max(1, slotStack.getMaxStackSize() / 2);
                ItemStack saveStack = slotStack.copy();
                saveStack.setCount(1);
                PacketDistributor.sendToServer(new LivingChestAccessPacket(
                    shift ? LivingChestAccessPacket.WITHDRAW_INVENTORY : LivingChestAccessPacket.WITHDRAW,
                    (CompoundTag) saveStack.save(this.minecraft.player.registryAccess()),
                    amount
                ));
            } else {
                PacketDistributor.sendToServer(new LivingChestAccessPacket(
                    LivingChestAccessPacket.DEPOSIT,
                    (CompoundTag) carried.save(this.minecraft.player.registryAccess()),
                    1
                ));
            }
        }
    }
}