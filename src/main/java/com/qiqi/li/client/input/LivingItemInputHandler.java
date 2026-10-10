package com.qiqi.li.client.input;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.qiqi.li.client.gui.LivingButton;
import com.qiqi.li.living.api.LivingItemFunction;
import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.api.HasDirection;
import com.qiqi.li.living.domain.hopper.LivingHopperFunction;
import com.qiqi.li.living.domain.hopper.DirectionTransferData;
import com.qiqi.li.living.domain.map.LivingMapClientCache;
import com.qiqi.li.living.model.Pos2D;
import com.qiqi.li.living.model.SlotMapping;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.qiqi.li.LivingItem;
import com.qiqi.li.network.HopperDirectionPacket;
import com.qiqi.li.network.SlotDirectionPacket;
import com.qiqi.li.network.ToolMemoryClearPacket;
import com.qiqi.li.living.domain.tools.LivingToolMemory;
import com.qiqi.li.living.domain.tools.LivingToolRecorder;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 活物品客户端输入处理器 —— 处理 WASD 键入配置活漏斗传输方向。
 *
 * 功能概述：
 * 在容器 GUI 中，将活漏斗拿起悬停在活按钮上方时，
 * 通过 WASD 键入来改变活漏斗的传输方向。
 *
 * 输入规则：
 * - W = 上方（UP）
 * - A = 左方（LEFT）
 * - S = 下方（DOWN）
 * - D = 右方（RIGHT）
 * - 需要输入 2 个键：第 1 个 = 源方向，第 2 个 = 目标方向
 * - 示例："WD" = 上传下，"AD" = 左传右
 *
 * 前置条件（全部满足才处理输入）：
 * 1. 当前屏幕是容器 GUI（AbstractContainerScreen）
 * 2. 鼠标悬停在活按钮（LivingButton）上
 * 3. 光标上持有活漏斗
 * 4. 输入的字符是有效的 WASD 键
 *
 * 输入会话机制（InputSession）：
 * - 每次输入创建一个会话，最多接受 2 个键
 * - 超时时间 2 秒，超时后会话自动失效
 * - 2 个键输入完成后立即处理并发送网络包
 *
 * 通信流程：
 *   WASD 键入 → onCharTyped() → InputSession 收集
 * → processInput() → 解析方向 → sendDirectionPacket()
 * → HopperDirectionPacket → 服务端 ServerPacketHandler
 */
