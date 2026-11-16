package com.qiqi.li.network;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import com.qiqi.li.living.api.ActivationRuleConfig;
import com.qiqi.li.living.api.LivingItemActivation;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.LivingItemRegistrationScope;
import com.qiqi.li.living.container.ContainerContext;
import com.qiqi.li.living.container.TickContext;
import com.qiqi.li.living.domain.chest.LivingChestFunction;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 服务端菜单、借入光标与真实钩子守卫；掉落验证到 addFreshEntity 提交边界。 */
@Isolated
class LivingTagPacketTest {
    private final Map<String, Object> savedRules = new LinkedHashMap<>();
    private final List<CarriedUpdatePacket> replies = new ArrayList<>();
    private final TestFunction function = new TestFunction();
    private LivingItemRegistrationScope registration;
    private RegistryAccess registry;
    private ServerPlayer player;
    private RecordingInventoryMenu menu;
    private Level level;

    @BeforeEach
    void setUp() throws Exception {
        for (String name : List.of("RULES", "REMOVED", "defaultAction", "denyUnclaimed")) {
            Object value = ruleField(name).get(null);
            savedRules.put(name, value instanceof Collection<?> values ? new ArrayList<>(values) : value);
        }
        rules("{}");
        registration = LivingItemRegistrationScope.isolated(List.of(new LivingChestFunction(), function));
        registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        player = mock(ServerPlayer.class);
        level = mock(Level.class);
        when(player.level()).thenReturn(level);
        when(player.registryAccess()).thenReturn(registry);
        when(player.blockPosition()).thenReturn(new BlockPos(8, 64, 12));
        Inventory inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        menu = new RecordingInventoryMenu(inventory, player);
        Field inventoryMenu = Player.class.getDeclaredField("inventoryMenu");
        inventoryMenu.setAccessible(true);
        inventoryMenu.set(player, menu);
        player.containerMenu = menu;
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void tearDown() throws Exception {
        try {
            if (registration != null) registration.close();
        } finally {
            for (var entry : savedRules.entrySet()) {
                Field field = ruleField(entry.getKey());
                Object current = field.get(null);
                if (current instanceof Collection<?> values) {
                    Collection<Object> target = (Collection<Object>) values;
                    target.clear();
                    target.addAll((Collection<?>) entry.getValue());
                } else {
                    field.set(null, entry.getValue());
                }
            }
        }
    }

    @Test
    void codec_roundTripsAbsentTagAndCorrelation() {
        assertEquals(new LivingTagPacket(-1, null, 0), new LivingTagPacket());
        assertEquals(new LivingTagPacket(17, null, 0), new LivingTagPacket(17, null));
        roundTrip(new LivingTagPacket());
        roundTrip(new LivingTagPacket(17, null, Long.MAX_VALUE));
        roundTrip(new LivingTagPacket(0, new CompoundTag(), 1));
    }

    @Test
    void codec_roundTripsNestedTagAndExactWireOrder() {
        CompoundTag candidate = tag(nestedChest(3));
        LivingTagPacket packet = new LivingTagPacket(17, candidate, 0x1020304050607080L);
        roundTrip(packet);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            LivingTagPacket.STREAM_CODEC.encode(buf, packet);
            assertEquals(17, buf.readVarInt());
            assertTrue(buf.readBoolean());
            assertEquals(candidate, buf.readNbt());
            assertEquals(packet.requestId(), buf.readLong());
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void legacyOrdinaryRequest_togglesRealCarriedWithoutClearing() {
        ItemStack original = new ItemStack(Items.PAPER, 7);
        setCarried(original);
        LivingTagPacket.handle(new LivingTagPacket(), player, replies::add);
        assertSame(original, menu.getCarried());
        assertTrue(LivingItemManager.isLivingItem(original));
        assertEquals(7, original.getCount());
        assertEquals(0, menu.writes);
        assertEquals(1, function.activated);
        assertTrue(replies.isEmpty());
    }

    @Test
    void ordinaryRequest_ignoresClientTagAndDoesNotParseIt() {
        ItemStack original = new ItemStack(Items.PAPER, 7);
        setCarried(original);
        CompoundTag malformed = new CompoundTag();
        malformed.putString("id", "not a resource location");
        LivingTagPacket.handle(new LivingTagPacket(0, malformed, 0), player, replies::add);
        assertSame(original, menu.getCarried());
        assertTrue(LivingItemManager.isLivingItem(original));
        assertEquals(7, original.getCount());
        assertEquals(0, menu.writes);
        verify(player, never()).registryAccess();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryCorrelatedRequest_ignoresCandidateAndReturnsActualCarriedEvenOnDenial(boolean denied)
            throws Exception {
        ItemStack original = new ItemStack(Items.PAPER, 7);
        setCarried(original);
        if (denied) rules("{\"rules\":[{\"item\":\"minecraft:paper\",\"activate\":\"deny\"}]}");
        CompoundTag forged = tag(nestedChest(3));
        LivingTagPacket.handle(new LivingTagPacket(0, forged, 54), player, replies::add);
        ItemStack result = result(0, 54);
        assertSame(original, menu.getCarried());
        assertTrue(result.is(Items.PAPER));
        assertEquals(7, result.getCount());
        assertEquals(!denied, LivingItemManager.isLivingItem(result));
        assertEquals(tag(original), tag(result));
        assertEquals(0, menu.writes);
        assertEquals(denied ? 0 : 1, function.activated);
    }

    @Test
    void omittedMenuIsOnlyAllowedForExactLegacyOrdinaryRequest() {
        ItemStack original = new ItemStack(Items.PAPER, 7);
        setCarried(original);
        LivingTagPacket.handle(new LivingTagPacket(-1, tag(new ItemStack(Items.STONE)), 55), player, replies::add);
        assertSame(original, menu.getCarried());
        assertFalse(LivingItemManager.isLivingItem(original));
        assertTrue(result(-1, 55).isEmpty());
        assertEquals(0, menu.writes);
    }

    @Test
    void ordinaryCorrelatedWrongMenu_returnsEmptyInsteadOfClientCandidate() {
        ItemStack original = nestedChest(2);
        CompoundTag before = tag(original);
        setCarried(original);
        CompoundTag forged = tag(new ItemStack(Items.PAPER, 3));
        LivingTagPacket.handle(new LivingTagPacket(17, forged, 55), player, replies::add);
        assertTrue(result(17, 55).isEmpty());
        assertSame(original, menu.getCarried());
        assertEquals(before, tag(original));
        assertEquals(0, menu.writes);
        assertEquals(0, function.deactivated);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preciseOrdinaryRequest_onRealContainerTogglesWithoutClearing(boolean creative) {
        when(player.isCreative()).thenReturn(creative);
        FakeMenu container = new FakeMenu(17);
        ItemStack original = new ItemStack(Items.PAPER, 7);
        container.setCarried(original);
        container.writes = 0;
        player.containerMenu = container;
        LivingTagPacket.handle(new LivingTagPacket(17, null, 0), player, replies::add);
        assertSame(original, container.getCarried());
        assertTrue(LivingItemManager.isLivingItem(original));
        assertEquals(0, container.writes);
        assertEquals(1, function.activated);
        assertTrue(replies.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void wrongOrdinaryMenu_rejectsWithoutTouchingRealCarried(boolean creative) {
        when(player.isCreative()).thenReturn(creative);
        ItemStack original = new ItemStack(Items.PAPER, 7);
        setCarried(original);
        LivingTagPacket.handle(new LivingTagPacket(17, null, 0), player, replies::add);
        assertSame(original, menu.getCarried());
        assertFalse(LivingItemManager.isLivingItem(original));
        assertEquals(0, menu.writes);
        assertEquals(0, function.activated);
    }

    @Test
    void creativeLegacyRequest_doesNotToggleRealCarried() {
        when(player.isCreative()).thenReturn(true);
        ItemStack original = new ItemStack(Items.PAPER);
        setCarried(original);
        LivingTagPacket.handle(new LivingTagPacket(), player, replies::add);
        assertSame(original, menu.getCarried());
        assertFalse(LivingItemManager.isLivingItem(original));
        assertEquals(0, menu.writes);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1, 17})
    void virtualWrongMenu_returnsOriginalCandidateWithoutParsing(int id) {
        when(player.isCreative()).thenReturn(true);
        CompoundTag candidate = tag(nestedChest(3));
        assertDoesNotThrow(() -> LivingTagPacket.handle(
            new LivingTagPacket(id, candidate, 41), player, replies::add));
        assertOriginalReply(candidate, id, 41);
        assertTrue(menu.getCarried().isEmpty());
        assertEquals(0, menu.writes);
        verify(player, never()).registryAccess();
    }

    @Test
    void virtualNonInventoryMenu_returnsCandidateWithoutTouchingEitherMenu() {
        when(player.isCreative()).thenReturn(true);
        FakeMenu other = new FakeMenu(0);
        ItemStack original = nestedChest(2);
        other.setCarried(original);
        other.writes = 0;
        player.containerMenu = other;
        CompoundTag candidate = tag(new ItemStack(Items.PAPER, 3));
        LivingTagPacket.handle(new LivingTagPacket(0, candidate, 42), player, replies::add);
        assertOriginalReply(candidate, 0, 42);
        assertSame(original, other.getCarried());
        assertEquals(0, other.writes);
        assertEquals(0, menu.writes);
        verify(player, never()).registryAccess();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void virtualInvalidOrUnknownMenu_returnsCandidateWithoutBorrowing(boolean missing) {
        when(player.isCreative()).thenReturn(true);
        if (missing) player.containerMenu = null;
        else menu.valid = false;
        CompoundTag candidate = tag(new ItemStack(Items.PAPER, 3));
        LivingTagPacket.handle(new LivingTagPacket(0, candidate, 43), player, replies::add);
        assertOriginalReply(candidate, 0, 43);
        assertEquals(0, menu.writes);
        assertEquals(0, function.activated);
        verify(player, never()).registryAccess();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void virtualWithoutPositiveRequestId_doesNotBorrowOrExecute(long requestId) {
        when(player.isCreative()).thenReturn(true);
        CompoundTag candidate = tag(new ItemStack(Items.PAPER, 3));
        LivingTagPacket.handle(new LivingTagPacket(0, candidate, requestId), player, replies::add);
        assertTrue(menu.getCarried().isEmpty());
        assertEquals(0, menu.writes);
        assertEquals(0, function.activated);
        assertTrue(replies.isEmpty());
        verify(player, never()).registryAccess();
    }

    @Test
    void virtualNonemptyRealCarried_preservesCountAndNestedStorage() {
        when(player.isCreative()).thenReturn(true);
        ItemStack original = nestedChest(2);
        CompoundTag before = tag(original);
        setCarried(original);
        CompoundTag candidate = tag(new ItemStack(Items.PAPER, 3));
        LivingTagPacket.handle(new LivingTagPacket(0, candidate, 44), player, replies::add);
        assertSame(original, menu.getCarried());
        assertEquals(before, tag(original));
        assertEquals(0, menu.writes);
        assertEquals(0, function.activated);
        assertOriginalReply(candidate, 0, 44);
        verify(level, never()).addFreshEntity(any());
    }

    @Test
    void virtualAuthorization_readsCandidateBeforeAnyCarriedWrite() throws Exception {
        when(player.isCreative()).thenReturn(true);
        rules("{\"options\":{\"denyUnclaimed\":true}}");
        function.probe = () -> {
            assertTrue(menu.getCarried().isEmpty());
            assertEquals(0, menu.writes);
        };
        function.activation = stack -> {
            assertSame(stack, menu.getCarried());
            assertEquals(1, menu.writes);
            assertTrue(LivingItemManager.isLivingItem(stack));
            stack.shrink(1);
        };
        CompoundTag candidate = tag(new ItemStack(Items.PAPER, 3));
        LivingTagPacket.handle(new LivingTagPacket(0, candidate, 45), player, replies::add);
        assertTrue(function.probes > 0);
        assertEquals(1, function.activated);
        assertTrue(menu.getCarried().isEmpty());
        assertEquals(2, menu.writes);
        ItemStack result = result(0, 45);
        assertTrue(LivingItemManager.isLivingItem(result));
        assertEquals(2, result.getCount());
        assertEquals(3, ItemStack.parse(registry, candidate).orElseThrow().getCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void policyDeniedVirtualCandidate_neverBorrowsAndReturnsOriginal(boolean unclaimed) throws Exception {
        when(player.isCreative()).thenReturn(true);
        rules(unclaimed ? "{\"options\":{\"denyUnclaimed\":true}}"
            : "{\"rules\":[{\"item\":\"minecraft:stick\",\"activate\":\"deny\"}]}");
        CompoundTag candidate = tag(new ItemStack(Items.STICK, 3));
        LivingTagPacket.handle(new LivingTagPacket(0, candidate, 46), player, replies::add);
        assertOriginalReply(candidate, 0, 46);
        assertTrue(menu.getCarried().isEmpty());
        assertEquals(0, menu.writes);
        assertEquals(0, function.activated);
        verify(player).sendSystemMessage(any());
    }

    @Test
    void parserFailure_repliesAndDoesNotBorrow() {
        when(player.isCreative()).thenReturn(true);
        CompoundTag candidate = tag(new ItemStack(Items.PAPER, 3));
        try (MockedStatic<ItemStack> stacks = mockStatic(ItemStack.class)) {
            stacks.when(() -> ItemStack.parse(registry, candidate))
                .thenThrow(new IllegalStateException("test parser failure"));
            assertDoesNotThrow(() -> LivingTagPacket.handle(
                new LivingTagPacket(0, candidate, 47), player, replies::add));
        }
        assertOriginalReply(candidate, 0, 47);
        assertTrue(menu.getCarried().isEmpty());
        assertEquals(0, menu.writes);
        assertEquals(0, function.activated);
    }

    @Test
    void malformedCandidate_repliesWithoutBorrowing() {
        when(player.isCreative()).thenReturn(true);
        CompoundTag candidate = new CompoundTag();
        candidate.putString("id", "minecraft:missing_item_for_test");
        LivingTagPacket.handle(new LivingTagPacket(0, candidate, 48), player, replies::add);
        assertOriginalReply(candidate, 0, 48);
        assertTrue(menu.getCarried().isEmpty());
        assertEquals(0, menu.writes);
        assertEquals(0, function.activated);
    }

    @Test
    void authorizationFailure_repliesBeforeBorrowing() throws Exception {
        when(player.isCreative()).thenReturn(true);
        rules("{\"options\":{\"denyUnclaimed\":true}}");
        function.probe = () -> { throw new IllegalStateException("test claim failure"); };
        CompoundTag candidate = tag(new ItemStack(Items.PAPER, 3));
        assertDoesNotThrow(() -> LivingTagPacket.handle(
            new LivingTagPacket(0, candidate, 49), player, replies::add));
        assertOriginalReply(candidate, 0, 49);
        assertTrue(menu.getCarried().isEmpty());
        assertEquals(0, menu.writes);
        assertEquals(0, function.activated);
    }

    @Test
    void hookVeto_returnsUnchangedLivingCandidateAndCleansBorrow() {
        when(player.isCreative()).thenReturn(true);
        function.deactivation = stack -> false;
        ItemStack living = new ItemStack(Items.PAPER, 3);
        LivingItemManager.setLiving(living, true);
        CompoundTag candidate = tag(living);
        LivingTagPacket.handle(new LivingTagPacket(0, candidate, 50), player, replies::add);
        ItemStack result = result(0, 50);
        assertEquals(candidate, tag(result));
        assertTrue(LivingItemManager.isLivingItem(result));
        assertEquals(1, function.deactivated);
        assertTrue(menu.getCarried().isEmpty());
        assertEquals(2, menu.writes);
    }

    @Test
    void hookFailure_returnsActualReplacementNotOriginalAndCleansBeforeReply() {
        when(player.isCreative()).thenReturn(true);
        ItemStack replacement = new ItemStack(Items.DIAMOND, 2);
        function.activation = stack -> {
            menu.setCarried(replacement);
            throw new IllegalStateException("test hook failure after replacement");
        };
        CompoundTag candidate = tag(new ItemStack(Items.PAPER, 3));
        assertDoesNotThrow(() -> LivingTagPacket.handle(new LivingTagPacket(0, candidate, 51), player, reply -> {
            assertTrue(menu.getCarried().isEmpty());
            replies.add(reply);
        }));
        ItemStack result = result(0, 51);
        assertTrue(ItemStack.isSameItemSameComponents(replacement, result));
        assertEquals(2, result.getCount());
        assertFalse(ItemStack.isSameItemSameComponents(ItemStack.parse(registry, candidate).orElseThrow(), result));
        assertEquals(3, menu.writes);
    }

    @Test
    void ordinaryHookFailure_keepsActualCarriedMutationWithoutClearing() {
        ItemStack original = new ItemStack(Items.PAPER, 3);
        setCarried(original);
        function.activation = stack -> {
            stack.shrink(1);
            throw new IllegalStateException("test ordinary hook failure");
        };
        assertDoesNotThrow(() -> LivingTagPacket.handle(new LivingTagPacket(), player, replies::add));
        assertSame(original, menu.getCarried());
        assertTrue(LivingItemManager.isLivingItem(original));
        assertEquals(2, original.getCount());
        assertEquals(0, menu.writes);
        assertTrue(replies.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stackedNestedChest_deactivationSubmitsConservedDropsAndNeverRestoresStorage(boolean lateFailure)
            throws Exception {
        when(player.isCreative()).thenReturn(true);
        Field random = Level.class.getDeclaredField("random");
        random.setAccessible(true);
        random.set(level, RandomSource.create(1));
        List<ItemEntity> drops = new ArrayList<>();
        doAnswer(invocation -> {
            drops.add(invocation.getArgument(0));
            return true;
        }).when(level).addFreshEntity(any(ItemEntity.class));
        function.claimed = Items.CHEST;
        function.deactivation = stack -> {
            assertTrue(stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream().allMatch(ItemStack::isEmpty));
            if (lateFailure) throw new IllegalStateException("test late hook failure after chest drop");
            return true;
        };
        CompoundTag candidate = tag(nestedChest(3));
        assertDoesNotThrow(() -> LivingTagPacket.handle(new LivingTagPacket(0, candidate, 52), player, reply -> {
            assertTrue(menu.getCarried().isEmpty());
            replies.add(reply);
        }));
        ItemStack result = result(0, 52);
        assertEquals(3, result.getCount());
        assertEquals(lateFailure, LivingItemManager.isLivingItem(result));
        assertTrue(result.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).stream().allMatch(ItemStack::isEmpty));
        assertEquals(2, menu.writes);
        assertEquals(1, function.deactivated);
        assertEquals(120, drops.stream().map(ItemEntity::getItem).filter(stack -> stack.is(Items.DIAMOND))
            .mapToInt(ItemStack::getCount).sum());
        assertEquals(6, drops.stream().map(ItemEntity::getItem).filter(stack -> stack.is(Items.CHEST))
            .mapToInt(ItemStack::getCount).sum());
        assertEquals(30, drops.stream().map(ItemEntity::getItem).filter(stack -> stack.is(Items.CHEST))
            .mapToInt(stack -> stack.getCount() * stack.get(DataComponents.CONTAINER).stream()
                .filter(inner -> inner.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum()).sum());
        assertEquals(3, drops.size());
        for (ItemEntity drop : drops) {
            assertTrue(drop.getItem().getCount() <= drop.getItem().getMaxStackSize());
            assertEquals(8.5, drop.getX());
            assertEquals(64.5, drop.getY());
            assertEquals(12.5, drop.getZ());
        }
        assertEquals(candidate, tag(nestedChest(3)));
    }

    @Test
    void payloadContext_routesCorrelatedReplyThroughReplyConsumer() {
        when(player.isCreative()).thenReturn(true);
        IPayloadContext context = mock(IPayloadContext.class);
        when(context.player()).thenReturn(player);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }).when(context).enqueueWork(any(Runnable.class));
        CompoundTag candidate = tag(new ItemStack(Items.PAPER, 3));
        LivingTagPacket.handle(new LivingTagPacket(17, candidate, 53), context);
        verify(context).reply(new CarriedUpdatePacket(candidate, 17, 53));
        assertEquals(0, menu.writes);
    }

    private CompoundTag tag(ItemStack stack) {
        return (CompoundTag) stack.saveOptional(registry);
    }

    private ItemStack result(int menuId, long requestId) {
        assertEquals(1, replies.size());
        CarriedUpdatePacket reply = replies.getFirst();
        assertEquals(menuId, reply.menuId());
        assertEquals(requestId, reply.requestId());
        return ItemStack.parseOptional(registry, reply.carriedTag());
    }

    private void assertOriginalReply(CompoundTag candidate, int menuId, long requestId) {
        assertEquals(List.of(new CarriedUpdatePacket(candidate, menuId, requestId)), replies);
        assertNotSame(candidate, replies.getFirst().carriedTag());
    }

    private void setCarried(ItemStack stack) {
        menu.setCarried(stack);
        menu.writes = 0;
    }

    private static ItemStack nestedChest(int count) {
        ItemStack inner = new ItemStack(Items.CHEST, 2);
        inner.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.EMERALD, 5))));
        ItemStack outer = new ItemStack(Items.CHEST, count);
        LivingItemManager.setLiving(outer, true);
        LivingChestFunction.setItems(outer, List.of(new ItemStack(Items.DIAMOND, 40), inner));
        return outer;
    }

    private static void roundTrip(LivingTagPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            LivingTagPacket.STREAM_CODEC.encode(buf, packet);
            assertEquals(packet, LivingTagPacket.STREAM_CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    private static Field ruleField(String name) throws Exception {
        Field field = ActivationRuleConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void rules(String json) throws Exception {
        Method load = ActivationRuleConfig.class.getDeclaredMethod("loadFromString", String.class);
        load.setAccessible(true);
        load.invoke(null, json);
    }

    private static class RecordingInventoryMenu extends InventoryMenu {
        boolean valid = true;
        int writes;
        RecordingInventoryMenu(Inventory inventory, Player owner) { super(inventory, false, owner); }
        @Override public boolean stillValid(Player player) { return valid && super.stillValid(player); }
        @Override public void setCarried(ItemStack stack) { writes++; super.setCarried(stack); }
    }

    private static class FakeMenu extends AbstractContainerMenu {
        int writes;
        FakeMenu(int id) { super(null, id); }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public void setCarried(ItemStack stack) { writes++; super.setCarried(stack); }
    }

    private static class TestFunction implements LivingItemFunction {
        Item claimed = Items.PAPER;
        Runnable probe = () -> {};
        Consumer<ItemStack> activation = stack -> {};
        Function<ItemStack, Boolean> deactivation = stack -> true;
        int probes;
        int activated;
        int deactivated;
        @Override public String getFunctionId() { return "test_living_tag"; }
        @Override public boolean canApply(ItemStack stack) {
            if (!stack.is(claimed)) return false;
            if (!LivingItemManager.isLivingItem(stack)) return false;
            probes++;
            if (probes == 1) probe.run();
            return true;
        }
        @Override public void tick(List<SlotEntry> entries, ContainerContext context, TickContext tick, Level level) {}
        @Override public void onActivated(ItemStack stack, Level level, Player player, LivingItemActivation.Via via) {
            activated++;
            activation.accept(stack);
        }
        @Override public boolean onDeactivated(ItemStack stack, Level level, Player player, LivingItemActivation.Via via) {
            deactivated++;
            return deactivation.apply(stack);
        }
    }
}
