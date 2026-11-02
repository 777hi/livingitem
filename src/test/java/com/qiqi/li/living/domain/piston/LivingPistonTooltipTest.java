package com.qiqi.li.living.domain.piston;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.domain.piston.PistonComponents;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.runtime.LivingItemClientCache;
import com.qiqi.li.living.runtime.RuntimeSegments;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LivingPistonTooltipTest {

    private final LivingPistonFunction function = new LivingPistonFunction();

    @AfterEach
    void clearTooltipState() {
        LivingItemClientCache.clearCurrentTooltipData();
    }

    private static ItemStack living(Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private List<Component> tooltip(ItemStack stack) {
        List<Component> lines = new ArrayList<>();
        function.addToTooltip(Item.TooltipContext.EMPTY, lines::add, TooltipFlag.NORMAL, stack);
        return lines;
    }

    private static TranslatableContents translated(List<Component> lines, String key) {
        return lines.stream().map(Component::getContents)
            .filter(TranslatableContents.class::isInstance)
            .map(TranslatableContents.class::cast)
            .filter(content -> content.getKey().equals(key))
            .findFirst().orElseThrow(() -> new AssertionError("Missing tooltip key: " + key));
    }

    private static boolean hasKey(List<Component> lines, String key) {
        return lines.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents contents
            && contents.getKey().equals(key));
    }

    @Test
    void exposesOneCardinalDirectionSlotWithoutLeakingNamesArray() {
        assertEquals(1, function.getDirectionKeyCount());
        assertArrayEquals(new String[]{"direction"}, function.getDirectionSlotNames());
        function.getDirectionSlotNames()[0] = "changed";
        assertArrayEquals(new String[]{"direction"}, function.getDirectionSlotNames());
        assertFalse(function.supportsDiagonal());
    }

    @Test
    void directionUpdateUsesValueEqualityAndReportsActualChange() {
        ItemStack stack = living(Items.PISTON, 3);
        assertEquals(Pos2D.UP, function.getSlotDirection(stack, "direction"));
        assertFalse(function.updateSlotDirection(stack, "direction", new Pos2D(0, -1)));
        assertTrue(function.updateSlotDirection(stack, "direction", new Pos2D(1, 0)));
        assertEquals(Pos2D.RIGHT, function.getSlotDirection(stack, "direction"));
        assertFalse(function.updateSlotDirection(stack, "direction", Pos2D.RIGHT));
        assertTrue(function.updateSlotDirection(stack, "direction", Pos2D.UP));
        assertFalse(stack.has(PistonComponents.LIVING_PISTON_DATA.value()));
        assertEquals(3, stack.getCount());
        assertTrue(LivingItemManager.isLivingItem(stack));
    }

    @Test
    void invalidDirectionRequestsDoNotChangeComponents() {
        ItemStack stack = living(Items.STICKY_PISTON, 1);
        ItemStack before = stack.copy();
        assertFalse(function.updateSlotDirection(stack, "unknown", Pos2D.RIGHT));
        assertFalse(function.updateSlotDirection(stack, "direction", Pos2D.NONE));
        assertFalse(function.updateSlotDirection(stack, "direction", Pos2D.UP_RIGHT));
        assertFalse(function.updateSlotDirection(stack, "direction", null));
        assertFalse(function.updateSlotDirection(null, "direction", Pos2D.RIGHT));
        assertFalse(function.updateSlotDirection(new ItemStack(Items.PISTON), "direction", Pos2D.RIGHT));
        assertFalse(function.updateSlotDirection(living(Items.HOPPER, 1), "direction", Pos2D.RIGHT));
        assertNull(function.getSlotDirection(stack, "unknown"));
        assertTrue(ItemStack.matches(before, stack));
    }

    @Test
    void ordinaryTooltipShowsDefaultDirectionForceAndStageLimit() {
        var lines = tooltip(living(Items.PISTON, 3));
        translated(lines, "tooltip.livingitem.piston.title");
        assertArrayEquals(new Object[]{"↑"}, translated(lines, "tooltip.livingitem.piston.direction").getArgs());
        assertArrayEquals(new Object[]{3}, translated(lines, "tooltip.livingitem.piston.force").getArgs());
        translated(lines, "tooltip.livingitem.piston.integration_pending");
        assertFalse(hasKey(lines, "tooltip.livingitem.piston.extended"));
        assertFalse(hasKey(lines, "tooltip.livingitem.piston.retracted"));
    }

    @Test
    void stickyTooltipReadsMatchingRuntimeState() {
        LivingItemClientCache.setCurrentTooltipData(RuntimeSegments.EMPTY.with(PistonSegment.INSTANCE,
            new PistonRuntime(true, Pos2D.UP)));
        var lines = tooltip(living(Items.STICKY_PISTON, 1));
        translated(lines, "tooltip.livingitem.piston.sticky_title");
        translated(lines, "tooltip.livingitem.piston.extended");
        assertFalse(hasKey(lines, "tooltip.livingitem.piston.retracted"));
    }

    @Test
    void tooltipShowsRetractedStateWithoutPersistingIt() {
        ItemStack stack = living(Items.PISTON, 1);
        ItemStack before = stack.copy();
        LivingItemClientCache.setCurrentTooltipData(RuntimeSegments.EMPTY.with(PistonSegment.INSTANCE,
            PistonRuntime.retracted(Pos2D.UP)));
        translated(tooltip(stack), "tooltip.livingitem.piston.retracted");
        assertTrue(ItemStack.matches(before, stack));
    }

    @Test
    void tooltipDoesNotShowOldDirectionRuntime() {
        LivingItemClientCache.setCurrentTooltipData(RuntimeSegments.EMPTY.with(PistonSegment.INSTANCE,
            new PistonRuntime(true, Pos2D.RIGHT)));
        var lines = tooltip(living(Items.PISTON, 1));
        assertFalse(hasKey(lines, "tooltip.livingitem.piston.extended"));
        assertFalse(hasKey(lines, "tooltip.livingitem.piston.retracted"));
    }

    @Test
    void pistonTooltipKeysExistInBothLanguages() throws IOException {
        var keys = List.of("title", "sticky_title", "direction", "force", "extended", "retracted", "integration_pending");
        for (String language : List.of("zh_cn", "en_us")) {
            try (var stream = getClass().getResourceAsStream("/assets/living_item/lang/" + language + ".json")) {
                assertNotNull(stream);
                var json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                for (String key : keys) {
                    assertFalse(json.get("tooltip.livingitem.piston." + key).getAsString().isBlank());
                }
            }
        }
    }
}
