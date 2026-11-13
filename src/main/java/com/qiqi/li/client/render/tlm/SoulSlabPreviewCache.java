package com.qiqi.li.client.render.tlm;

import com.github.tartaricacid.touhoulittlemaid.init.InitDataComponent;
import com.github.tartaricacid.touhoulittlemaid.init.InitItems;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.client.mixin.LivingItemSlotWrapperAccessor;
import com.qiqi.li.client.render.LivingSoulSlabHotbarPreview;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.compat.tlm.TlmSoulData;
import com.qiqi.li.living.domain.maid.LivingSoulSlabFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
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
import com.qiqi.li.client.render.tlm.SoulSlabPreviewSurface.Kind;

/** 当前世界/玩家的预览缓存；HUD、菜单槽与真实光标共用同一实例身份。 */
final class SoulSlabPreviewCache implements LivingSoulSlabHotbarPreview.Renderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/SoulSlabPreview");

    private AbstractContainerScreen<?> screen;
    private ClientLevel level;
    private LocalPlayer player;
    private GuiGraphics frameGraphics;
    private SoulSlabGuiFrame.Token frameToken;
    private Map<Position, Entry> entries = new HashMap<>();
    private final Map<Position, Drawing> frame = new LinkedHashMap<>();
    private int ticks;
    private boolean hotbarFrame;

    private record Placement(Kind kind, Position position, Slot slot, ItemStack stack, int x, int y) {}

    private static final class Entry {
        final Placement placement;
        final ItemStack reference;
        final ItemStack snapshot;
        SoulSlabMaidPreview preview;
        SoulSlabFeedFeedback feedFeedback = new SoulSlabFeedFeedback();
        SoulSlabBooleanFeedback sittingFeedback = new SoulSlabBooleanFeedback();
        boolean feedPending;
        boolean responsePending;
        boolean attempted;
        int seenTick = Integer.MIN_VALUE;

        Entry(Placement placement, ItemStack reference, ItemStack snapshot) {
            this.placement = placement;
            this.reference = reference;
            this.snapshot = snapshot;
        }
    }

    private record Drawing(Entry entry, ItemStack source, ItemStack snapshot,
                           PoseStack origin, PoseStack itemPose, int x, int y, int seed) {}

    void beginFrame(AbstractContainerScreen<?> current, GuiGraphics graphics) {
        reconcile(current);
        clearBegging();
        clearContainerFrame();
        frameGraphics = graphics;
        frameToken = SoulSlabGuiFrame.begin();
        if (current == null || level == null) return;
        Map<Position, Integer> displayCounts = new HashMap<>();
        for (Slot slot : current.getMenu().slots) {
            Slot physical = physicalSlot(slot);
            if (!slot.isActive() || slot.isFake() || !SoulSlabGuiSlots.hasStableStackReference(slot, physical)) continue;
            if (eligible(slot.getItem())) displayCounts.merge(position(slot), 1, Integer::sum);
        }
        PoseStack origin = SoulSlabGuiPose.slotOrigin(graphics.pose(), current.getGuiLeft(), current.getGuiTop());
        for (var mapped : entries.entrySet()) {
            Entry entry = mapped.getValue();
            Slot slot = entry.placement.slot();
            if (entry.placement.kind() != Kind.GUI_SLOT || slot == null) continue;
            Slot physical = physicalSlot(slot);
            ItemStack stack = slot.getItem();
            if (!slot.isActive() || slot.isFake() || !SoulSlabGuiSlots.hasStableStackReference(slot, physical)
                    || !eligible(stack) || displayCounts.getOrDefault(mapped.getKey(), 0) != 1
                    || coolingDown(stack)) continue;
            if (!prepare(entry, stack) || entry.preview.disabled()) continue;
            PoseStack itemPose = SoulSlabGuiPose.itemModelPose(origin, slot.x, slot.y);
            frame.put(mapped.getKey(), new Drawing(entry, stack, stack.copy(), copy(origin), itemPose,
                    slot.x, slot.y, slot.x + slot.y * current.getXSize()));
            SoulSlabGuiFrame.register(stack, frameToken, itemPose);
        }
    }

    private boolean prepare(Entry entry, ItemStack stack) {
        if (!entry.attempted) {
            entry.attempted = true;
            try {
                entry.preview = new SoulSlabMaidPreview(stack, level);
            } catch (RuntimeException | LinkageError failure) {
                int index = entry.placement.slot() == null ? -1 : entry.placement.slot().index;
                LOGGER.warn("Soul slab preview preparation failed at menu slot {}; keeping original icon", index, failure);
            }
        }
        return entry.preview != null;
    }

    private static PoseStack copy(PoseStack source) {
        return SoulSlabGuiPose.copy(source);
    }

    private void reconcile(AbstractContainerScreen<?> current) {
        Minecraft mc = Minecraft.getInstance();
        if (level != mc.level || player != mc.player) {
            clear();
            level = mc.level;
            player = mc.player;
        }
        screen = current;
        if (level == null || player == null) return;

        Map<Position, Placement> placements = placements(current);
        Map<Position, Entry> next = new LinkedHashMap<>();
        List<Snapshot> added = new ArrayList<>();
        List<Snapshot> removed = new ArrayList<>();
        for (Placement placement : placements.values()) {
            ItemStack snapshot = stable(placement.stack());
            next.put(placement.position(), new Entry(placement, placement.stack(), snapshot));
            added.add(new Snapshot(placement.position(), snapshot, placement.stack()));
        }
        entries.forEach((position, entry) ->
                removed.add(new Snapshot(position, entry.snapshot, entry.reference)));

        Map<Position, Position> moves = SoulSlabPreviewMoves.match(removed, added);
        moves.forEach((destination, source) -> {
            Entry old = entries.get(source);
            Entry target = next.get(destination);
            if (old == null || target == null) return;
            transfer(target, old);
            SoulSlabPreviewSurface.Motion motion = SoulSlabPreviewSurface.transition(
                    old.placement.kind(), target.placement.kind(), source.equals(destination));
            if (target.preview != null && !target.preview.disabled()) {
                if (motion == SoulSlabPreviewSurface.Motion.SETTLE) {
                    target.preview.settle();
                } else if (motion == SoulSlabPreviewSurface.Motion.MOVE) {
                    target.preview.move(old.placement.x(), old.placement.y(),
                            target.placement.x(), target.placement.y());
                }
            }
            if (target.placement.kind() == Kind.CURSOR && old.placement.kind() != Kind.CURSOR
                    && SoulSlabPreviewVisibility.recentlyDrawn(ticks, old.seenTick)) {
                target.responsePending = true;
            }
        });
        for (Snapshot source : removed) {
            if (!moves.containsValue(source.position())) {
                Entry old = entries.get(source.position());
                if (old != null && old.preview != null) old.preview.dispose();
            }
        }
        entries = next;
        observeEntries();
    }

    private Map<Position, Placement> placements(AbstractContainerScreen<?> current) {
        Map<Position, Placement> result = new LinkedHashMap<>();
        addHudPlacements(result);
        if (current != null) addGuiPlacements(result, current);
        if (current != null) {
            ItemStack carried = current.getMenu().getCarried();
            if (eligible(carried)) {
                Position cursor = Position.cursor(current.getMenu());
                result.put(cursor, new Placement(Kind.CURSOR, cursor, null, carried, 0, 0));
            }
        }
        return result;
    }

    private void addHudPlacements(Map<Position, Placement> result) {
        for (int slot = 0; slot < 9; slot++) addHudPlacement(result, slot, player.getInventory().getItem(slot));
        addHudPlacement(result, SoulSlabHotbarSlots.OFFHAND_SLOT, player.getOffhandItem());
    }

    private void addHudPlacement(Map<Position, Placement> result, int slot, ItemStack stack) {
        if (!eligible(stack)) return;
        int logicalX = slot < 9 ? slot * 20 : 10 * 20;
        Position position = new Position(player.getInventory(), slot, logicalX, 0);
        result.put(position, new Placement(Kind.HUD_SLOT, position, null, stack, logicalX, 0));
    }

    private void addGuiPlacements(Map<Position, Placement> result, AbstractContainerScreen<?> current) {
        for (Slot slot : current.getMenu().slots) {
            Slot physical = physicalSlot(slot);
            if (!slot.isActive() || slot.isFake() || !SoulSlabGuiSlots.hasStableStackReference(slot, physical)) continue;
            ItemStack stack = slot.getItem();
            if (!eligible(stack)) continue;
            Position position = position(slot);
            result.put(position,
                    new Placement(Kind.GUI_SLOT, position, slot, stack, slot.x, slot.y));
        }
    }

    private void observeEntries() {
        for (Entry entry : entries.values()) {
            entry.feedPending |= entry.feedFeedback.observe(TlmSoulData.pendingFavor(entry.reference));
            boolean sitting = LivingSoulSlabFunction.isSitting(entry.reference);
            entry.responsePending |= entry.sittingFeedback.observe(sitting);
            if (entry.preview != null && !entry.preview.disabled()) entry.preview.setSitting(sitting);
        }
    }

    private static void transfer(Entry target, Entry source) {
        target.preview = source.preview;
        target.feedFeedback = source.feedFeedback;
        target.sittingFeedback = source.sittingFeedback;
        target.feedPending = source.feedPending;
        target.responsePending = source.responsePending;
        target.attempted = source.attempted;
        target.seenTick = source.seenTick;
    }

    private static boolean eligible(ItemStack stack) {
        return !stack.isEmpty() && stack.is(InitItems.SMART_SLAB_HAS_MAID.get())
                && LivingItemManager.isLivingItem(stack) && stack.has(InitDataComponent.MAID_INFO);
    }

    static boolean isActualCarried(ItemStack rendered, ItemStack carried, boolean snapback) {
        return !snapback && rendered != null && rendered == carried && !rendered.isEmpty();
    }

    private static boolean coolingDown(ItemStack stack) {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getCooldowns().getCooldownPercent(stack.getItem(),
                mc.getTimer().getGameTimeDeltaPartialTick(true)) > 0;
    }

    static ItemStack stable(ItemStack stack) {
        return SoulSlabPreviewMoves.stable(stack);
    }

    private static Slot physicalSlot(Slot slot) {
        return slot instanceof LivingItemSlotWrapperAccessor accessor
                ? accessor.living_item$getWrappedTarget() : slot;
    }

    private static Position position(Slot slot) {
        Slot physical = physicalSlot(slot);
        Object owner = physical instanceof SlotItemHandler handler ? handler.getItemHandler() : physical.container;
        return new Position(owner, physical.getContainerSlot(), slot.x, slot.y);
    }

    void render(AbstractContainerScreen<?> current, GuiGraphics graphics, int mouseX, int mouseY) {
        if (current != screen || graphics != frameGraphics) {
            clearContainerFrame();
            return;
        }
        try {
            for (Drawing drawing : frame.values()) {
                if (!SoulSlabGuiFrame.takeClaim(drawing.source, frameToken, drawing.itemPose)) continue;
                Slot slot = drawing.entry.placement.slot();
                if (slot == null || drawing.source != drawing.entry.reference
                        || drawing.source != slot.getItem()
                        || !ItemStack.matches(drawing.snapshot, drawing.source)) {
                    fallback(graphics, drawing.snapshot, drawing.origin, drawing.x, drawing.y, drawing.seed);
                    continue;
                }
                try {
                    drawing.entry.preview.setBegging(SoulSlabGuiSlots.isHovered(slot, current.getSlotUnderMouse()));
                    applyPending(drawing.entry);
                    renderOne(graphics, drawing.entry, drawing.snapshot, drawing.origin,
                            drawing.x, drawing.y, mouseX, mouseY, SoulSlabMaidPreview.Display.SLOT);
                    drawing.entry.seenTick = ticks;
                } catch (RuntimeException | LinkageError failure) {
                    drawing.entry.preview.disable();
                    LOGGER.warn("Soul slab preview drawing failed at menu slot {}; restoring original icon",
                            slot.index, failure);
                    fallback(graphics, drawing.snapshot, drawing.origin, drawing.x, drawing.y, drawing.seed);
                }
            }
        } finally {
            clearContainerFrame();
        }
    }

    void renderFloating(AbstractContainerScreen<?> current, GuiGraphics graphics, ItemStack stack,
                        int x, int y, boolean snapback, Runnable original) {
        if (current != screen || level == null
                || !isActualCarried(stack, current.getMenu().getCarried(), snapback)
                || !eligible(stack) || coolingDown(stack)) {
            original.run();
            return;
        }
        Entry entry = entries.get(Position.cursor(current.getMenu()));
        if (entry == null || entry.reference != stack || !prepare(entry, stack) || entry.preview.disabled()) {
            original.run();
            return;
        }

        PoseStack origin = SoulSlabGuiPose.floatingOrigin(graphics.pose());
        PoseStack itemPose = SoulSlabGuiPose.itemModelPose(origin, x, y);
        SoulSlabGuiFrame.Token token = SoulSlabGuiFrame.begin();
        SoulSlabGuiFrame.register(stack, token, itemPose);
        try {
            original.run();
            if (!SoulSlabGuiFrame.takeClaim(stack, token, itemPose)) return;
            try {
                entry.preview.setBegging(false);
                applyPending(entry);
                renderOne(graphics, entry, stack, origin, x, y, x + 8, y + 8,
                        SoulSlabMaidPreview.Display.CURSOR);
                entry.seenTick = ticks;
            } catch (RuntimeException | LinkageError failure) {
                entry.preview.disable();
                LOGGER.warn("Soul slab cursor preview drawing failed; restoring original icon", failure);
                original.run();
            }
        } finally {
            SoulSlabGuiFrame.clear();
        }
    }

    @Override
    public void begin() {
        AbstractContainerScreen<?> current = Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> container
                ? container : null;
        reconcile(current);
        hotbarFrame = SoulSlabPreviewSurface.hudOwnsFrame(current != null);
        if (hotbarFrame) clearBegging();
    }

    @Override
    public void render(GuiGraphics graphics, LivingEntity entity, ItemStack stack,
                       int x, int y, int seed, Runnable original) {
        if (!hotbarFrame || entity != player || screen != null || level == null
                || SoulSlabHotbarSlots.inventorySlot(player, stack, seed) < 0
                || !eligible(stack) || coolingDown(stack)) {
            original.run();
            return;
        }
        int slot = SoulSlabHotbarSlots.inventorySlot(player, stack, seed);
        Position position = new Position(player.getInventory(), slot, 0, 0);
        Entry entry = entries.get(position);
        if (entry == null || entry.reference != stack || entry.placement.kind() != Kind.HUD_SLOT
                || !prepare(entry, stack) || entry.preview.disabled()) {
            original.run();
            return;
        }

        PoseStack origin = copy(graphics.pose());
        PoseStack itemPose = SoulSlabGuiPose.itemModelPose(origin, x, y);
        SoulSlabGuiFrame.Token token = SoulSlabGuiFrame.begin();
        SoulSlabGuiFrame.register(stack, token, itemPose);
        try {
            original.run();
            if (!SoulSlabGuiFrame.takeClaim(stack, token, itemPose)) return;
            try {
                entry.preview.setBegging(false);
                applyPending(entry);
                renderOne(graphics, entry, stack, origin, x, y, x + 8, y + 8,
                        SoulSlabMaidPreview.Display.HOTBAR);
                entry.seenTick = ticks;
            } catch (RuntimeException | LinkageError failure) {
                entry.preview.disable();
                LOGGER.warn("Soul slab hotbar preview drawing failed; restoring original icon", failure);
                original.run();
            }
        } finally {
            SoulSlabGuiFrame.clear();
        }
    }

    @Override
    public void end() {
        hotbarFrame = false;
    }

    private static void applyPending(Entry entry) {
        if (entry.feedPending) {
            entry.feedPending = false;
            entry.preview.feed();
        }
        if (entry.responsePending) {
            entry.responsePending = false;
            entry.preview.respond();
        }
    }

    private void renderOne(GuiGraphics graphics, Entry entry, ItemStack stack, PoseStack origin,
                           int x, int y, int mouseX, int mouseY, SoulSlabMaidPreview.Display display) {
        // Foreground/光标/HUD接缝的标准恢复基线是3D item光照；不改dispatcher/options。
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
                int height = stack.getCount() != 1 ? 9 : stack.isBarVisible() ? 13 : 16;
                entry.preview.render(graphics, origin, x, y, height, mouseX, mouseY, display);
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

    private void fallback(GuiGraphics graphics, ItemStack stack, PoseStack origin, int x, int y, int seed) {
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        graphics.pose().pushPose();
        try {
            graphics.pose().last().pose().set(origin.last().pose());
            graphics.pose().last().normal().set(origin.last().normal());
            graphics.renderItem(stack, x, y, seed);
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
            boolean visible = SoulSlabPreviewVisibility.recentlyDrawn(ticks, entry.seenTick);
            if (entry.preview != null) {
                try {
                    entry.preview.tick(visible);
                } catch (RuntimeException | LinkageError failure) {
                    entry.preview.disable();
                    LOGGER.warn("Soul slab preview animation/audio failed; keeping original icon", failure);
                }
            }
            if (!visible) {
                entry.feedPending = false;
                entry.responsePending = false;
            }
        }
    }

    void screenClosing(AbstractContainerScreen<?> closing) {
        clearContainerFrame();
        clearBegging();
    }

    private void clearBegging() {
        entries.values().forEach(entry -> {
            if (entry.preview != null && !entry.preview.disabled()) entry.preview.setBegging(false);
        });
    }

    private void clearContainerFrame() {
        SoulSlabGuiFrame.clear();
        frame.clear();
        frameGraphics = null;
        frameToken = null;
    }

    void clear() {
        clearContainerFrame();
        entries.values().forEach(entry -> { if (entry.preview != null) entry.preview.dispose(); });
        entries.clear();
        screen = null;
        level = null;
        player = null;
        ticks = 0;
        hotbarFrame = false;
    }
}
