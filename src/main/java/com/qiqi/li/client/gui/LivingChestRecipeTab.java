package com.qiqi.li.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qiqi.li.client.util.LivingChestTabState;
import com.qiqi.li.client.util.LivingChestTabSearch;
import com.qiqi.li.living.domain.chest.LivingChestFunction;
import com.qiqi.li.network.LivingChestAccessPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StateSwitchingButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 活箱子标签页视图 —— 配方书「活箱子」标签的全部有状态 UI：标签按钮、
 * 内容网格（5×4 分页）、搜索过滤、点击存取（发包 {@link LivingChestAccessPacket}）。
 *
 * <p>2026-10-29 从 {@code RecipeBookComponentMixin} 抽出（对齐「mixin 薄、逻辑放普通类」惯例）。
 * mixin 只保留 @Shadow 访问与注入点委托；本类不反向持有 mixin，
 * 原版配方书的私有状态（width / xOffset / searchBox / selectedTab 等）由 mixin 以参数传入。</p>
 *
 * <p>实例生命周期：由 mixin 在首次 {@code initVisuals} 注入时懒创建 ——
 * 创建前所有入口都必须被 mixin 的 null 检查挡住（等价于改造前对按钮的 null 检查）。</p>
 */
public class LivingChestRecipeTab {

    /** 标签按钮精灵（复用原版配方书标签纹理，保持视觉一致） */
    private static final WidgetSprites LIVING_CHEST_TAB_SPRITES = new WidgetSprites(
        ResourceLocation.withDefaultNamespace("recipe_book/tab_selected"),
        ResourceLocation.withDefaultNamespace("recipe_book/tab"),
        ResourceLocation.withDefaultNamespace("recipe_book/tab_selected"),
        ResourceLocation.withDefaultNamespace("recipe_book/tab_selected")
    );

    /** 物品槽背景纹理（原版"可制作"槽位样式） */
    private static final ResourceLocation SLOT_BG_SPRITE =
        ResourceLocation.withDefaultNamespace("recipe_book/slot_craftable");

    private static final ResourceLocation PAGE_FORWARD_SPRITE =
        ResourceLocation.withDefaultNamespace("recipe_book/page_forward");
    private static final ResourceLocation PAGE_FORWARD_HL_SPRITE =
        ResourceLocation.withDefaultNamespace("recipe_book/page_forward_highlighted");
    private static final ResourceLocation PAGE_BACKWARD_SPRITE =
        ResourceLocation.withDefaultNamespace("recipe_book/page_backward");
    private static final ResourceLocation PAGE_BACKWARD_HL_SPRITE =
        ResourceLocation.withDefaultNamespace("recipe_book/page_backward_highlighted");

    /** 物品网格 / 分页控件布局常量（对齐原版 RecipeButton 与 RecipeBookPage 的尺寸位置） */
    private static final class Layout {
        /** 单个物品槽大小（25×25 像素，与 RecipeButton 一致） */
        static final int SLOT_SIZE = 25;
        static final int SLOT_GAP = 0;
        static final int COLUMNS = 5;
        static final int ROWS = 4;
        static final int TOTAL_SLOTS = COLUMNS * ROWS;

        /** 网格左 / 上边缘偏移；物品图标在槽位内的偏移 */
        static final int GRID_OFFSET_X = 11;
        static final int GRID_OFFSET_Y = 31;
        static final int ITEM_OFFSET = 4;

        /** 分页按钮与页码文本位置（与 RecipeBookPage 一致） */
        static final int BUTTON_Y_OFFSET = 137;
        static final int BACK_BUTTON_X_OFFSET = 38;
        static final int FORWARD_BUTTON_X_OFFSET = 93;
        static final int PAGE_TEXT_Y_OFFSET = 141;
        static final int PAGE_TEXT_X_CENTER_OFFSET = 73;

        static final int BUTTON_WIDTH = 12;
        static final int BUTTON_HEIGHT = 17;
    }

    private StateSwitchingButton livingChestTab;
    private int currentPage;
    private int totalPages = 1;
    private List<Slot> livingChestSlots = new ArrayList<>();
    private int hoveredSlotIndex = -1;
    private Button backButton;
    private Button forwardButton;
    private String lastSearchText = "";

    // ==================== 静态数据源 ====================

