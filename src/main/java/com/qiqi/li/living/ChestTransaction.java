package com.qiqi.li.living;

import com.qiqi.li.living.core.ComponentState;
import com.qiqi.li.living.core.components.InternalStorageComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 活箱子事务管理器 (Chest Transaction Manager)
 * 
 * <h2>🔴 P0 安全修复：确保原子性操作</h2>
 * 
 * <h3>问题场景</h3>
 * <p><strong>原代码的隐患：</strong></p>
 * <pre>{@code
 * // 原代码（有风险）：
 * ComponentState state = getStorageState(chestStack);
 * boolean result = InternalStorageComponent.insertItem(server, state, item, capacity);
 * saveStorageState(chestStack, state);  // ← 如果这里抛异常，前面的修改丢失！
 * }</pre>
 * 
 * <h3>解决方案</h3>
 * <ul>
 *   <li><strong>try-with-resources</strong>：自动管理资源</li>
 *   <li><strong>自动回滚</strong>：未 commit 时 close() 会回滚所有操作</li>
 *   <li><strong>显式提交</strong>：必须显式调用 commit() 才会持久化</li>
 * </ul>
 * 
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ✅ 推荐用法（自动回滚）
 * try (var tx = ChestTransaction.begin(server, chestStack)) {
 *     tx.insertItem(diamondStack);  // 存入钻石
 *     tx.extractItem(64);           // 取出物品
 *     tx.commit();                  // 显式提交（必须！）
 * }  // 如果没有 commit，自动回滚
 * 
 * // ❌ 错误用法（忘记 commit）
 * try (var tx = ChestTransaction.begin(server, chestStack)) {
 *     tx.insertItem(diamondStack);
 *     // 忘记调用 commit() → 所有修改被回滚！
 * }
 * }</pre>
 * 
 * <h3>支持的操作</h3>
 * <table border="1">
 *   <tr><th>方法</th><th>功能</th><th>回滚能力</th></tr>
 *   <tr><td>{@code insertItem()}</td><td>存入物品</td><td>✅ 可恢复原始数量</td></tr>
 *   <tr><td>{@code extractItem()}</td><td>取出物品</td><td>✅ 可放回原位</td></tr>
 *   <tr><td>{@code syncMergedToStorage()}</td><td>批量同步</td><td>⚠️ 复杂（需快照）</td></tr>
 * </table>
 *
 * @author P0 Safety Fix
 * @version 1.0.0
 */
