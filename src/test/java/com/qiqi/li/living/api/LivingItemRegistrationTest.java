package com.qiqi.li.living.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

@Isolated
class LivingItemRegistrationTest {
    private static LivingItemFunction function(String id, boolean selfSustaining) {
        LivingItemFunction function = mock(LivingItemFunction.class);
        when(function.getFunctionId()).thenReturn(id);
        when(function.shouldTickWithoutOwnItems(null)).thenReturn(selfSustaining);
        when(function.canApply(any())).thenReturn(true);
        return function;
    }

    private static ItemStack livingPaper() {
        ItemStack stack = new ItemStack(Items.PAPER);
        LivingItemManager.setLiving(stack, true);
        return stack;
    }

    @Test
    void duplicateIdFailsBeforeAnyMutationOrCallback() {
        var first = function("test_same", true);
        var duplicate = function(new String("test_same"), false);
        try (var scope = LivingItemRegistrationScope.isolated(List.of(first))) {
            var view = LivingItemManager.getAllFunctions();
            var cached = LivingItemManager.getApplicableFunctions(livingPaper());
            var sustaining = LivingItemManager.getSelfSustainingFunctions();
            clearInvocations(first, duplicate);

            var failure = assertThrows(IllegalStateException.class,
                () -> LivingItemManager.registerFunction(duplicate));
            assertTrue(failure.getMessage().contains("test_same"));
            assertEquals(List.of(first), view);
            assertSame(cached, LivingItemManager.getApplicableFunctions(livingPaper()));
            assertSame(sustaining, LivingItemManager.getSelfSustainingFunctions());
            verify(first, never()).shouldTickWithoutOwnItems(null);
            verify(duplicate, never()).shouldTickWithoutOwnItems(null);
            assertThrows(IllegalStateException.class, () -> LivingItemManager.registerFunction(first));
        }
    }

    @Test
    void differentIdsOfSameImplementationCanRegisterAndInvalidateCache() {
        var first = function("test_one", false);
        var second = function("test_two", true);
        try (var scope = LivingItemRegistrationScope.isolated(List.of(first))) {
            var cached = LivingItemManager.getApplicableFunctions(livingPaper());
            LivingItemManager.registerFunction(second);
            assertEquals(List.of(first, second), LivingItemManager.getAllFunctions());
            assertNotSame(cached, LivingItemManager.getApplicableFunctions(livingPaper()));
            assertEquals(List.of(second), LivingItemManager.getSelfSustainingFunctions());
        }
    }

    @Test
    void scopeRestoresOrderedInstancesCachesAndLiveViewAfterException() {
        var before = LivingItemManager.snapshotForTest();
        var view = LivingItemManager.getAllFunctions();
        var cached = LivingItemManager.getApplicableFunctions(livingPaper());
        var sustaining = LivingItemManager.getSelfSustainingFunctions();
        assertThrows(IllegalArgumentException.class, () -> {
            try (var scope = LivingItemRegistrationScope.isolated(List.of(function("test_temporary", true)))) {
                assertEquals("test_temporary", view.getFirst().getFunctionId());
                throw new IllegalArgumentException("test exit");
            }
        });
        assertEquals(before.functions(), view);
        for (int i = 0; i < view.size(); i++) assertSame(before.functions().get(i), view.get(i));
        assertSame(cached, LivingItemManager.getApplicableFunctions(livingPaper()));
        assertSame(sustaining, LivingItemManager.getSelfSustainingFunctions());
    }

    @Test
    void differentImplementationWithSameIdIsRejected() {
        LivingItemFunction original = new com.qiqi.li.living.domain.tnt.LivingTntFunction();
        LivingItemFunction incoming = function(original.getFunctionId(), false);
        try (var scope = LivingItemRegistrationScope.isolated(List.of(original))) {
            var error = assertThrows(IllegalStateException.class,
                () -> LivingItemManager.registerFunction(incoming));
            assertTrue(error.getMessage().contains(original.getClass().getName()));
            assertTrue(error.getMessage().contains(incoming.getClass().getName()));
            assertEquals(List.of(original), LivingItemManager.getAllFunctions());
        }
    }

    @Test
    void throwingFixtureCallbackRestoresOriginalState() {
        var before = LivingItemManager.snapshotForTest();
        var broken = function("test_broken_declaration", false);
        when(broken.shouldTickWithoutOwnItems(null)).thenThrow(new IllegalArgumentException("fixture"));
        assertThrows(IllegalArgumentException.class,
            () -> LivingItemRegistrationScope.isolated(List.of(broken)));
        assertEquals(before, LivingItemManager.snapshotForTest());
        assertSame(before.selfSustaining(), LivingItemManager.getSelfSustainingFunctions());
    }

    @Test
    void failedFixtureInstallationRestoresOriginalState() {
        var before = LivingItemManager.snapshotForTest();
        var one = function("test_duplicate_seed", true);
        var two = function("test_duplicate_seed", false);
        assertThrows(IllegalStateException.class,
            () -> LivingItemRegistrationScope.isolated(List.of(one, two)));
        assertEquals(before, LivingItemManager.snapshotForTest());
    }
}
