package com.qiqi.li.living.core;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.qiqi.li.living.core.model.Pos2D;

/**
 * 运行时容器验证器 —— 验证槽位解析结果的安全性和正确性。
 *
 * 在槽位解析完成后进行额外的安全检查，确保：
 * 1. 容器完整性：容器对象可用且方法可调用
 * 2. 槽位有效性：解析出的槽位索引在合法范围内
 * 3. 访问安全性：槽位可安全读写（不会抛出异常）
 * 4. 无冲突：输入/燃料/输出槽位不重叠
 *
 * 默认不启用（影响性能），可通过 HybridContainerResolver.setRuntimeValidation(true) 开启。
 * 主要用于调试和排查非标准容器的兼容性问题。
 */
public final class RuntimeContainerValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeContainerValidator.class);

    /** 单例实例 */
    public static final RuntimeContainerValidator INSTANCE = new RuntimeContainerValidator();

    private long validationCount = 0;
    private long failureCount = 0;

    private RuntimeContainerValidator() {}

    /**
     * 解析并验证指定方向的槽位。
     *
     * @param container 目标容器
     * @param hostSlot 活物品所在槽位
     * @param inputDir 输入方向
     * @param fuelDir 燃料方向
     * @param outputDir 输出方向
     * @return 验证结果（包含状态、槽位数组、失败原因等）
     */
    public ValidationResult resolveAndValidate(
        Container container,
        int hostSlot,
        Pos2D inputDir,
        Pos2D fuelDir,
        Pos2D outputDir
    ) {
        validationCount++;

        try {
            if (!validateContainerIntegrity(container)) {
                return ValidationResult.failure("Container integrity check failed");
            }

            int size = safeGetContainerSize(container);
            if (size <= 0) {
                return ValidationResult.failure("Invalid container size: " + size);
            }

            if (!isValidSlot(hostSlot, size)) {
                return ValidationResult.failure("Invalid host slot: " + hostSlot + " for size " + size);
            }

            int inputSlot = resolveWithValidation(container, hostSlot, inputDir, size);
            int fuelSlot = resolveWithValidation(container, hostSlot, fuelDir, size);
            int outputSlot = resolveWithValidation(container, hostSlot, outputDir, size);

            if (inputSlot == -1 || fuelSlot == -1 || outputSlot == -1) {
                return ValidationResult.partialFailure(
                    String.format("Some slots invalid: input=%d, fuel=%d, output=%d",
                                 inputSlot, fuelSlot, outputSlot),
                    new int[]{inputSlot, fuelSlot, outputSlot}
                );
            }

            if (hasConflicts(inputSlot, fuelSlot, outputSlot)) {
                return ValidationResult.failure("Slot conflicts detected");
            }

            if (!testAccessSafety(container, inputSlot, fuelSlot, outputSlot)) {
                return ValidationResult.failure("Access safety test failed");
            }

            return ValidationResult.success(new int[]{inputSlot, fuelSlot, outputSlot});

        } catch (Exception e) {
            failureCount++;
            LOGGER.warn("Unexpected error during container validation", e);
            return ValidationResult.exception(e);
        }
    }

    /** 验证容器对象完整性（方法是否可调用） */
    private boolean validateContainerIntegrity(Container container) {
        if (container == null) return false;

        try {
            container.getContainerSize();
            return true;
        } catch (NullPointerException | ClassCastException e) {
            return false;
        } catch (Exception e) {
            LOGGER.debug("Container integrity check exception", e);
            return true;
        }
    }

    /** 安全获取容器大小（带异常保护和合理性检查） */
    private int safeGetContainerSize(Container container) {
        try {
            int size = container.getContainerSize();

            if (size < 0 || size > 1024) {
                LOGGER.warn("Suspicious container size reported: {}", size);
                return Math.max(0, Math.min(size, 256));
            }

            return size;
        } catch (Exception e) {
            LOGGER.warn("Failed to get container size", e);
            return -1;
        }
    }

    /** 检查槽位索引是否在合法范围内 */
    private boolean isValidSlot(int slot, int containerSize) {
        return slot >= 0 && slot < containerSize;
    }

    /** 解析方向并验证槽位可访问性 */
    private int resolveWithValidation(Container container, int baseSlot, Pos2D direction, int containerSize) {
        if (direction == Pos2D.NONE) return -1;

        int width = getContainerWidth(container);
        int resolvedSlot = SlotResolver.resolve(baseSlot, direction, containerSize, width);

        if (resolvedSlot == -1) return -1;

        if (!isValidSlot(resolvedSlot, containerSize)) {
            LOGGER.warn("SlotResolver returned invalid slot: {} for container size {}",
                       resolvedSlot, containerSize);
            return -1;
        }

        try {
            ItemStack testItem = container.getItem(resolvedSlot);
            if (testItem == null) {
                return -1;
            }
        } catch (IndexOutOfBoundsException e) {
            LOGGER.error("Index out of bounds accessing slot {}", resolvedSlot, e);
            return -1;
        } catch (Exception e) {
            LOGGER.debug("Unexpected error accessing slot {}", resolvedSlot, e);
            return -1;
        }

        return resolvedSlot;
    }

    /** 检查槽位之间是否有冲突（同一槽位被多个角色使用） */
    private boolean hasConflicts(int... slots) {
        for (int i = 0; i < slots.length; i++) {
            for (int j = i + 1; j < slots.length; j++) {
                if (slots[i] != -1 && slots[i] == slots[j]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 测试槽位的读写安全性 */
    private boolean testAccessSafety(Container container, int... slots) {
        for (int slot : slots) {
            try {
                container.getItem(slot);
                container.setItem(slot, ItemStack.EMPTY);
            } catch (Exception e) {
                LOGGER.debug("Access test failed for slot {}", slot, e);
                return false;
            }
        }
        return true;
    }

    /** 获取验证统计信息 */
    public ValidationStats getStats() {
        return new ValidationStats(validationCount, failureCount);
    }

    /** 验证统计记录 */
    public record ValidationStats(long totalValidations, long failures) {
        public double successRate() {
            return totalValidations > 0 ?
                (double)(totalValidations - failures) / totalValidations : 0.0;
        }
    }

    /**
     * 验证结果 —— 封装槽位验证的输出。
     *
     * 状态类型：
     * - SUCCESS：所有槽位有效且无冲突
     * - PARTIAL_FAILURE：部分槽位无效
     * - FAILURE：验证失败
     * - EXCEPTION：验证过程中发生异常
     */
    public static class ValidationResult {
        /** 验证状态 */
        enum Status {
            SUCCESS,
            PARTIAL_FAILURE,
            FAILURE,
            EXCEPTION
        }

        private final Status status;
        private final int[] resolvedSlots;
        private final String message;
        private final Exception exception;

        private ValidationResult(Status status, int[] resolvedSlots, String message, Exception exception) {
            this.status = status;
            this.resolvedSlots = resolvedSlots;
            this.message = message;
            this.exception = exception;
        }

        public static ValidationResult success(int[] slots) {
            return new ValidationResult(Status.SUCCESS, slots, null, null);
        }

        public static ValidationResult partialFailure(String message, int[] slots) {
            return new ValidationResult(Status.PARTIAL_FAILURE, slots, message, null);
        }

        public static ValidationResult failure(String message) {
            return new ValidationResult(Status.FAILURE, null, message, null);
        }

        public static ValidationResult exception(Exception e) {
            return new ValidationResult(Status.EXCEPTION, null, e.getMessage(), e);
        }

        public boolean isSuccess() { return status == Status.SUCCESS; }
        public boolean isPartialFailure() { return status == Status.PARTIAL_FAILURE; }
        public boolean isFailure() { return status == Status.FAILURE || status == Status.EXCEPTION; }

        public int[] getResolvedSlots() { return resolvedSlots; }
        public String getMessage() { return message; }
        public Exception getException() { return exception; }
        public Status getStatus() { return status; }

        @Override
        public String toString() {
            return String.format("ValidationResult{status=%s, message=%s}", status, message);
        }
    }

    private int getContainerWidth(Container container) {
        var adapter = com.qiqi.li.living.core.adapters.AdapterRegistry.getInstance().findAdapter(container);
        if (adapter != null) {
            try {
                var layout = adapter.getLayout(container);
                if (layout != null && layout.columns() > 0) {
                    return layout.columns();
                }
            } catch (Exception e) {
                // 回退到下一策略
            }
        }

        int size = container.getContainerSize();
        if (size > 0 && size % 9 != 0) {
            for (int w = 9; w >= 1; w--) {
                if (size % w == 0) return w;
            }
        }

        return SlotResolver.DEFAULT_WIDTH;
    }}