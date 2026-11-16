package com.qiqi.li.client.render.tlm;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.StackCopySlot;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

class SoulSlabGuiSlotsTest {
    @Test
    void ordinarySlotHasStableReference() {
        Slot slot = new Slot(new SimpleContainer(new ItemStack(Items.PAPER)), 0, 0, 0);
        assertTrue(SoulSlabGuiSlots.hasStableStackReference(slot, slot));
        assertSame(slot.getItem(), slot.getItem());
    }

    @Test
    void officialCopySlotSafelyKeepsOriginalIcon() {
        StackCopySlot slot = copySlot();
        assertFalse(SoulSlabGuiSlots.hasStableStackReference(slot, slot));
        assertNotSame(slot.getItem(), slot.getItem());
    }

    @Test
    void wrappedCopySlotUsesPhysicalSlotStability() {
        Slot display = new Slot(new SimpleContainer(0), 0, 0, 0);
        assertFalse(SoulSlabGuiSlots.hasStableStackReference(display, copySlot()));
    }

    @Test
    void missingDisplayOrPhysicalSlotIsRejected() {
        Slot slot = new Slot(new SimpleContainer(0), 0, 0, 0);
        assertFalse(SoulSlabGuiSlots.hasStableStackReference(null, slot));
        assertFalse(SoulSlabGuiSlots.hasStableStackReference(slot, null));
    }

    @Test
    void hoverRequiresExactDisplayedSlotIdentity() {
        SimpleContainer container = new SimpleContainer(new ItemStack(Items.PAPER));
        Slot display = new Slot(container, 0, 18, 18);
        assertTrue(SoulSlabGuiSlots.isHovered(display, display));

        Slot alias = new Slot(container, 0, 18, 18);
        assertFalse(SoulSlabGuiSlots.isHovered(display, alias),
                "同容器同索引同坐标的另一个显示对象也不冒充本槽");
        assertFalse(SoulSlabGuiSlots.isHovered(display, null));
        assertFalse(SoulSlabGuiSlots.isHovered(null, display));
    }

    @Test
    void onlyExactCarriedReferenceCanUseFloatingPreview() {
        ItemStack carried = new ItemStack(Items.PAPER);
        assertTrue(SoulSlabPreviewCache.isActualCarried(carried, carried, false));
        assertFalse(SoulSlabPreviewCache.isActualCarried(carried.copy(), carried, false));
        assertFalse(SoulSlabPreviewCache.isActualCarried(carried, carried, true));
        assertFalse(SoulSlabPreviewCache.isActualCarried(ItemStack.EMPTY, ItemStack.EMPTY, false));
        assertFalse(SoulSlabPreviewCache.isActualCarried(null, carried, false));
    }

    @Test
    void carriedAndSnapbackMarkers_bothRequireInjection() throws IOException {
        ClassLoader loader = getClass().getClassLoader();
        String className = "com.qiqi.li.client.mixin.AbstractContainerScreenFloatingItemMixin";
        try (InputStream resource = loader.getResourceAsStream("living_item.client.mixins.json")) {
            assertNotNull(resource);
            var config = JsonParser.parseString(new String(resource.readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
            assertTrue(config.get("required").getAsBoolean());
            assertEquals(1, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
            assertTrue(config.getAsJsonArray("client").asList().stream()
                .anyMatch(name -> className.equals(config.get("package").getAsString() + "." + name.getAsString())));
        }
        ClassNode mixin = new ClassNode();
        try (InputStream resource = loader.getResourceAsStream(className.replace('.', '/') + ".class")) {
            assertNotNull(resource);
            new ClassReader(resource).accept(mixin,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        List<MethodNode> markers = mixin.methods.stream()
            .filter(method -> method.name.equals("living_item$markCarriedDraw")
                || method.name.equals("living_item$markSnapbackDraw")).toList();
        assertEquals(2, markers.size());
        for (MethodNode marker : markers) {
            AnnotationNode inject = annotation(marker, "Lorg/spongepowered/asm/mixin/injection/Inject;");
            assertNotNull(inject);
            Object require = annotationValue(inject, "require");
            assertTrue(require == null || ((Integer) require) == -1 || ((Integer) require) >= 1,
                "两个标记都不能覆盖默认要求为零: " + marker.name);
            assertEquals(List.of("render"), annotationValue(inject, "method"));
            AnnotationNode at = (AnnotationNode) ((List<?>) annotationValue(inject, "at")).getFirst();
            assertEquals("INVOKE", annotationValue(at, "value"));
            assertEquals("Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;"
                + "renderFloatingItem(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/item/ItemStack;"
                + "IILjava/lang/String;)V", annotationValue(at, "target"));
            assertEquals(marker.name.equals("living_item$markCarriedDraw") ? 0 : 1, annotationValue(at, "ordinal"));
        }
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        for (List<AnnotationNode> annotations : List.of(
                method.visibleAnnotations == null ? List.<AnnotationNode>of() : method.visibleAnnotations,
                method.invisibleAnnotations == null ? List.<AnnotationNode>of() : method.invisibleAnnotations)) {
            for (AnnotationNode annotation : annotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static StackCopySlot copySlot() {
        return new StackCopySlot(0, 0) {
            @Override protected ItemStack getStackCopy() { return new ItemStack(Items.PAPER); }
            @Override protected void setStackCopy(ItemStack stack) {}
        };
    }
}