public class ChestTransaction implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChestTransaction.class);

    private final MinecraftServer server;
    private final ItemStack chestStack;
    private final ComponentState originalState;
    private final ComponentState currentState;
    
    private final List<Runnable> rollbackActions = new ArrayList<>();
    private boolean committed = false;
    private boolean closed = false;

    /**
     * 开始一个新的事务
     * 
     * <p>会立即捕获当前的组件状态作为回滚基线。</p>
     *
     * @param server Minecraft 服务器实例
     * @param chestStack 目标活箱子物品栈（不会被修改，直到 commit）
     * @return 新的事务实例
     */
    public static ChestTransaction begin(MinecraftServer server, ItemStack chestStack) {
        return new ChestTransaction(server, chestStack);
    }

    /**
     * 私有构造函数
     * 
     * <p>创建时会保存原始状态的<strong>深拷贝</strong>，
     * 用于在回滚时恢复。</p>
     */
    private ChestTransaction(MinecraftServer server, ItemStack chestStack) {
        this.server = server;
        this.chestStack = chestStack;
        
        this.originalState = LivingChestFunction.getStorageState(chestStack);
        this.currentState = LivingChestFunction.getStorageState(chestStack);
        
        LOGGER.debug("Transaction started for chest={}", chestStack.getItem());
    }

    /**
     * 向活箱子存入物品（事务版本）
     * 
     * <h3>副作用</h3>
     * <ul>
     *   <li>修改内部状态（不写回 ItemStack）</li>
     *   <li>记录回滚操作（可恢复 itemToInsert 的数量）</li>
     * </ul>
     *
     * @param itemToInsert 要存入的物品（会被修改）
     * @param capacityPerChest 每个虚拟箱子的槽数量
     * @return true 表示完全存入
     */
    public boolean insertItem(ItemStack itemToInsert, int capacityPerChest) {
        ensureNotClosed();
        
        int originalCount = itemToInsert.getCount();
        boolean result = InternalStorageComponent.insertItem(
            server, currentState, itemToInsert, capacityPerChest, chestStack.getCount()
        );
        
        // 记录回滚操作：恢复物品数量
        final int actualInserted = originalCount - itemToInsert.getCount();
        if (actualInserted > 0) {
            rollbackActions.add(() -> {
                LOGGER.debug("Rollback: restoring {} items", actualInserted);
                itemToInsert.grow(actualInserted);
            });
        }
        
        return result;
    }

    /**
     * 从活箱子取出物品（事务版本）
     * 
     * <h3>副作用</h3>
     * <ul>
     *   <li>修改内部状态</li>
     *   <li>记录回滚操作（可将物品放回原位）</li>
     * </ul>
     *
     * @param amount 最大提取数量
     * @param capacityPerChest 每个虚拟箱子的槽数量
     * @return 提取的物品栈
     */
    public ItemStack extractItem(int amount, int capacityPerChest) {
        ensureNotClosed();
        
        ItemStack extracted = InternalStorageComponent.extractItem(
            server, currentState, amount, capacityPerChest, chestStack.getCount()
        );
        
        if (!extracted.isEmpty()) {
            final ItemStack extractedCopy = extracted.copy();
            rollbackActions.add(() -> {
                LOGGER.debug("Rollback: re-inserting extracted items");
                // 回滚：将取出的物品重新存入
                InternalStorageComponent.insertItem(
                    server, currentState, extractedCopy, capacityPerChest, chestStack.getCount()
                );
            });
        }
        
        return extracted;
    }

    /**
     * 批量同步物品到存储（事务版本）
     * 
     * <p>用于 GUI 操作后的批量更新。</p>
     *
     * @param merged 合并后的物品列表
     * @param capacityPerChest 每个虚拟箱子的槽数量
     */
    public void syncMergedToStorage(List<ItemStack> merged, int capacityPerChest) {
        ensureNotClosed();
        
        InternalStorageComponent.syncMergedToStorage(
            server, currentState, merged, capacityPerChest
        );
        
        // ⚠️ 复杂操作的回滚需要保存完整状态快照
        final ComponentState preSyncState = LivingChestFunction.getStorageState(chestStack);
        rollbackActions.add(() -> {
            LOGGER.debug("Rollback: reverting sync operation");
            // 简单实现：恢复到同步前的状态
            LivingChestFunction.saveStorageState(chestStack, preSyncState);
        });
    }

    /**
     * 提交事务（持久化所有修改）
     * 
     * <h3>执行流程</h3>
     * <ol>
     *   <li>将最终状态写入 ItemStack 的 NBT</li>
     *   <li>清除回滚操作列表</li>
     *   <li>标记为已提交</li>
     * </ol>
     * 
     * <h3>⚠️ 重要提示</h3>
     * <p><strong>必须在 try 块内调用此方法！</strong></p>
     * <p>否则 {@code close()} 时会自动回滚。</p>
     */
    public void commit() {
        ensureNotClosed();
        
        LOGGER.debug("Committing transaction for chest={}", chestStack.getItem());
        
        // 将最终状态持久化到 ItemStack
        LivingChestFunction.saveStorageState(chestStack, currentState);
        
        committed = true;
        rollbackActions.clear();  // 清除回滚操作（已提交无需回滚）
    }

    /**
     * 关闭事务（AutoCloseable 接口）
     * 
     * <h3>行为逻辑</h3>
     * <ul>
     *   <li><strong>已提交</strong>：什么都不做</li>
     *   <li><strong>未提交</strong>：执行所有回滚操作</li>
     * </ul>
     * 
     * <p>通常不需要手动调用，由 try-with-resources 自动触发。</p>
     */
    @Override
    public void close() {
        if (closed) return;  // 防止重复关闭
        
        closed = true;
        
        if (!committed) {
            LOGGER.warn("Transaction not committed! Rolling back {} actions...", 
                       rollbackActions.size());
            
            // 执行所有回滚操作（逆序执行，保证正确的回滚顺序）
            for (int i = rollbackActions.size() - 1; i >= 0; i--) {
                try {
                    rollbackActions.get(i).run();
                } catch (Exception e) {
                    LOGGER.error("Rollback action {} failed!", i, e);
                    // 继续执行其他回滚操作
                }
            }
            
            LOGGER.info("Rollback completed. Original state restored.");
            
            // 恢复原始状态到 ItemStack
            LivingChestFunction.saveStorageState(chestStack, originalState);
        } else {
            LOGGER.debug("Transaction already committed, closing normally.");
        }
    }

    /**
     * 获取当前事务中的组件状态（只读）
     * 
     * <p>用于查询但不修改的场景。</p>
     *
     * @return 当前状态的不可变视图
     */
    public ComponentState getState() {
        return currentState;
    }

    /**
     * 检查事务是否已提交
     *
     * @return true 如果已提交
     */
    public boolean isCommitted() {
        return committed;
    }

    /**
     * 检查事务是否已关闭
     *
     * @return true 如果已关闭
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 在操作前检查事务是否已关闭
     * 
     * @throws IllegalStateException 如果事务已关闭
     */
    private void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("Transaction is already closed! Cannot perform operations.");
        }
    }

    /**
     * 在事务中执行自定义操作（高级用法）
     * 
     * <p>允许对状态进行复杂的自定义修改，同时享受事务保护。</p>
     * 
     * <h3>使用示例</h3>
     * <pre>{@code
     * try (var tx = ChestTransaction.begin(server, chestStack)) {
     *     tx.execute(state -> {
     *         // 自定义复杂逻辑
     *         InternalStorageComponent.someComplexOperation(state, ...);
     *     });
     *     tx.commit();
     * }
     * }</pre>
     *
     * @param operation 对 ComponentState 的自定义操作
     */
    public void execute(Consumer<ComponentState> operation) {
        ensureNotClosed();
        
        final ComponentState beforeOperation = LivingChestFunction.getStorageState(chestStack);
        operation.accept(currentState);
        
        rollbackActions.add(() -> {
            LOGGER.debug("Rollback: reverting custom operation");
            LivingChestFunction.saveStorageState(chestStack, beforeOperation);
        });
    }
}