package com.qiqi.li.living.core.components;

import java.util.*;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import com.qiqi.li.living.ContainerContext;
import com.qiqi.li.living.core.ComponentConfig;
import com.qiqi.li.living.core.ComponentContext;
import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.model.Pos2D;
import com.qiqi.li.living.core.model.SlotMapping;

public class DirectionModeComponent implements ILivingComponent {

    public static final String ID = "direction_mode";

    public enum DirectionMode {
        SLOTS,
        TRANSFER
    }

    @FunctionalInterface
    public interface KeyParser {
        Optional<SlotMapping> parse(String rawInput);
    }

    private final DirectionMode mode;
    private final Map<String, Pos2D> defaultSlots;
    private final SlotMapping defaultMapping;
    private final Set<Character> validKeys;
    private final KeyParser keyParser;
    private final int minInputs;
    private final int maxInputs;

    public DirectionModeComponent() {
        this(DirectionMode.TRANSFER, Map.of(), SlotMapping.UP_TO_DOWN,
             Set.of('W', 'A', 'S', 'D'), new WASDSequenceParser(), 2, 2);
    }

    public DirectionModeComponent(Map<String, Pos2D> defaultSlots) {
        this(DirectionMode.SLOTS, defaultSlots, null, Set.of(), null, 0, 0);
    }

    public DirectionModeComponent(Set<Character> validKeys, KeyParser parser,
                                  SlotMapping defaultMapping, int minInputs, int maxInputs) {
        this(DirectionMode.TRANSFER, Map.of(), defaultMapping, validKeys, parser, minInputs, maxInputs);
    }

    private DirectionModeComponent(DirectionMode mode, Map<String, Pos2D> defaultSlots,
                                   SlotMapping defaultMapping, Set<Character> validKeys,
                                   KeyParser keyParser, int minInputs, int maxInputs) {
        this.mode = mode;
        this.defaultSlots = Collections.unmodifiableMap(new HashMap<>(defaultSlots));
        this.defaultMapping = defaultMapping;
        this.validKeys = Collections.unmodifiableSet(new HashSet<>(validKeys));
        this.keyParser = keyParser;
        this.minInputs = minInputs;
        this.maxInputs = maxInputs;
    }

    @Override
    public String getComponentId() { return ID; }

    @Override
    public void tick(ComponentContext ctx, int hostSlot, ItemStack hostStack,
                     ComponentState state, ComponentConfig config) {
    }

    @Override
    public ComponentState createDefaultState() {
        ComponentState state = new ComponentState();
        if (mode == DirectionMode.SLOTS) {
            for (var entry : defaultSlots.entrySet()) {
                String key = "slot_" + entry.getKey();
                state.setInt(key + "_x", entry.getValue().x());
                state.setInt(key + "_y", entry.getValue().y());
            }
        } else {
            if (defaultMapping != null) {
                state.setInt("src_x", defaultMapping.sourceOffset().x());
                state.setInt("src_y", defaultMapping.sourceOffset().y());
                state.setInt("tgt_x", defaultMapping.targetOffset().x());
                state.setInt("tgt_y", defaultMapping.targetOffset().y());
            }
        }
        return state;
    }