    /**
     * 收集玩家背包中所有活箱子（count&gt;1 锁存取，跳过）的物品内容。
     * 供内容网格与材料统计（{@code StackedContents.accountStack}）共用。
     */
    public static List<ItemStack> collectLivingChestItems() {
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

    // ==================== 生命周期 ====================

    /**
     * 配方书 {@code initVisuals} 尾部调用：懒创建标签按钮、重置标签状态并定位。
     *
     * @param tabX 标签 X（mixin 按 width/xOffset 算出）
     * @param tabY 标签 Y 基准（mixin 按 height 算出）；实际 Y = tabY + 27 × 原版标签数（垂直堆叠）
     * @param tabCount 原版标签按钮数
     */
    public void onInitVisuals(int tabX, int tabY, int tabCount) {
        if (this.livingChestTab == null) {
            this.livingChestTab = new StateSwitchingButton(0, 0, 35, 27, false);
            this.livingChestTab.initTextureValues(LIVING_CHEST_TAB_SPRITES);
        }
        LivingChestTabState.setActive(false);
        this.livingChestTab.setStateTriggered(false);
        this.currentPage = 0;

        this.livingChestTab.setPosition(tabX, tabY + 27 * tabCount);
    }

    // ==================== 渲染 ====================

    /**
     * 配方书 render 中 PoseStack.popPose 之前调用：先同步可见性（配方书不可见 ⇒ 自动退出），
     * 再由 mixin 传入 left/top 后继续 {@link #renderContents}。
     *
     * @return 是否继续渲染内容网格（标签未激活 ⇒ false）
     */
    public boolean beforePopRender(boolean bookVisible) {
        if (LivingChestTabState.isActive() && !bookVisible) {
            LivingChestTabState.setActive(false);
            this.livingChestTab.setStateTriggered(false);
        }
        return LivingChestTabState.isActive();
    }

    /**
     * 渲染活箱子内容网格（支持搜索过滤与分页）。
     *
     * <p>渲染口径 100% 对齐原版：{@code renderFakeItem} + 25×25 槽位 + (4,4) 图标偏移 +
     * recipe_book 纹理 + 5×4 网格 + 翻页控件，与 RecipeButton / RecipeBookPage 一致。</p>
     *
     * @param left 配方书左边界绝对坐标（mixin 按 width/xOffset 算出）
     * @param top 配方书上边界绝对坐标（mixin 按 height 算出）
     * @param rawSearchText 原版搜索框原文（未归一化；允许 null）
     */
    public void renderContents(GuiGraphics guiGraphics, int left, int top, int mouseX, int mouseY, String rawSearchText) {
        String currentSearch = sanitizeSearchText(rawSearchText);
        if (!currentSearch.equals(this.lastSearchText)) {
            this.lastSearchText = currentSearch;
            this.currentPage = 0;
        }

        List<ItemStack> contents = collectLivingChestItems();
        List<ItemStack> displayContents = LivingChestTabSearch.filter(contents, currentSearch);

        int pages = Math.max(1, (displayContents.size() + Layout.TOTAL_SLOTS - 1) / Layout.TOTAL_SLOTS);
        this.totalPages = pages;
        if (this.currentPage >= pages) {
            this.currentPage = pages - 1;
        }
        int startIdx = this.currentPage * Layout.TOTAL_SLOTS;

        updateLivingChestSlots(left, top, displayContents, startIdx);

        this.hoveredSlotIndex = -1;
        for (int i = 0; i < this.livingChestSlots.size(); i++) {
            Slot slot = this.livingChestSlots.get(i);
            renderSlot(guiGraphics, slot, mouseX, mouseY, i);
        }

        if (this.hoveredSlotIndex >= 0) {
            Slot hoveredSlot = this.livingChestSlots.get(this.hoveredSlotIndex);
            if (!hoveredSlot.getItem().isEmpty()) {
                renderSlotTooltip(guiGraphics, hoveredSlot, mouseX, mouseY);
            }
        }

        if (totalPages > 1) {
            setupPageButtons(left, top, totalPages);

            if (this.backButton != null) {
                this.backButton.render(guiGraphics, mouseX, mouseY, 0);
            }
            if (this.forwardButton != null) {
                this.forwardButton.render(guiGraphics, mouseX, mouseY, 0);
            }

            Component pageComponent = Component.translatable("gui.recipebook.page", this.currentPage + 1, totalPages);
            int textWidth = Minecraft.getInstance().font.width(pageComponent);
            int textX = left - textWidth / 2 + Layout.PAGE_TEXT_X_CENTER_OFFSET;
            guiGraphics.drawString(Minecraft.getInstance().font, pageComponent, textX, top + Layout.PAGE_TEXT_Y_OFFSET, -1, false);
        }
    }

    /**
     * 配方书 render 尾部调用：渲染标签按钮 + 箱子图标。
     * 须禁用深度测试，防止按钮被其它 UI 元素遮挡。
     */
    public void renderTabButton(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        RenderSystem.disableDepthTest();
        this.livingChestTab.render(guiGraphics, mouseX, mouseY, partialTick);
        RenderSystem.enableDepthTest();

        this.renderLivingChestIcon(guiGraphics);
    }

    /** 标签上的箱子图标（renderFakeItem 假物品；选中时左移 2px 模拟按压） */
    private void renderLivingChestIcon(GuiGraphics guiGraphics) {
        int x = this.livingChestTab.getX() + 9 + (this.livingChestTab.isStateTriggered() ? -2 : 0);
        int y = this.livingChestTab.getY() + 5;
        guiGraphics.renderFakeItem(new ItemStack(Items.CHEST), x, y);
    }

    /** 创建 / 更新本页 20 个虚拟 Slot（坐标为绝对屏幕坐标 —— 漏加 left/top 会画到屏幕左上角） */
    private void updateLivingChestSlots(int left, int top, List<ItemStack> contents, int startIdx) {
        this.livingChestSlots.clear();
        for (int slot = 0; slot < Layout.TOTAL_SLOTS; slot++) {
            int contentIdx = startIdx + slot;
            ItemStack stack = (contentIdx < contents.size()) ? contents.get(contentIdx) : ItemStack.EMPTY;
            this.livingChestSlots.add(createLivingChestSlot(left, top, slot, stack));
        }
    }

    /**
     * 创建单个只读展示槽位（不绑定 ContainerMenu）。
     * 坐标公式对齐原版 RecipeBookPage：x = left + 11 + 25 × (i % 5)，y = top + 31 + 25 × (i / 5)。
     */
    private Slot createLivingChestSlot(int left, int top, final int slotIndex, final ItemStack stack) {
        int col = slotIndex % Layout.COLUMNS;
        int row = slotIndex / Layout.COLUMNS;

        int x = left + Layout.GRID_OFFSET_X + col * (Layout.SLOT_SIZE + Layout.SLOT_GAP);
        int y = top + Layout.GRID_OFFSET_Y + row * (Layout.SLOT_SIZE + Layout.SLOT_GAP);

        return new Slot(null, slotIndex, x, y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;  // 只读展示，不允许拖放
            }

            @Override
            public boolean mayPickup(Player player) {
                return true;  // 允许点击取出
            }

            @Override
            public ItemStack getItem() {
                return stack.copy();  // 物品副本
            }

            @Override
            public void setChanged() {
            }

            @Override
            public boolean isActive() {
                return !stack.isEmpty();
            }
        };
    }