@EventBusSubscriber(modid = LivingItem.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public class LivingItemInputHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LivingItemInputHandler.class);

    /** 当前活跃的输入会话 */
    private static InputSession currentSession = null;
    private static DirectionSession directionSession = null;

    /**
     * 断开连接时清空活地图元数据缓存，避免切换存档/服务器后残留旧 mapId 的中心坐标。
     */
    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LivingMapClientCache.clear();
    }

    /**
     * 左键空气 → 通知服务端清除记忆（{@code L13}）。
     *
     * <p>{@code LeftClickEmpty} <b>只在客户端触发</b>（NeoForge 注释：
     * "The server is not aware of when the client left clicks empty space,
     * you will need to tell the server yourself."），故必须由客户端发包。</p>
     *
     * <p>只在「确实有对应记忆」时才发包 —— 避免无意义的网络流量。</p>
     *
     * <p>⭐ 两条分支（判据不同，别合并）：</p>
     * <ul>
     *   <li>手持<b>活工具</b>且有挖掘记忆 ⇒ 清<b>挖掘</b>记忆</li>
     *   <li>手持<b>活武器</b>且有攻击记忆 ⇒ 清<b>攻击</b>记忆（这一刀挥空了，没打到怪）</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        ItemStack tool = mc.player.getMainHandItem();
        LivingToolMemory memory = LivingToolMemory.of(tool);

        if (LivingToolRecorder.isLivingTool(tool) && memory.hasDig()) {
            PacketDistributor.sendToServer(
                new ToolMemoryClearPacket(ToolMemoryClearPacket.KIND_DIG));
            return;
        }
        // ⭐ 活武器：挥空 = 这一刀没打到怪 ⇒ 清掉攻击记忆（回到"无记忆"⇒ 重新上环）
        if (LivingToolRecorder.isLivingWeapon(tool) && memory.hasAttack()) {
            PacketDistributor.sendToServer(
                new ToolMemoryClearPacket(ToolMemoryClearPacket.KIND_ATTACK));
        }
    }

    /**
     * 监听字符输入事件。
     *
     * 检查前置条件后，将有效的 WASD 键加入当前输入会话。
     * 会话完成后（2 个键），解析方向并发送网络包。
     */
    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (!(mc.screen instanceof AbstractContainerScreen)) return;

        if (!isLivingButtonHovered()) return;

        ItemStack carried = mc.player.containerMenu.getCarried();
        if (carried.isEmpty()) return;

        if (!LivingItemManager.isLivingItem(carried)) return;

        char typedChar = event.getCodePoint();
        char upperChar = Character.toUpperCase(typedChar);

        if (!isValidKey(upperChar)) return;

        LivingItemFunction dirFunc = findDirectionFunction(carried);
        if (dirFunc instanceof HasDirection dir) {
            event.setCanceled(true);
            processDirectionInput(upperChar, dirFunc.getFunctionId(), dir);
            return;
        }

        if (!isHopperItem(carried)) return;

        // 漏斗不支持对角槽位：对角键直接忽略（不进入会话、不 cancel）
        Pos2D hopperDir = keyToDirection(upperChar);
        if (hopperDir != null && hopperDir.isDiagonal()) return;

        event.setCanceled(true);

        if (currentSession == null) {
            currentSession = new InputSession();
            beginHud(true);
        }

        currentSession.appendKey(upperChar);
        refreshHudTyped();

        if (currentSession.isComplete()) {
            processInput(currentSession.getRawInput(), carried);
            currentSession = null;
            activeHud = null;
        }
    }

    // ==================== 按键屏蔽 + HUD（2026-10-10，九宫格方案） ====================

    /**
     * ⭐ 九宫格屏蔽：「光标持活物品 + 悬停活按钮」时拦截 8 个方向键的<b>按键事件</b>。
     *
     * <p>为什么必须拦在 KeyPressed 而不只是 CharacterTyped：GLFW 的按键事件<b>先于</b>字符事件 ——
     * 等字符事件到来时，Q 的丢弃 / E 的关界面已经发生。故在按键阶段直接 cancel，
     * 让这 8 个键在该场景下只服务于方向输入。</p>
     */
    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!shouldCaptureDirectionInput()) return;
        int keyCode = event.getKeyCode();
        if (!isDirectionKeyCode(keyCode)) return;
        // ⭐ 对角键只在目标功能声明支持时才屏蔽（supportsDiagonal 默认 false）——
        // 否则会无谓地吃掉 Q（丢弃）/ E（关界面）：不支持对角的活物品上，这两键应保持原版行为
        if (isDiagonalKeyCode(keyCode) && !targetSupportsDiagonal()) return;
        event.setCanceled(true);
    }

    /** 当前光标物品的方向功能是否声明支持对角槽位（漏斗不支持）。 */
    private static boolean targetSupportsDiagonal() {
        ItemStack carried = Minecraft.getInstance().player.containerMenu.getCarried();
        LivingItemFunction func = findDirectionFunction(carried);
        return func instanceof HasDirection dir && dir.supportsDiagonal();
    }

    /** 会话进行到一半时关闭界面 → 丢弃半输入状态（避免残留到下一个界面）。 */
    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        currentSession = null;
        directionSession = null;
        activeHud = null;
    }

    /**
     * 方向输入的总前置条件 —— 按键拦截与字符输入<b>共用同一判据</b>（改一处即改两处）。
     */
    private static boolean shouldCaptureDirectionInput() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (!(mc.screen instanceof AbstractContainerScreen)) return false;
        if (mc.screen.getFocused() instanceof EditBox) return false; // 文本框（重命名等）输入不受影响
        if (!isLivingButtonHovered()) return false;
        ItemStack carried = mc.player.containerMenu.getCarried();
        if (carried.isEmpty() || !LivingItemManager.isLivingItem(carried)) return false;
        return findDirectionFunction(carried) != null || isHopperItem(carried);
    }

    // -------------------- 方向配置 HUD（悬停即显示） --------------------

    /**
     * HUD 的输入进度快照 —— 只承载「已输入」的符号列；槽位名与当前方向每帧从光标物品**实时读**
     * （避免快照过期）。null = 尚未开始输入（预览态）。
     *
     * @param typedSymbols 已输入的方向符号（与槽位顺序对齐）
     * @param hopperMode true = 活漏斗两键模式（源 → 目标）
     */
    public record DirectionHudState(List<String> typedSymbols, boolean hopperMode) {}

    private static DirectionHudState activeHud = null;

    /**
     * 方向配置 HUD 是否可见 —— 「悬停活按钮 + 光标持方向类活物品」即显示
     * （2026-10-10 起由「输入时显示」改为「**悬浮即显示**」：玩家不必先盲按一次才知道有这功能）。
     * 活按钮据此让位自己的 tooltip（两者同为 tooltip 会重叠）。
     */
    public static boolean isDirectionHudVisible() {
        return shouldCaptureDirectionInput();
    }

    /**
     * HUD 渲染行（null = 不显示）。预览态列出各槽位当前方向；输入态叠加进度与高亮
     * （已输入=绿、下一待输入=黄并带 ▶ 前缀、其余=灰）。
     */
    public static List<Component> getHudLines() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !shouldCaptureDirectionInput()) return null;
        ItemStack carried = mc.player.containerMenu.getCarried();
        LivingItemFunction func = findDirectionFunction(carried);
        boolean hopperMode = func == null;               // 能走到这里且无 HasDirection ⇒ 漏斗
        boolean diagonal;
        List<Component> slotNames = new ArrayList<>();
        List<String> currentSymbols = new ArrayList<>();
        if (hopperMode) {
            diagonal = false;
            slotNames.add(Component.translatable("hud.livingitem.hopper.source"));
            slotNames.add(Component.translatable("hud.livingitem.hopper.target"));
            DirectionTransferData dirData = LivingHopperFunction.readDirectionData(carried);
            currentSymbols.add(dirData != null ? dirData.sourceOffset().getSymbol() : "—");
            currentSymbols.add(dirData != null ? dirData.targetOffset().getSymbol() : "—");
        } else {
            HasDirection dir = (HasDirection) func;
            diagonal = dir.supportsDiagonal();
            for (String name : dir.getDirectionSlotNames()) {
                slotNames.add(Component.translatable("slot.livingitem." + name));
                Pos2D d = dir.getSlotDirection(carried, name);
                currentSymbols.add(d != null ? d.getSymbol() : "—");
            }
        }

        int total = slotNames.size();
        List<String> typed = activeHud != null ? activeHud.typedSymbols() : List.of();
        int done = Math.min(typed.size(), total);

        List<Component> lines = new ArrayList<>();
        if (activeHud == null) {                         // 预览态：不显示进度
            lines.add(Component.translatable(hopperMode
                ? "hud.livingitem.hopper.title_preview" : "hud.livingitem.direction.title_preview"));
        } else {
            lines.add(Component.translatable(hopperMode
                ? "hud.livingitem.hopper.title" : "hud.livingitem.direction.title", done, total));
        }
        for (int i = 0; i < total; i++) {
            boolean isDone = i < done;
            boolean isNext = i == done && done < total;
            String symbol = isDone ? typed.get(i) : currentSymbols.get(i);
            ChatFormatting color = isDone ? ChatFormatting.GREEN
                : isNext ? ChatFormatting.YELLOW : ChatFormatting.GRAY;
            Component nameComp = Component.literal(isNext ? "▶ " : "  ")
                .append(slotNames.get(i)).withStyle(color);
            Component symbolComp = Component.literal(symbol).withStyle(color);
            lines.add(Component.translatable("hud.livingitem.slot.line", nameComp, symbolComp));
        }
        String hintKey = hopperMode ? "hud.livingitem.hopper.hint"
            : diagonal ? "hud.livingitem.direction.hint" : "hud.livingitem.direction.hint_basic";
        lines.add(Component.translatable(hintKey).withStyle(ChatFormatting.GRAY));
        return lines;
    }

    private static void beginHud(boolean hopperMode) {
        activeHud = new DirectionHudState(new ArrayList<>(), hopperMode);
    }

    /** 会话追加按键后，刷新「已输入」符号列（两个会话类型共用）。 */
    private static void refreshHudTyped() {
        if (activeHud == null) return;
        String raw = directionSession != null ? directionSession.getRawInput()
            : currentSession != null ? currentSession.getRawInput() : "";
        List<String> typed = new ArrayList<>();
        for (char k : raw.toCharArray()) {
            Pos2D d = keyToDirection(k);
            typed.add(d != null ? d.getSymbol() : "?");
        }
        activeHud = new DirectionHudState(typed, activeHud.hopperMode());
    }

    /** 检查鼠标是否悬停在活按钮上 */
    private static boolean isLivingButtonHovered() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) return false;

        for (GuiEventListener child : mc.screen.children()) {
            if (child instanceof LivingButton button && button.isHovered()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 每 tick 检查会话存活：**鼠标离开活按钮即重置**（2026-10-10 起取代 3 秒超时——
     * 只要一直悬浮在按钮上就保持输入窗口，符合直觉，不再中途丢失半输入）。
     */
    @SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel)) return;
        if (currentSession == null && directionSession == null) return;
        if (isLivingButtonHovered()) return;
        LOGGER.debug("Direction session reset (cursor left the living button)");
        currentSession = null;
        directionSession = null;
        activeHud = null;
    }

    /**
     * 处理完整的输入序列。
     *
     * 1. 通过 LivingHopperFunction.readDirectionState() 读取当前方向状态
     * 2. 通过 DirectionModeComponent 解析输入
     * 3. 获取解析后的 SlotMapping
     * 4. 发送网络包到服务端
     *
     * @param rawInput 原始输入字符串（如 "WD"、"AD"）
     * @param hopperStack 光标上的活漏斗 ItemStack
     */
    private static void processInput(String rawInput, ItemStack hopperStack) {
        DirectionTransferData dirData = LivingHopperFunction.readDirectionData(hopperStack);
        if (dirData == null) {
            LOGGER.debug("Failed to read direction data from hopper");
            return;
        }

        Pos2D source = dirData.sourceOffset();
        Pos2D target = dirData.targetOffset();

        if (rawInput.length() >= 1) {
            Pos2D newSource = keyToDirection(rawInput.charAt(0));
            if (newSource != null) source = newSource;
        }
        if (rawInput.length() >= 2) {
            Pos2D newTarget = keyToDirection(rawInput.charAt(1));
            if (newTarget != null) target = newTarget;
        }

        SlotMapping mapping = SlotMapping.fromDirections(source, target);
        if (mapping == null) {
            LOGGER.debug("No mapping resolved from input: {}", rawInput);
            return;
        }

        sendDirectionPacket(mapping);

        LOGGER.debug("Updated hopper direction: {} -> {}", rawInput, mapping.displayName());
    }

    private static Pos2D keyToDirection(char key) {
        return switch (Character.toUpperCase(key)) {
            case 'W' -> Pos2D.UP;
            case 'S' -> Pos2D.DOWN;
            case 'A' -> Pos2D.LEFT;
            case 'D' -> Pos2D.RIGHT;
            // ⭐ 九宫格对角（2026-10-10）：键位 = 键盘左上角物理布局。
            // ⚠️ 不用 X（用户拍板：与 S 的「下」直觉冲突）——左下=Z、右下=C，提示文案同理不提 X
            case 'Q' -> Pos2D.UP_LEFT;
            case 'E' -> Pos2D.UP_RIGHT;
            case 'Z' -> Pos2D.DOWN_LEFT;
            case 'C' -> Pos2D.DOWN_RIGHT;
            default -> null;
        };
    }

    /** 九宫格 8 键对应的 GLFW 键码 —— 按键阶段拦截用（Q=丢弃 / E=关界面，必须赶在原版之前）。 */
    private static boolean isDirectionKeyCode(int keyCode) {
        return switch (keyCode) {
            case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D,
                 GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_C -> true;
            default -> false;
        };
    }

    /** 4 个对角键（屏蔽需按能力放行，见 {@link #targetSupportsDiagonal()}）。 */
    private static boolean isDiagonalKeyCode(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_Q || keyCode == GLFW.GLFW_KEY_E
            || keyCode == GLFW.GLFW_KEY_Z || keyCode == GLFW.GLFW_KEY_C;
    }

    private static boolean isValidKey(char key) {
        return keyToDirection(key) != null;
    }

    /** 发送方向配置网络包到服务端 */
    private static void sendDirectionPacket(SlotMapping mapping) {
        PacketDistributor.sendToServer(new HopperDirectionPacket(mapping.toNBT()));
    }

    /** 检查物品是否为活漏斗 */
    private static boolean isHopperItem(ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.HOPPER) &&
               LivingItemManager.isLivingItem(stack);
    }

    /** 查找支持方向配置的活物品功能 */
    private static LivingItemFunction findDirectionFunction(ItemStack stack) {
        for (LivingItemFunction func : LivingItemManager.getApplicableFunctions(stack)) {
            if (func instanceof HasDirection) {
                return func;
            }
        }
        return null;
    }

    /**
     * 通用方向输入处理 —— 自动适配 1 键或多键输入。
     *
     * 1 键模式：立即发送（活红石火把）
     * 多键模式：使用 DirectionSession 收集，完成后统一发送（活熔炉）
     */
    private static void processDirectionInput(char key, String functionId, HasDirection dirFunc) {
        Pos2D direction = keyToDirection(key);
        if (direction == null) return;
        // 对角槽位是声明式能力（supportsDiagonal 默认 false）：未声明 ⇒ 忽略对角键
        if (direction.isDiagonal() && !dirFunc.supportsDiagonal()) return;

        if (dirFunc.getDirectionKeyCount() == 1) {
            sendSlotDirectionPacket(functionId, dirFunc.getDirectionSlotNames()[0], direction);
            return;
        }

        if (directionSession == null) {
            directionSession = new DirectionSession(dirFunc.getDirectionKeyCount());
            beginHud(false);
        }
        directionSession.appendKey(key);
        refreshHudTyped();

        if (directionSession.isComplete()) {
            processDirectionSession(directionSession, functionId, dirFunc);
            directionSession = null;
        }
    }

    /** 完成多键方向输入后，依次发送每个槽位的方向包 */
    private static void processDirectionSession(DirectionSession session, String functionId, HasDirection dirFunc) {
        String[] slotNames = dirFunc.getDirectionSlotNames();
        String keys = session.getRawInput();

        for (int i = 0; i < slotNames.length && i < keys.length(); i++) {
            Pos2D direction = keyToDirection(keys.charAt(i));
            if (direction != null) {
                sendSlotDirectionPacket(functionId, slotNames[i], direction);
            }
        }

        LOGGER.debug("Updated direction: {} total={}", keys, slotNames.length);
        activeHud = null;
    }

    /** 发送槽位方向配置网络包到服务端 */
    private static void sendSlotDirectionPacket(String functionId, String slotName, Pos2D direction) {
        PacketDistributor.sendToServer(new SlotDirectionPacket(
            functionId, slotName, direction.x(), direction.y()));
    }

    /**
     * 通用方向输入会话 —— 管理多键方向输入的生命周期。
     *
     * <p>适用于任意需要多键配向的活物品（如活熔炉 3 键）。
     * <b>不设超时</b>：存活由「鼠标是否仍在活按钮上」决定（{@link #onLevelTick}）。</p>
     */
    private static class DirectionSession {
        private final StringBuilder keys = new StringBuilder();
        private final int maxKeys;

        DirectionSession(int maxKeys) {
            this.maxKeys = maxKeys;
        }

        void appendKey(char key) {
            if (keys.length() < maxKeys) {
                keys.append(key);
            }
        }

        boolean isComplete() {
            return keys.length() >= maxKeys;
        }

        String getRawInput() {
            return keys.toString();
        }
    }

    /**
     * 活漏斗输入会话 —— 管理 2 键 WASD 输入的生命周期。
     *
     * <p>输入规则：第 1 个键 = 源方向，第 2 个键 = 目标方向；示例 "WD" = 上传下。
     * <b>不支持对角</b>（漏斗的槽位映射只有 4 个基本方向）。
     * <b>不设超时</b>：存活由「鼠标是否仍在活按钮上」决定。</p>
     */
    private static class InputSession {
        private final StringBuilder keys = new StringBuilder();

        private static final int MAX_KEYS = 2;

        void appendKey(char key) {
            if (keys.length() < MAX_KEYS) {
                keys.append(key);
            }
        }

        boolean isComplete() {
            return keys.length() >= MAX_KEYS;
        }

        String getRawInput() {
            return keys.toString();
        }
    }
}