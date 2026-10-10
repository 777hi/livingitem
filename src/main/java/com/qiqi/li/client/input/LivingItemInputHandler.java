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

        event.setCanceled(true);

        if (currentSession == null) {
            currentSession = new InputSession();
            beginHopperHud();
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
        if (!isDirectionKeyCode(event.getKeyCode())) return;
        event.setCanceled(true);
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

    // -------------------- 方向输入 HUD --------------------

    /**
     * 方向输入会话的 HUD 快照 —— 供容器界面 render TAIL 绘制（mixin 只画框，不解析业务）。
     *
     * @param slotNameKeys 槽位名的翻译键（如 "slot.livingitem.input"）
     * @param typedSymbols 已输入的方向符号（与槽位顺序对齐）
     * @param currentSymbols 会话开始时各槽位的旧方向符号（"—" = 未配置 / 未知）
     * @param hopperMode true = 活漏斗两键模式（源 → 目标）
     */
    public record DirectionHudState(List<String> slotNameKeys, List<String> typedSymbols,
                                    List<String> currentSymbols, boolean hopperMode) {}

    private static DirectionHudState activeHud = null;

    /** HUD 渲染行（null = 无会话）。 */
    public static List<Component> getActiveHudLines() {
        if (activeHud == null) return null;
        List<Component> lines = new ArrayList<>();
        int total = activeHud.slotNameKeys().size();
        int typed = activeHud.typedSymbols().size();
        lines.add(Component.translatable(
            activeHud.hopperMode() ? "hud.livingitem.hopper.title" : "hud.livingitem.direction.title",
            typed, total));
        for (int i = 0; i < total; i++) {
            String symbol = i < typed ? activeHud.typedSymbols().get(i)
                : i < activeHud.currentSymbols().size() ? activeHud.currentSymbols().get(i) : "—";
            lines.add(Component.translatable("tooltip.livingitem.direction.slot",
                Component.translatable(activeHud.slotNameKeys().get(i)), symbol));
        }
        lines.add(Component.translatable(
                activeHud.hopperMode() ? "hud.livingitem.hopper.hint" : "hud.livingitem.direction.hint")
            .withStyle(ChatFormatting.GRAY));
        return lines;
    }

    private static void beginHopperHud() {
        ItemStack carried = Minecraft.getInstance().player.containerMenu.getCarried();
        List<String> current = new ArrayList<>();
        DirectionTransferData dirData = LivingHopperFunction.readDirectionData(carried);
        current.add(dirData != null ? dirData.sourceOffset().getSymbol() : "—");
        current.add(dirData != null ? dirData.targetOffset().getSymbol() : "—");
        activeHud = new DirectionHudState(
            List.of("hud.livingitem.hopper.source", "hud.livingitem.hopper.target"),
            new ArrayList<>(), current, true);
    }

    private static void beginDirectionHud(HasDirection dirFunc, ItemStack carried) {
        String[] names = dirFunc.getDirectionSlotNames();
        List<String> keys = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String name : names) {
            keys.add("slot.livingitem." + name);
            Pos2D d = dirFunc.getSlotDirection(carried, name);
            current.add(d != null ? d.getSymbol() : "—");
        }
        activeHud = new DirectionHudState(keys, new ArrayList<>(), current, false);
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
        activeHud = new DirectionHudState(activeHud.slotNameKeys(), typed,
            activeHud.currentSymbols(), activeHud.hopperMode());
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
     * 监听世界 tick 事件，检查输入会话是否超时。
     *
     * 超时后会话自动失效，用户需要重新开始输入。
     */
    @SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel)) return;
        if (currentSession != null && currentSession.isTimedOut()) {
            LOGGER.debug("Input session timed out: {}", currentSession.getRawInput());
            currentSession = null;
            activeHud = null;
        }
        if (directionSession != null && directionSession.isTimedOut()) {
            LOGGER.debug("Direction session timed out: {}", directionSession.getRawInput());
            directionSession = null;
            activeHud = null;
        }
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

        if (dirFunc.getDirectionKeyCount() == 1) {
            sendSlotDirectionPacket(functionId, dirFunc.getDirectionSlotNames()[0], direction);
            return;
        }

        if (directionSession == null) {
            directionSession = new DirectionSession(dirFunc.getDirectionKeyCount());
            beginDirectionHud(dirFunc, Minecraft.getInstance().player.containerMenu.getCarried());
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
     * 通用方向输入会话 —— 管理多键 WASD 输入的生命周期。
     *
     * 适用于任意需要多键配向的活物品（如活熔炉 3 键）。
     * 超时后会话自动失效，避免残留的半输入状态。
     */
    private static class DirectionSession {
        private final StringBuilder keys = new StringBuilder();
        private final long startTime;
        private final int maxKeys;

        private static final long TIMEOUT_MS = 3000;

        DirectionSession(int maxKeys) {
            this.maxKeys = maxKeys;
            this.startTime = System.currentTimeMillis();
        }

        void appendKey(char key) {
            if (keys.length() < maxKeys) {
                keys.append(key);
            }
        }

        boolean isComplete() {
            return keys.length() >= maxKeys;
        }

        boolean isTimedOut() {
            return System.currentTimeMillis() - startTime > TIMEOUT_MS;
        }

        String getRawInput() {
            return keys.toString();
        }
    }

    /**
     * 活漏斗输入会话 —— 管理 2 键 WASD 输入的生命周期。
     *
     * 输入规则：
     * - 第 1 个键：源方向
     * - 第 2 个键：目标方向
     * - 示例："WD" = 上传下
     *
     * 超时机制：2 秒内未完成输入则会话失效。
     */
    private static class InputSession {
        private final StringBuilder keys = new StringBuilder();
        private final long startTime;

        private static final long TIMEOUT_MS = 2000;
        private static final int MAX_KEYS = 2;

        InputSession() {
            this.startTime = System.currentTimeMillis();
        }

        void appendKey(char key) {
            if (keys.length() < MAX_KEYS) {
                keys.append(key);
            }
        }

        boolean isComplete() {
            return keys.length() >= MAX_KEYS;
        }

        boolean isTimedOut() {
            return System.currentTimeMillis() - startTime > TIMEOUT_MS;
        }

        String getRawInput() {
            return keys.toString();
        }
    }
}