    /** 渲染单个槽位（背景纹理 + renderFakeItem + 数量/耐久装饰 + 悬停高亮） */
    private void renderSlot(GuiGraphics guiGraphics, Slot slot, int mouseX, int mouseY, int slotIndex) {
        int slotX = slot.x;
        int slotY = slot.y;

        guiGraphics.blitSprite(SLOT_BG_SPRITE, slotX, slotY, Layout.SLOT_SIZE, Layout.SLOT_SIZE);

        ItemStack stack = slot.getItem();
        if (!stack.isEmpty()) {
            int itemX = slotX + Layout.ITEM_OFFSET;
            int itemY = slotY + Layout.ITEM_OFFSET;

            guiGraphics.renderFakeItem(stack, itemX, itemY);
            guiGraphics.renderItemDecorations(Minecraft.getInstance().font, stack, itemX, itemY);
        }

        if (isHovering(mouseX, mouseY, slotX, slotY, Layout.SLOT_SIZE, Layout.SLOT_SIZE)) {
            this.hoveredSlotIndex = slotIndex;
            guiGraphics.fill(slotX, slotY, slotX + Layout.SLOT_SIZE, slotY + Layout.SLOT_SIZE, 0x80FFFFFF);
        }
    }

    /** 悬浮提示：复用 {@link GuiGraphics#renderTooltip}，自动带名称 / Lore / 附魔 / 耐久 / 屏幕边界检测 */
    private void renderSlotTooltip(GuiGraphics guiGraphics, Slot slot, int mouseX, int mouseY) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return;