    @Override
    public void appendTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        if (mode == DirectionMode.SLOTS) {
            appendSlotsTooltip(state, tooltipAdder);
        } else {
            appendTransferTooltip(state, tooltipAdder);
        }
    }

    private void appendSlotsTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        for (String slotName : defaultSlots.keySet()) {
            Pos2D dir = getDirection(state, slotName);
            if (dir != null && dir != Pos2D.NONE) {
                tooltipAdder.accept(Component.literal(
                    slotName + ": " + dir.getSymbol()
                ).withStyle(net.minecraft.ChatFormatting.GRAY));
            }
        }
    }

    private void appendTransferTooltip(ComponentState state, Consumer<Component> tooltipAdder) {
        SlotMapping mapping = getCurrentMapping(state);
        if (mapping != null) {
            tooltipAdder.accept(Component.literal(
                "传输方向: " + mapping.displaySymbol() + " " + mapping.displayName()
            ).withStyle(net.minecraft.ChatFormatting.GOLD));
        }
    }

    public DirectionMode getMode() { return mode; }

    public Pos2D getDirection(ComponentState state, String slotName) {
        String key = "slot_" + slotName;
        int x = state.getInt(key + "_x", Integer.MIN_VALUE);
        int y = state.getInt(key + "_y", Integer.MIN_VALUE);

        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) {
            return defaultSlots.getOrDefault(slotName, Pos2D.NONE);
        }

        return new Pos2D(x, y);
    }

    public boolean setDirection(ComponentState state, String slotName, Pos2D direction) {
        if (mode != DirectionMode.SLOTS) return false;
        String key = "slot_" + slotName;
        state.setInt(key + "_x", direction.x());
        state.setInt(key + "_y", direction.y());
        return true;
    }

    public SlotMapping getCurrentMapping(ComponentState state) {
        if (mode != DirectionMode.TRANSFER) return null;

        int srcX = state.getInt("src_x", Integer.MIN_VALUE);
        int srcY = state.getInt("src_y", Integer.MIN_VALUE);
        int tgtX = state.getInt("tgt_x", Integer.MIN_VALUE);
        int tgtY = state.getInt("tgt_y", Integer.MIN_VALUE);

        if (srcX == Integer.MIN_VALUE || tgtX == Integer.MIN_VALUE) {
            return defaultMapping;
        }

        Pos2D source = new Pos2D(srcX, srcY);
        Pos2D target = new Pos2D(tgtX, tgtY);

        return findMatchingPreset(source, target)
            .orElseGet(() -> new SlotMapping(source, target,
                source.getSymbol() + "→" + target.getSymbol(),
                "自定义"));
    }

    public boolean updateMapping(ComponentState state, SlotMapping newMapping) {
        if (mode != DirectionMode.TRANSFER || newMapping == null) return false;

        state.setInt("src_x", newMapping.sourceOffset().x());
        state.setInt("src_y", newMapping.sourceOffset().y());
        state.setInt("tgt_x", newMapping.targetOffset().x());
        state.setInt("tgt_y", newMapping.targetOffset().y());
        return true;
    }

    public boolean updateFromInput(ComponentState state, String rawInput) {
        if (mode != DirectionMode.TRANSFER || keyParser == null || rawInput == null) return false;

        if (!validateInput(rawInput)) return false;

        Optional<SlotMapping> parsed = keyParser.parse(rawInput);
        return parsed.map(mapping -> updateMapping(state, mapping)).orElse(false);
    }

    private boolean validateInput(String rawInput) {
        if (rawInput.isEmpty()) return false;
        if (rawInput.length() < minInputs || rawInput.length() > maxInputs) return false;

        for (char c : rawInput.toUpperCase().toCharArray()) {
            if (!validKeys.contains(c)) return false;
        }
        return true;
    }

    private Optional<SlotMapping> findMatchingPreset(Pos2D source, Pos2D target) {
        for (var preset : SlotMapping.PRESETS) {
            if (preset.sourceOffset().equals(source) && preset.targetOffset().equals(target)) {
                return Optional.of(preset);
            }
        }
        return Optional.empty();
    }

    public Map<String, Pos2D> getDefaultSlots() {
        return defaultSlots;
    }

    public SlotMapping getDefaultMapping() {
        return defaultMapping;
    }

    public Set<Character> getValidKeys() {
        return validKeys;
    }

    public static class WASDSequenceParser implements KeyParser {
        private static final Map<Character, Pos2D> KEY_MAP = Map.of(
            'W', Pos2D.UP,
            'S', Pos2D.DOWN,
            'A', Pos2D.LEFT,
            'D', Pos2D.RIGHT
        );

        @Override
        public Optional<SlotMapping> parse(String rawInput) {
            if (rawInput == null || rawInput.length() < 2) return Optional.empty();

            String upper = rawInput.toUpperCase();
            Pos2D source = KEY_MAP.get(upper.charAt(0));
            Pos2D target = KEY_MAP.get(upper.charAt(1));

            if (source == null || target == null) return Optional.empty();

            return Optional.of(new SlotMapping(source, target,
                source.getSymbol() + "→" + target.getSymbol(),
                source.getSymbol() + "到" + target.getSymbol()));
        }
    }
}