package com.qiqi.li.client.gui;

import com.mojang.logging.LogUtils;
import com.qiqi.li.LivingItem;
import com.qiqi.li.LivingItemClient;
import com.qiqi.li.client.mixinsupport.MutableSpriteSpriteIconButton;
import com.qiqi.li.network.LivingTagPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;

import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;


public class LivingButton extends SpriteIconButton.CenteredIcon {
    private static final int WIDTH = 16;
    private static final int HEIGHT = 8;
    private static final int SPRITE_WIDTH = 16;
    private static final int SPRITE_HEIGHT = 8;
    private static final Component ANNOTATION = Component.translatable("text.livingitem.living");

    private static final ResourceLocation SPRITE = LivingItem.id("living_button");
    private static final ResourceLocation HOVERED_SPRITE = LivingItem.id("living_button_hovered");
//    private static final WidgetSprites WIDGET_SPRITES = new WidgetSprites(SPRITE,HOVERED_SPRITE);

    private final AbstractContainerMenu menu;
    private final Slot slot;

    public static final Logger LOGGER = LogUtils.getLogger();

    public LivingButton(int x, int y, AbstractContainerMenu menu, Slot slot) {
        super(WIDTH, HEIGHT, ANNOTATION, SPRITE_WIDTH, SPRITE_HEIGHT,SPRITE,LivingButton::onPress, null);

        this.menu = menu;
        this.slot = slot;
        this.setX(x);
        this.setY(y);
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        ((MutableSpriteSpriteIconButton) this).setSprite(this.isHovered() ? HOVERED_SPRITE : SPRITE);
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
    }

    private static void onPress(Button button) {
        if (button instanceof LivingButton livingButton) {
            // 获取玩家当前拿着的物品（悬浮在鼠标上的物品）
            ItemStack carriedItem = livingButton.menu.getCarried();
            
            if (!carriedItem.isEmpty()) {
                // 发送网络包到服务端（服务端会直接处理玩家拿着的物品）
                LivingTagPacket packet = new LivingTagPacket();
                PacketDistributor.sendToServer(packet);
                LOGGER.info("发送 living tag 数据包，物品: {}", carriedItem.getItem().getName(carriedItem).getString());
            } else {
                LOGGER.info("没有拿着任何物品");
            }
        } else {
            throw new IllegalStateException("LivingButton::onPress called with non LivingButton argument");
        }
    }
}