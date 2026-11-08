package com.qiqi.li.client.render.tlm;

import com.github.tartaricacid.touhoulittlemaid.init.InitDataComponent;
import com.github.tartaricacid.touhoulittlemaid.init.InitItems;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.client.mixin.LivingItemSlotWrapperAccessor;
import com.qiqi.li.living.api.LivingItemManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.qiqi.li.client.render.tlm.SoulSlabPreviewMoves.Position;
import com.qiqi.li.client.render.tlm.SoulSlabPreviewMoves.Snapshot;

/** 当前 screen/level 的私有实例缓存；仅当帧实际抑制过图标才绘制。 */
final class SoulSlabPreviewCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/SoulSlabPreview");
    private AbstractContainerScreen<?> screen;
    private ClientLevel level;
    private GuiGraphics frameGraphics;
    private Map<Position, Entry> entries = new HashMap<>();
    private final Map<Position, Drawing> frame = new LinkedHashMap<>();
    private int ticks;

    private static final class Entry {
        final Slot slot;
        final ItemStack snapshot;
        SoulSlabMaidPreview preview;
        boolean attempted;
        int seenTick = Integer.MIN_VALUE;
        Entry(Slot slot, ItemStack snapshot) { this.slot = slot; this.snapshot = snapshot; }
    }
    private record Drawing(Entry entry, ItemStack stack, PoseStack origin, int x, int y, int seed, String countString) {}

    void beginFrame(AbstractContainerScreen<?> current, GuiGraphics graphics) {
        reconcile(current);
        frame.clear();
        frameGraphics = graphics;
    }

    private void reconcile(AbstractContainerScreen<?> current) {
        Minecraft mc = Minecraft.getInstance();
        if (screen != current || level != mc.level) {
            clear();
            screen = current;
            level = mc.level;
        }
        if (current == null || level == null) return;
        Map<Position, Entry> next = new LinkedHashMap<>();
        List<Snapshot> added = new ArrayList<>(), removed = new ArrayList<>();
        for (Slot slot : current.getMenu().slots) {
            if (!slot.isActive() || slot.isFake()) continue;
            ItemStack stack = slot.getItem();
            if (!eligible(stack)) continue;
            Position position = position(slot);
            if (next.containsKey(position)) continue;
            ItemStack snapshot = stable(stack);
            Entry old = entries.get(position);
            if (old != null && ItemStack.matches(old.snapshot, snapshot)) {
                Entry same = new Entry(slot, snapshot);
                same.preview = old.preview;
                same.attempted = old.attempted;
                same.seenTick = old.seenTick;
                next.put(position, same);
            } else {
                next.put(position, new Entry(slot, snapshot));
                added.add(new Snapshot(position, snapshot));
            }
        }
        entries.forEach((position, entry) -> {
            Entry replacement = next.get(position);
            if (replacement == null || !ItemStack.matches(entry.snapshot, replacement.snapshot)) {
                removed.add(new Snapshot(position, entry.snapshot));
            }
        });
        Map<Position, Position> moves = SoulSlabPreviewMoves.match(removed, added);
        moves.entrySet().removeIf(move -> next.containsKey(move.getValue()) || entries.containsKey(move.getKey()));
        moves.forEach((destination, source) -> {
            Entry old = entries.get(source), target = next.get(destination);
            target.preview = old.preview;
            target.attempted = old.attempted;
            target.seenTick = old.seenTick;
            if (target.preview != null && !target.preview.disabled()) target.preview.move(old.slot.x, old.slot.y);
        });
        for (Snapshot source : removed) {
            if (!moves.containsValue(source.position())) {
                SoulSlabMaidPreview preview = entries.get(source.position()).preview;
                if (preview != null) preview.dispose();
            }
        }
        entries = next;
    }

    private static boolean eligible(ItemStack stack) {
        return !stack.isEmpty() && stack.is(InitItems.SMART_SLAB_HAS_MAID.get())
                && LivingItemManager.isLivingItem(stack) && stack.has(InitDataComponent.MAID_INFO);
    }

    static ItemStack stable(ItemStack stack) {
        return SoulSlabPreviewMoves.stable(stack);
    }

    private static Position position(Slot slot) {
        Slot physical = slot instanceof LivingItemSlotWrapperAccessor accessor
                ? accessor.living_item$getWrappedTarget() : slot;
        Object owner = physical instanceof SlotItemHandler handler ? handler.getItemHandler() : physical.container;
        return new Position(owner, physical.getContainerSlot(), slot.x, slot.y);
    }

    boolean prepare(AbstractContainerScreen<?> current, GuiGraphics graphics, ItemStack stack,
                    Slot slot, int x, int y, int seed, String countString) {
        if (screen != current || level != Minecraft.getInstance().level || frameGraphics != graphics
                || slot.isFake() || !eligible(stack) || !ItemStack.matches(stack, slot.getItem())) return false;
        var mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getCooldowns().getCooldownPercent(stack.getItem(),
                mc.getTimer().getGameTimeDeltaPartialTick(true)) > 0) return false;
        Position position = position(slot);
        Entry entry = entries.get(position);
        if (entry == null || !ItemStack.matches(entry.snapshot, stable(stack))) return false;
        Drawing previous = frame.get(position);
        if (previous != null) return previous.x == x && previous.y == y;
        if (!entry.attempted) {
            entry.attempted = true;
            try {
                entry.preview = new SoulSlabMaidPreview(stack, level);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn("Soul slab preview preparation failed at menu slot {}; keeping original icon", slot.index, failure);
            }
        }
        if (entry.preview == null || entry.preview.disabled()) return false;
        PoseStack origin = new PoseStack();
        origin.last().pose().set(graphics.pose().last().pose());
        origin.last().normal().set(graphics.pose().last().normal());
        frame.put(position, new Drawing(entry, stack.copy(), origin, x, y, seed, countString));
        entry.seenTick = ticks;
        return true;
    }

    void render(AbstractContainerScreen<?> current, GuiGraphics graphics) {
        if (current != screen || graphics != frameGraphics) return;
        try {
            for (Drawing drawing : frame.values()) {
                if (!ItemStack.matches(drawing.stack, drawing.entry.slot.getItem())) continue;
                try {
                    renderOne(graphics, drawing);
                } catch (RuntimeException | LinkageError failure) {
                    drawing.entry.preview.disable();
                    LOGGER.warn("Soul slab preview drawing failed at menu slot {}; restored original icon", drawing.entry.slot.index, failure);
                    fallback(graphics, drawing);
                }
            }
        } finally {
            frame.clear();
            frameGraphics = null;
        }
    }

    private void renderOne(GuiGraphics graphics, Drawing drawing) {
        // Foreground 接缝的标准恢复基线是 3D item 光照；不改 dispatcher/options。
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        float[] color = RenderSystem.getShaderColor().clone();
        var shader = RenderSystem.getShader();
        try (MemoryStack memory = MemoryStack.stackPush()) {
            var box = memory.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
            try {
                graphics.flush();
                float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
                int px = Mth.floor(drawing.entry.preview.displayX(drawing.x, partial));
                int py = Mth.floor(drawing.entry.preview.displayY(drawing.y, partial));
                int height = drawing.stack.getCount() != 1 || drawing.countString != null ? 9
                        : drawing.stack.isBarVisible() ? 13 : 16;
                graphics.enableScissor(screen.getGuiLeft() + px, screen.getGuiTop() + py,
                        screen.getGuiLeft() + px + 16, screen.getGuiTop() + py + height);
                try {
                    drawing.entry.preview.render(graphics, drawing.origin, drawing.x, drawing.y, height);
                } finally {
                    graphics.disableScissor();
                }
            } finally {
                Lighting.setupFor3DItems();
                RenderSystem.setShader(() -> shader);
                RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
                RenderSystem.depthFunc(depthFunc);
                RenderSystem.depthMask(depthMask);
                if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
                if (scissor) RenderSystem.enableScissor(box.get(0), box.get(1), box.get(2), box.get(3));
                else RenderSystem.disableScissor();
            }
        }
    }

    private void fallback(GuiGraphics graphics, Drawing drawing) {
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        graphics.pose().pushPose();
        try {
            graphics.pose().last().pose().set(drawing.origin.last().pose());
            graphics.pose().last().normal().set(drawing.origin.last().normal());
            graphics.renderItem(drawing.stack, drawing.x, drawing.y, drawing.seed);
            graphics.renderItemDecorations(Minecraft.getInstance().font, drawing.stack,
                    drawing.x, drawing.y, drawing.countString);
            Slot hovered = screen.getSlotUnderMouse();
            if (hovered != null && hovered.isHighlightable() && position(hovered).equals(position(drawing.entry.slot))) {
                AbstractContainerScreen.renderSlotHighlight(graphics, drawing.x, drawing.y, 0, screen.getSlotColor(hovered.index));
            }
        } finally {
            graphics.pose().popPose();
            Lighting.setupFor3DItems();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        }
    }

    void tick() {
        var mc = Minecraft.getInstance();
        AbstractContainerScreen<?> current = mc.screen instanceof AbstractContainerScreen<?> container ? container : null;
        reconcile(current);
        ticks++;
        for (Entry entry : entries.values()) {
            if (entry.preview == null) continue;
            try {
                entry.preview.tick(ticks - entry.seenTick <= 2);
            } catch (RuntimeException | LinkageError failure) {
                entry.preview.disable();
                LOGGER.warn("Soul slab preview animation/audio failed; keeping original icon", failure);
            }
        }
    }

    void clear() {
        entries.values().forEach(entry -> { if (entry.preview != null) entry.preview.dispose(); });
        entries.clear();
        frame.clear();
        frameGraphics = null;
        screen = null;
        level = null;
        ticks = 0;
    }
}