        guiGraphics.renderTooltip(Minecraft.getInstance().font, stack, mouseX, mouseY);
    }

    /** 创建 / 定位翻页按钮（位置尺寸对齐原版 RecipeBookPage：上一页 x+38、下一页 x+93、y+137） */
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

    private static boolean isHovering(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static String sanitizeSearchText(String raw) {
        return raw != null ? raw.trim().toLowerCase(Locale.ROOT) : "";
    }

    // ==================== 鼠标事件 ====================

    /**
     * 标签按钮点击（配方书 mouseClicked HEAD 阶段调用）。
     * 未激活 → 激活：记状态、重置分页，并取消原版标签选中态。
     *
     * @param vanillaSelectedTab 原版配方书当前选中标签（mixin 从 @Shadow 传入，可为 null）
     * @return 点击是否被标签按钮消费（消费 ⇒ mixin 应短路原版处理）
     */
    public boolean onTabClicked(double mouseX, double mouseY, int button, RecipeBookTabButton vanillaSelectedTab) {
        if (!this.livingChestTab.mouseClicked(mouseX, mouseY, button)) return false;

        if (!LivingChestTabState.isActive()) {
            LivingChestTabState.setActive(true);
            this.livingChestTab.setStateTriggered(true);
            this.currentPage = 0;

            if (vanillaSelectedTab != null) {
                vanillaSelectedTab.setStateTriggered(false);
            }
        }
        return true;
    }

    /**
     * 配方书 mouseClicked RETURN 阶段调用：原版处理了本次点击 ⇒ 视为切回原版视图，自动退出。
     * 保留原判据：须存在原版选中标签才退出（selectedTab == null 时不动作）。
     */
    public void deactivateIfVanillaClick(boolean vanillaHandled, RecipeBookTabButton vanillaSelectedTab) {
        if (LivingChestTabState.isActive() && vanillaHandled && vanillaSelectedTab != null) {
            LivingChestTabState.setActive(false);
            this.livingChestTab.setStateTriggered(false);
        }
    }

    /** 翻页按钮点击：委托给原版 {@link Button#mouseClicked}（区域检测 / 悬停 / 音效均由其处理） */
    public boolean onPageButtonClicked(double mouseX, double mouseY, int button) {
        if (this.backButton == null || this.forwardButton == null) return false;
        if (this.totalPages <= 1) return false;

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
     * 内容槽位点击 → 发包存取。25×25 命中区域（对齐 RecipeButton，而非容器界面的 18×18）。
     *
     * <p>操作映射：左键空手=取出一组（Shift=全部取出入背包）；左键手持=全部存入（Shift=存 1）；
     * 右键空手=取半组（Shift=取 1）；右键手持=存 1。空槽 + 空手 ⇒ 无操作。</p>
     */
    public boolean onSlotClicked(double mouseX, double mouseY, int button) {
        ItemStack carried = Minecraft.getInstance().player.containerMenu.getCarried();

        for (int i = 0; i < this.livingChestSlots.size(); i++) {
            Slot slot = this.livingChestSlots.get(i);

            if (isHovering((int) mouseX, (int) mouseY, slot.x, slot.y, Layout.SLOT_SIZE, Layout.SLOT_SIZE)) {
                ItemStack stack = slot.getItem();

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
                        (CompoundTag) carried.save(Minecraft.getInstance().player.registryAccess()),
                        amount
                    ));

                    return true;
                }

                return false;
            }
        }

        return false;
    }

    /** 执行槽位点击的具体操作（发包 + 动作常量见 {@link LivingChestAccessPacket}） */
    private void executeSlotAction(ItemStack slotStack, int button) {
        ItemStack carried = Minecraft.getInstance().player.containerMenu.getCarried();
        boolean shift = Screen.hasShiftDown();

        if (button == 0) {
            if (carried.isEmpty()) {
                int amount = slotStack.getMaxStackSize();
                ItemStack saveStack = slotStack.copy();
                saveStack.setCount(1);
                PacketDistributor.sendToServer(new LivingChestAccessPacket(
                    shift ? LivingChestAccessPacket.WITHDRAW_INVENTORY : LivingChestAccessPacket.WITHDRAW,
                    (CompoundTag) saveStack.save(Minecraft.getInstance().player.registryAccess()),
                    amount
                ));
            } else {
                int amount = carried.getCount();
                PacketDistributor.sendToServer(new LivingChestAccessPacket(
                    LivingChestAccessPacket.DEPOSIT,
                    (CompoundTag) carried.save(Minecraft.getInstance().player.registryAccess()),
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
                    (CompoundTag) saveStack.save(Minecraft.getInstance().player.registryAccess()),
                    amount
                ));
            } else {
                PacketDistributor.sendToServer(new LivingChestAccessPacket(
                    LivingChestAccessPacket.DEPOSIT,
                    (CompoundTag) carried.save(Minecraft.getInstance().player.registryAccess()),
                    1
                ));
            }
        }
    }
}
