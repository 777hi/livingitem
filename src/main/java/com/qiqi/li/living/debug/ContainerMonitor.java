package com.qiqi.li.living.debug;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.qiqi.li.living.api.LivingItemManager;
import com.qiqi.li.living.container.ContainerContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 容器监控系统 —— 实时追踪容器状态变化，检测物品复制/丢失/异常移动。
 *
 * <h3>使用方式</h3>
 * <ol>
 *   <li>通过命令启用监控：{@code /living_monitor on}</li>
 *   <li>监控系统自动对比处理前后快照，检测异常</li>
 *   <li>异常报告同时写入日志文件和聊天栏</li>
 * </ol>
 *
 * <h3>检测能力</h3>
 * <ul>
 *   <li><b>物品复制</b>：某物品数量增加但无对应来源</li>
 *   <li><b>物品丢失</b>：某物品数量减少但无对应去向</li>
 *   <li><b>活物品覆盖</b>：活物品被非活物品覆盖</li>
 *   <li><b>异常槽位变化</b>：非活物品功能涉及的槽位被意外修改</li>
 * </ul>
 */
public class ContainerMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger("LivingItem/Monitor");

    private static final Path LOG_PATH = Path.of("logs", "living_item_container_monitor.log");

    private static final Map<String, Boolean> ENABLED_CONTAINERS = new ConcurrentHashMap<>();
    private static volatile boolean globalEnabled = false;
    private static volatile MinecraftServer server;

    private static final Map<String, ContainerState> BEFORE_STATES = new ConcurrentHashMap<>();

    private static PrintWriter logWriter;

    private ContainerMonitor() {}

    public static void setServer(MinecraftServer srv) {
        server = srv;
    }

    public static void setGlobalEnabled(boolean enabled) {
        globalEnabled = enabled;
        if (enabled) {
            openLog();
        } else {
            closeLog();
        }
    }

    public static boolean isGlobalEnabled() {
        return globalEnabled;
    }

    public static void enable(String containerKey) {
        ENABLED_CONTAINERS.put(containerKey, Boolean.TRUE);
        openLog();
    }

    public static void disable(String containerKey) {
        ENABLED_CONTAINERS.remove(containerKey);
    }

    public static boolean isEnabled(String containerKey) {
        return globalEnabled || ENABLED_CONTAINERS.containsKey(containerKey);
    }

    private static synchronized void openLog() {
        if (logWriter != null) return;
        try {
            Files.createDirectories(LOG_PATH.getParent());
            logWriter = new PrintWriter(Files.newBufferedWriter(LOG_PATH,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND));
        } catch (IOException e) {
            LOGGER.error("Failed to open monitor log: {}", e.getMessage());
        }
    }

    private static synchronized void closeLog() {
        if (logWriter != null) {
            logWriter.close();
            logWriter = null;
        }
    }

    private static synchronized void writeLog(String line) {
        if (logWriter != null) {
            logWriter.println(line);
            logWriter.flush();
        }
    }

    private static void broadcastToOps(String message) {
        MinecraftServer srv = server;
        if (srv == null) return;
        try {
            srv.execute(() -> {
                for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
                    if (srv.getPlayerList().isOp(player.getGameProfile())) {
                        player.sendSystemMessage(Component.literal(message));
                    }
                }
            });
        } catch (Exception ignored) {}
    }

    public static void beforeProcess(String containerKey, ContainerContext ctx) {
        if (!isEnabled(containerKey)) return;
        ContainerState state = captureState(ctx);
        BEFORE_STATES.put(containerKey, state);
    }

    public static void afterProcess(String containerKey, ContainerContext ctx) {
        if (!isEnabled(containerKey)) return;

        ContainerState before = BEFORE_STATES.remove(containerKey);
        if (before == null) return;

        ContainerState after = captureState(ctx);
        ContainerDiff diff = computeDiff(before, after);
        if (diff.hasAnomalies()) {
            String report = diff.formatReport(containerKey, before, after);
            writeLog(report);
            String chatSummary = "[Monitor] " + diff.summary() + " in " + containerKey + " | " + diff.shortDetail();
            broadcastToOps(chatSummary);
            LOGGER.warn("[ContainerMonitor] Anomaly in '{}': {}", containerKey, diff.summary());
        }
    }

    public static void dumpState(String containerKey, ContainerContext ctx) {
        ContainerState state = captureState(ctx);
        String dump = state.formatDump(containerKey);
        writeLog(dump);
        broadcastToOps(dump);
    }

    // ===== 内部 =====

    private static ContainerState captureState(ContainerContext ctx) {
        int size = ctx.getSize();
        List<SlotState> slots = new ArrayList<>(size);
        Map<String, Integer> itemCounts = new LinkedHashMap<>();

        for (int i = 0; i < size; i++) {
            ItemStack stack = ctx.getItem(i);
            slots.add(new SlotState(i, stack.copy()));
            if (!stack.isEmpty()) {
                String key = stack.getItem().toString();
                itemCounts.merge(key, stack.getCount(), Integer::sum);
            }
        }

        return new ContainerState(slots, itemCounts, System.nanoTime());
    }

    private static ContainerDiff computeDiff(ContainerState before, ContainerState after) {
        List<SlotChange> changes = new ArrayList<>();
        Map<String, Integer> beforeCounts = new LinkedHashMap<>(before.itemCounts);
        Map<String, Integer> afterCounts = new LinkedHashMap<>(after.itemCounts);

        int minSize = Math.min(before.slots.size(), after.slots.size());
        for (int i = 0; i < minSize; i++) {
            SlotState bs = before.slots.get(i);
            SlotState as = after.slots.get(i);

            boolean wasLiving = LivingItemManager.isLivingItem(bs.stack);
            boolean isLiving = LivingItemManager.isLivingItem(as.stack);

            if (wasLiving && !isLiving && !as.stack.isEmpty()) {
                changes.add(new SlotChange(i, bs.stack, as.stack, ChangeType.LIVING_OVERWRITTEN));
            } else if (!ItemStack.matches(bs.stack, as.stack)) {
                changes.add(new SlotChange(i, bs.stack, as.stack, ChangeType.MODIFIED));
            }
        }

        for (int i = minSize; i < after.slots.size(); i++) {
            SlotState as = after.slots.get(i);
            if (!as.stack.isEmpty()) {
                changes.add(new SlotChange(i, ItemStack.EMPTY, as.stack, ChangeType.ADDED));
            }
        }

        Map<String, Integer> gained = new LinkedHashMap<>();
        Map<String, Integer> lost = new LinkedHashMap<>();

        for (var entry : afterCounts.entrySet()) {
            int beforeCount = beforeCounts.getOrDefault(entry.getKey(), 0);
            int diff = entry.getValue() - beforeCount;
            if (diff > 0) gained.put(entry.getKey(), diff);
        }
        for (var entry : beforeCounts.entrySet()) {
            int afterCount = afterCounts.getOrDefault(entry.getKey(), 0);
            int diff = afterCount - entry.getValue();
            if (diff < 0) lost.put(entry.getKey(), -diff);
        }

        return new ContainerDiff(changes, gained, lost);
    }

    enum ChangeType { MODIFIED, ADDED, LIVING_OVERWRITTEN }

    record SlotState(int slot, ItemStack stack) {}

    record SlotChange(int slot, ItemStack before, ItemStack after, ChangeType type) {
        String format() {
            return switch (type) {
                case LIVING_OVERWRITTEN -> String.format("  slot[%d]: LIVING_OVERWRITTEN! %s -> %s",
                    slot, itemDesc(before), itemDesc(after));
                case MODIFIED -> String.format("  slot[%d]: %s -> %s",
                    slot, itemDesc(before), itemDesc(after));
                case ADDED -> String.format("  slot[%d]: (empty) -> %s",
                    slot, itemDesc(after));
            };
        }

        static String itemDesc(ItemStack stack) {
            if (stack.isEmpty()) return "(empty)";
            String living = LivingItemManager.isLivingItem(stack) ? "[L]" : "";
            return stack.getItem() + "x" + stack.getCount() + living;
        }
    }

    record ContainerState(List<SlotState> slots, Map<String, Integer> itemCounts, long timestamp) {
        String formatDump(String containerKey) {
            StringBuilder sb = new StringBuilder();
            sb.append("=== DUMP: ").append(containerKey).append(" ===\n");
            for (SlotState ss : slots) {
                if (!ss.stack.isEmpty()) {
                    String living = LivingItemManager.isLivingItem(ss.stack) ? " [LIVING]" : "";
                    sb.append(String.format("  slot[%d]: %s x%d%s\n",
                        ss.slot, ss.stack.getItem(), ss.stack.getCount(), living));
                }
            }
            sb.append("  total: ").append(itemCounts.values().stream().mapToInt(Integer::intValue).sum());
            return sb.toString();
        }
    }

    record ContainerDiff(List<SlotChange> changes, Map<String, Integer> gained, Map<String, Integer> lost) {
        boolean hasAnomalies() {
            if (!gained.isEmpty() || !lost.isEmpty()) return true;
            for (SlotChange sc : changes) {
                if (sc.type == ChangeType.LIVING_OVERWRITTEN) return true;
            }
            return false;
        }

        String summary() {
            if (!gained.isEmpty() && !lost.isEmpty()) return "ITEM_DUP_AND_LOSS";
            if (!gained.isEmpty()) return "ITEM_DUPLICATION";
            if (!lost.isEmpty()) return "ITEM_LOSS";
            for (SlotChange sc : changes) {
                if (sc.type == ChangeType.LIVING_OVERWRITTEN) return "LIVING_OVERWRITTEN";
            }
            return "UNKNOWN";
        }

        String shortDetail() {
            StringBuilder sb = new StringBuilder();
            if (!gained.isEmpty()) {
                sb.append("Gained: ");
                for (var e : gained.entrySet()) sb.append("+").append(e.getValue()).append("x ").append(e.getKey()).append(" ");
            }
            if (!lost.isEmpty()) {
                sb.append("Lost: ");
                for (var e : lost.entrySet()) sb.append("-").append(e.getValue()).append("x ").append(e.getKey()).append(" ");
            }
            for (SlotChange sc : changes) {
                if (sc.type == ChangeType.LIVING_OVERWRITTEN) {
                    sb.append("slot[").append(sc.slot).append("] LIVING_OVERWRITTEN ");
                }
            }
            return sb.toString().trim();
        }

        String formatReport(String containerKey, ContainerState before, ContainerState after) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%d] === ANOMALY: %s === %s\n",
                System.currentTimeMillis(), containerKey, summary()));

            if (!gained.isEmpty()) {
                sb.append("  GAINED (possible duplication):\n");
                for (var e : gained.entrySet()) {
                    sb.append("    +").append(e.getValue()).append("x ").append(e.getKey()).append('\n');
                }
            }
            if (!lost.isEmpty()) {
                sb.append("  LOST:\n");
                for (var e : lost.entrySet()) {
                    sb.append("    -").append(e.getValue()).append("x ").append(e.getKey()).append('\n');
                }
            }

            sb.append("  SLOT CHANGES:\n");
            for (SlotChange sc : changes) {
                sb.append(sc.format()).append('\n');
            }

            sb.append("  BEFORE:\n");
            for (SlotState ss : before.slots) {
                if (!ss.stack.isEmpty()) {
                    sb.append(String.format("    slot[%d]: %s x%d%s\n",
                        ss.slot, ss.stack.getItem(), ss.stack.getCount(),
                        LivingItemManager.isLivingItem(ss.stack) ? " [L]" : ""));
                }
            }
            sb.append("  AFTER:\n");
            for (SlotState ss : after.slots) {
                if (!ss.stack.isEmpty()) {
                    sb.append(String.format("    slot[%d]: %s x%d%s\n",
                        ss.slot, ss.stack.getItem(), ss.stack.getCount(),
                        LivingItemManager.isLivingItem(ss.stack) ? " [L]" : ""));
                }
            }

            return sb.toString();
        }
    }
}