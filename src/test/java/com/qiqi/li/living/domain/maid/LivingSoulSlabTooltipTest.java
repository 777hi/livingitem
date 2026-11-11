package com.qiqi.li.living.domain.maid;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qiqi.li.living.api.LivingItemManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LivingSoulSlabTooltipTest {
    private static final String PENDING_KEY = "pending_favor";
    private static final LivingSoulSlabFunction.FavorLedger LEDGER = new LivingSoulSlabFunction.FavorLedger() {
        @Override public boolean canAdd(ItemStack stack) { return pending(stack) < 384; }
        @Override public ItemStack add(ItemStack stack, int amount) { return stack.copy(); }
        @Override public int pending(ItemStack stack) { return LivingSoulSlabTooltipTest.pending(stack); }
    };
    private final LivingSoulSlabFunction function = new LivingSoulSlabFunction(
            stack -> stack.is(Items.PAPER), stack -> false, LEDGER);

    @Test void defaultClockShowsAttemptChanceWithoutMutatingStack() {
        ItemStack stack = livingSoul(1);
        ItemStack before = stack.copy();
        TranslatableContents chance = translated(tooltip(stack), "tooltip.livingitem.soul_slab.attempt_chance");
        assertArgs(chance, "1/7", "14.3%", "7");
        assertTrue(ItemStack.matches(before, stack));
    }

    @Test void intervalEndpointsUseOneDecimalPercentage() {
        ItemStack fast = livingSoul(1);
        fast.set(MaidComponents.CLOCK.get(), new LivingSoulSlabClock(1));
        assertArgs(translated(tooltip(fast), "tooltip.livingitem.soul_slab.attempt_chance"),
                "1/1", "100.0%", "1");

        ItemStack slow = livingSoul(1);
        slow.set(MaidComponents.CLOCK.get(), new LivingSoulSlabClock(49));
        assertArgs(translated(tooltip(slow), "tooltip.livingitem.soul_slab.attempt_chance"),
                "1/49", "2.0%", "49");
    }

    @Test void invalidClockFallsBackToEffectiveInitialValue() {
        for (int interval : new int[]{0, 50}) {
            ItemStack stack = livingSoul(1);
            stack.set(MaidComponents.CLOCK.get(), new LivingSoulSlabClock(interval));
            assertArgs(translated(tooltip(stack), "tooltip.livingitem.soul_slab.attempt_chance"),
                    "1/7", "14.3%", "7");
            assertEquals(interval, stack.get(MaidComponents.CLOCK.get()).intervalSeconds());
        }
    }

    @Test void pendingFavorAppearsOnlyWhenPositive() {
        assertFalse(hasKey(tooltip(livingSoul(1)), "tooltip.livingitem.soul_slab.pending_favor"));
        ItemStack stack = livingSoul(1);
        setPending(stack, 7);
        assertArgs(translated(tooltip(stack), "tooltip.livingitem.soul_slab.pending_favor"), "+7");
        setPending(stack, -3);
        assertFalse(hasKey(tooltip(stack), "tooltip.livingitem.soul_slab.pending_favor"));
    }

    @Test void inactiveAndOversizedStacksDoNotAdvertiseRuntimeState() {
        assertTrue(tooltip(new ItemStack(Items.PAPER)).isEmpty());
        assertTrue(tooltip(livingSoul(2)).isEmpty());
    }

    @Test void languageKeysDescribeAttemptsNotMovementSuccess() throws IOException {
        for (String language : List.of("zh_cn", "en_us")) {
            JsonObject json;
            try (var stream = getClass().getResourceAsStream("/assets/living_item/lang/" + language + ".json")) {
                assertNotNull(stream);
                json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
            for (String suffix : List.of("title", "attempt_chance", "pending_favor")) {
                assertTrue(json.has("tooltip.livingitem.soul_slab." + suffix));
            }
            String chance = json.get("tooltip.livingitem.soul_slab.attempt_chance").getAsString();
            assertEquals(3, chance.split("%s", -1).length - 1);
            assertFalse(chance.contains("%d"));
            if (language.equals("zh_cn")) {
                assertTrue(chance.contains("尝试"));
                assertFalse(chance.contains("移动"));
                assertFalse(chance.contains("成功"));
            } else {
                assertTrue(chance.toLowerCase().contains("attempt"));
                assertFalse(chance.toLowerCase().contains("move"));
                assertFalse(chance.toLowerCase().contains("success"));
            }
        }
    }

    private List<Component> tooltip(ItemStack stack) {
        List<Component> lines = new ArrayList<>();
        function.addToTooltip(Item.TooltipContext.EMPTY, lines::add, TooltipFlag.NORMAL, stack);
        return lines;
    }

    private static ItemStack livingSoul(int count) {
        ItemStack stack = new ItemStack(Items.PAPER, count);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    private static int pending(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getInt(PENDING_KEY);
    }

    private static void setPending(ItemStack stack, int value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(PENDING_KEY, value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static TranslatableContents translated(List<Component> lines, String key) {
        return lines.stream().map(Component::getContents)
                .filter(TranslatableContents.class::isInstance)
                .map(TranslatableContents.class::cast)
                .filter(content -> content.getKey().equals(key))
                .findFirst().orElseThrow(() -> new AssertionError("Missing tooltip key: " + key));
    }

    private static boolean hasKey(List<Component> lines, String key) {
        return lines.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents content
                && content.getKey().equals(key));
    }

    private static void assertArgs(TranslatableContents content, String... expected) {
        Object[] actual = content.getArgs();
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertInstanceOf(Component.class, actual[i]);
            assertEquals(expected[i], ((Component) actual[i]).getString());
        }
    }
}
