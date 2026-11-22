package com.qiqi.li.client.render.battle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.qiqi.li.client.render.GuiOverlays;
import com.qiqi.li.client.util.ClientMenuSlots;
import com.qiqi.li.living.domain.battle.BattleClientCache;
import com.qiqi.li.living.domain.battle.BattleEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.joml.Quaternionf;

/**
 * 容器 GUI 里的战场「摄像头」（快照 + 事件流重演）。
 *
 * <p>棋子（我方）不画：槽位图标本身就是棋子。叠加层只补客户端缺的信息——
 * 车道框线（Background，槽位之下：绿框三边 + 红线出怪边）、敌军（临时实体 + 原生
 * renderer，快照位置插值）、事件重演（{@code SWING} → 挥臂相位 + 桥红闪）。</p>
 *
 * <p>安全退化：实体渲染链任一次异常 → 本局起画 2D 点（不重试不抛出）。
 * 临时实体不入世界、不完整 tick：位置/动画全部客户端本地喂（活魂符预览同管线）。</p>
 *
 * <p>⚠️ GuiOverlays / paintUnder 都把位姿重置为<b>屏幕绝对坐标</b> ⇒ 所有绘制
 * 必须加 {@code guiLeft/guiTop}；Grid 内部存 GUI 相对坐标，出格时统一加偏移。</p>
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class BattleBoardOverlay {

    private static final int SWING_TICKS = 6;
    private static final float ZOMBIE_SCALE = 11.0F;
    /** 车道框线：绿框三边（战败线侧 + 上下沿）、红线出怪边（右端）。 */
    private static final int FRAME_GREEN = 0xFF55AA55;
    private static final int FRAME_RED = 0xFFDD4444;
    private static final int FLASH_COLOR = 0x60FF5555;
    private static final int DOT_COLOR = 0xFFCC2222;

    private static final Map<UUID, BoardEnemy> ZOMBIES = new HashMap<>();
    private static final Map<Integer, Integer> FLASH = new HashMap<>();
    private static boolean degraded = false;
    private static final ByteBufferBuilder BUFFER_MEMORY = new ByteBufferBuilder(262144);

    private BattleBoardOverlay() {}

    // ── 棋盘几何：行 = 车道，行内按列排布（GUI 相对坐标） ────────

    private record Grid(List<Row> rows) {
        boolean isEmpty() {
            return rows.isEmpty();
        }

        Row row(int lane) {
            return rows.get(Math.min(Math.max(lane, 0), rows.size() - 1));
        }

        float enemyX(int lane, float progress) {
            Row row = row(lane);
            return row.firstCenterX() + progress * (row.lastCenterX() - row.firstCenterX());
        }
    }

    private record Row(int y, int firstCenterX, int lastCenterX) {}

    /** 从快照映射出的容器槽反推网格（映射空 = 本菜单不含战场容器）。 */
    private static Grid gridOf(AbstractContainerMenu menu) {
        TreeMap<Integer, List<int[]>> byY = new TreeMap<>();
        for (Map.Entry<Integer, Integer> entry : BattleClientCache.menuSlots().entrySet()) {
            int menuIdx = entry.getKey();
            if (menuIdx < 0 || menuIdx >= menu.slots.size()) continue;
            Slot slot = menu.slots.get(menuIdx);
            if (!slot.isActive()) continue;
            byY.computeIfAbsent(slot.y, k -> new ArrayList<>()).add(new int[] {slot.x, menuIdx});
        }
        if (byY.isEmpty()) return new Grid(List.of());
        List<Row> rows = new ArrayList<>(byY.size());
        for (var entry : byY.entrySet()) {
            List<int[]> cells = entry.getValue();
            cells.sort((a, b) -> Integer.compare(a[0], b[0]));
            int firstCenterX = cells.get(0)[0] + 8;
            int lastCenterX = cells.get(cells.size() - 1)[0] + 8;
            rows.add(new Row(entry.getKey(), firstCenterX, lastCenterX));
        }
        return new Grid(rows);
    }

    // ── 渲染 ──────────────────────────────────────────────────

    @SubscribeEvent
    public static void onBackground(ContainerScreenEvent.Render.Background event) {
        if (!(event.getContainerScreen() instanceof AbstractContainerScreen<?> screen)) return;
        int menuId = ClientMenuSlots.serverMenuId(screen.getMenu());
        if (menuId < 0 || !BattleClientCache.active(menuId)) return;
        Grid grid = gridOf(screen.getMenu());
        if (grid.isEmpty()) return;
        int left = screen.getGuiLeft();
        int top = screen.getGuiTop();
        GuiGraphics graphics = event.getGuiGraphics();
        paintUnder(graphics, () -> {
            for (Row row : grid.rows()) {
                drawLaneFrame(graphics, left, top, row);
            }
        });
    }

    /** 车道框线：绿框三边 + 红线出怪边（右端 = 东 = 出怪口）。 */
    private static void drawLaneFrame(GuiGraphics graphics, int left, int top, Row row) {
        int x0 = left + row.firstCenterX() - 9;
        int x1 = left + row.lastCenterX() + 9;
        int y0 = top + row.y() - 1;
        int y1 = top + row.y() + 17;
        graphics.fill(x0, y0, x1, y0 + 1, FRAME_GREEN);   // 上沿
        graphics.fill(x0, y1 - 1, x1, y1, FRAME_GREEN);   // 下沿
        graphics.fill(x0, y0, x0 + 1, y1, FRAME_GREEN);   // 左（战败线侧）
        graphics.fill(x1 - 1, y0, x1, y1, FRAME_RED);     // 右（出怪边）
    }

    @SubscribeEvent
    public static void onForeground(ContainerScreenEvent.Render.Foreground event) {
        if (!(event.getContainerScreen() instanceof AbstractContainerScreen<?> screen)) return;
        int menuId = ClientMenuSlots.serverMenuId(screen.getMenu());
        if (menuId < 0 || !BattleClientCache.active(menuId)) return;
        Grid grid = gridOf(screen.getMenu());
        if (grid.isEmpty()) return;
        int left = screen.getGuiLeft();
        int top = screen.getGuiTop();
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        GuiGraphics graphics = event.getGuiGraphics();
        GuiOverlays.paintOverContent(graphics, () -> {
            drawFlashes(graphics, left, top, screen.getMenu());
            if (degraded) {
                drawDots(graphics, grid, left, top);
            } else {
                drawZombies(mc, grid, left, top, partial);
            }
        });
    }

    /** 桥受击红闪（事件重演的容器侧反馈）。 */
    private static void drawFlashes(GuiGraphics graphics, int left, int top, AbstractContainerMenu menu) {
        for (Map.Entry<Integer, Integer> entry : FLASH.entrySet()) {
            int menuIdx = entry.getKey();
            if (menuIdx < 0 || menuIdx >= menu.slots.size()) continue;
            Slot slot = menu.slots.get(menuIdx);
            graphics.fill(left + slot.x - 1, top + slot.y - 1,
                left + slot.x + 17, top + slot.y + 17, FLASH_COLOR);
        }
    }

    /** 安全退化的 2D 敌军点。 */
    private static void drawDots(GuiGraphics graphics, Grid grid, int left, int top) {
        for (var entry : BattleClientCache.enemies().entrySet()) {
            BattleClientCache.EnemyPos pos = entry.getValue();
            int x = Math.round(grid.enemyX(pos.lane(), pos.progress()));
            Row row = grid.row(pos.lane());
            graphics.fill(left + x - 2, top + row.y() + 5, left + x + 2, top + row.y() + 11, DOT_COLOR);
        }
    }

    /** 敌军临时实体渲染（活魂符预览同管线：GUI 矩阵 + 相机朝向临时覆写）。 */
    private static void drawZombies(Minecraft mc, Grid grid, int left, int top, float partial) {
        var dispatcher = mc.getEntityRenderDispatcher();
        Quaternionf previousCamera = new Quaternionf(dispatcher.cameraOrientation());
        try {
            MultiBufferSource.BufferSource buffer = MultiBufferSource.immediate(BUFFER_MEMORY);
            Lighting.setupForEntityInInventory();
            dispatcher.overrideCameraOrientation(new Quaternionf().rotateY((float) Math.PI));
            for (BoardEnemy enemy : ZOMBIES.values()) {
                float x = grid.enemyX(enemy.targetLane, enemy.displayProgress());
                int y = grid.row(enemy.targetLane).y();
                PoseStack pose = new PoseStack();
                pose.translate(left + x, top + y + 16, 0);
                pose.scale(ZOMBIE_SCALE, ZOMBIE_SCALE, -ZOMBIE_SCALE);
                pose.mulPose(new Quaternionf().rotateZ((float) Math.PI));
                var renderer = dispatcher.getRenderer(enemy.entity);
                if (renderer != null) {
                    renderer.render(enemy.entity, 0, partial, pose, buffer, 0xf000f0);
                }
            }
            buffer.endBatch();
        } catch (RuntimeException | LinkageError failure) {
            degraded = true;   // 安全退化：之后画 2D 点，不重试不抛出
        } finally {
            dispatcher.overrideCameraOrientation(previousCamera);
            Lighting.setupFor3DItems();
        }
    }

    /** Background 事件的槽位之下绘制（基座 Z 捕获 + 绝对坐标，同 GuiOverlays 口径）。 */
    private static void paintUnder(GuiGraphics graphics, Runnable body) {
        PoseStack pose = graphics.pose();
        float baseZ = pose.last().pose().m32();
        pose.pushPose();
        pose.setIdentity();
        pose.translate(0, 0, baseZ + 1);
        try {
            body.run();
        } finally {
            pose.popPose();
        }
    }

    // ── 生命周期与动画推进 ────────────────────────────────────

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            clearBoard();
            return;
        }
        int menuId = ClientMenuSlots.serverMenuId(screen.getMenu());
        if (menuId < 0 || !BattleClientCache.active(menuId)) {
            clearBoard();
            return;
        }
        // 事件消费 → 桥红闪 + 最近敌军挥臂（事件只有槽位定位，挥臂者客户端就近认领）
        for (BattleEvent battleEvent : BattleClientCache.consumeEvents()) {
            if (battleEvent.type() != BattleEvent.Type.SWING) continue;
            int menuIdx = BattleClientCache.menuSlotOf(battleEvent.slot());
            if (menuIdx >= 0) FLASH.put(menuIdx, SWING_TICKS);
            BoardEnemy nearest = nearestToSlot(menuIdx, screen.getMenu());
            if (nearest != null) nearest.swingTicks = SWING_TICKS;
        }
        syncEnemies();
        for (BoardEnemy enemy : ZOMBIES.values()) enemy.tick();
        FLASH.replaceAll((key, value) -> value - 1);
        FLASH.values().removeIf(value -> value <= 0);
        ZOMBIES.keySet().removeIf(id -> !BattleClientCache.enemies().containsKey(id));
    }

    @SubscribeEvent
    public static void onClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof AbstractContainerScreen<?>) clearBoard();
    }

    private static void clearBoard() {
        BattleClientCache.clear();
        ZOMBIES.clear();
        FLASH.clear();
        degraded = false;
    }

    /** 快照 → 临时实体：新建 / 目标更新（插值从当前显示位起步，不跨场瞬移）。 */
    private static void syncEnemies() {
        for (var entry : BattleClientCache.enemies().entrySet()) {
            BattleClientCache.EnemyPos pos = entry.getValue();
            BoardEnemy enemy = ZOMBIES.get(entry.getKey());
            if (enemy == null) {
                enemy = BoardEnemy.create();
                if (enemy == null) continue;
                enemy.lastProgress = pos.progress();
                ZOMBIES.put(entry.getKey(), enemy);
            } else if (pos.lane() != enemy.targetLane
                    || Math.abs(pos.progress() - enemy.targetProgress) > 1.0E-4F) {
                enemy.lastProgress = enemy.displayProgress();
                enemy.sinceUpdate = 0;
            }
            enemy.targetLane = pos.lane();
            enemy.targetProgress = pos.progress();
            // 装备重演：快照带什么演什么（spike 只重演头盔）
            if (pos.helmet() != enemy.helmet) {
                enemy.helmet = pos.helmet();
                enemy.entity.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                    pos.helmet() ? new net.minecraft.world.item.ItemStack(
                        net.minecraft.world.item.Items.IRON_HELMET)
                        : net.minecraft.world.item.ItemStack.EMPTY);
            }
        }
    }

    private static BoardEnemy nearestToSlot(int menuIdx, AbstractContainerMenu menu) {
        if (menuIdx < 0 || menuIdx >= menu.slots.size()) return null;
        Slot slot = menu.slots.get(menuIdx);
        double cx = slot.x + 8;
        double cy = slot.y + 8;
        Grid grid = gridOf(menu);
        BoardEnemy best = null;
        double bestDist = Double.MAX_VALUE;
        for (BoardEnemy enemy : ZOMBIES.values()) {
            double dx = grid.enemyX(enemy.targetLane, enemy.displayProgress()) - cx;
            double dy = grid.row(enemy.targetLane).y() + 8 - cy;
            double dist = dx * dx + dy * dy;
            if (dist < bestDist) {
                bestDist = dist;
                best = enemy;
            }
        }
        return best;
    }

    /** 临时敌军：位置/动画全部本地驱动，不入世界、不运行 AI。 */
    private static final class BoardEnemy {
        final Zombie entity;
        int targetLane;
        float targetProgress;
        float lastProgress;
        int sinceUpdate;
        int swingTicks;
        boolean helmet;

        BoardEnemy(Zombie entity) {
            this.entity = entity;
        }

        static BoardEnemy create() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return null;
            Zombie zombie = new Zombie(EntityType.ZOMBIE, mc.level);
            zombie.setOnGround(true);
            // 面向 GUI 左（推进方向）；maid 预览同管线，角度域同源
            zombie.yBodyRotO = zombie.yBodyRot = 270.0F;
            zombie.setYRot(270.0F);
            zombie.yRotO = 270.0F;
            zombie.yHeadRotO = zombie.yHeadRot = 270.0F;
            return new BoardEnemy(zombie);
        }

        float displayProgress() {
            float t = Math.min(1.0F, sinceUpdate / 4.0F);
            return lastProgress + (targetProgress - lastProgress) * t;
        }

        void tick() {
            sinceUpdate++;
            this.entity.tickCount++;
            boolean moving = Math.abs(targetProgress - lastProgress) > 1.0E-4F;
            this.entity.walkAnimation.update(moving ? 0.5F : 0.0F, 0.4F);
            if (swingTicks > 0) {
                swingTicks--;
                this.entity.swinging = true;
                this.entity.swingTime = (SWING_TICKS - swingTicks) * 2;
            } else {
                this.entity.swinging = false;
            }
        }
    }
}
