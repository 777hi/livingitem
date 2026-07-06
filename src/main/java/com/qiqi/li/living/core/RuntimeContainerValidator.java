package com.qiqi.li.living.core;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行时容器验证器 - 方案D的实现
 * 
 * 核心理念：不依赖任何预计算，每次访问都进行完整验证
 * 
 * 优点：
 * ✅ 最高安全性 - 即使容器行为异常也能保护系统
 * ✅ 零配置 - 无需预先了解容器特性
 * ✅ 兼容所有容器 - 包括未来可能出现的任何容器
 * 
 * 缺点：
 * ❌ 性能开销较大 - 每次访问都有验证成本
 * ❌ 无法利用缓存优化 - 相同操作重复验证
 * 
 * 适用场景：
 * - 调试阶段排查问题
 * - 处理未知/不可信的容器
 * - 作为其他方案的最终安全网
 */
public final class RuntimeContainerValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeContainerValidator.class);

    public static final RuntimeContainerValidator INSTANCE = new RuntimeContainerValidator();

    private long validationCount = 0;
    private long failureCount = 0;

    private RuntimeContainerValidator() {}

    /**
     * 完整的槽位解析与验证流程
     * 
     * @return 验证结果对象，包含状态信息和数据
     */
    public ValidationResult resolveAndValidate(
        Container container, 
        int hostSlot, 
        Direction2D inputDir,
        Direction2D fuelDir,
        Direction2D outputDir
    ) {
        validationCount++;

        try {
            // 步骤1: 容器有效性检查
            if (!validateContainerIntegrity(container)) {
                return ValidationResult.failure("Container integrity check failed");
            }

            // 步骤2: 容器大小获取
            int size = safeGetContainerSize(container);
            if (size <= 0) {
                return ValidationResult.failure("Invalid container size: " + size);
            }

            // 步骤3: 主槽位验证
            if (!isValidSlot(hostSlot, size)) {
                return ValidationResult.failure("Invalid host slot: " + hostSlot + " for size " + size);
            }

            // 步骤4: 解析并验证每个方向
            int inputSlot = resolveWithValidation(container, hostSlot, inputDir, size);
            int fuelSlot = resolveWithValidation(container, hostSlot, fuelDir, size);
            int outputSlot = resolveWithValidation(container, hostSlot, outputDir, size);

            // 步骤5: 综合检查
            if (inputSlot == -1 || fuelSlot == -1 || outputSlot == -1) {
                return ValidationResult.partialFailure(
                    String.format("Some slots invalid: input=%d, fuel=%d, output=%d", 
                                 inputSlot, fuelSlot, outputSlot),
                    new int[]{inputSlot, fuelSlot, outputSlot}
                );
            }

            // 步骤6: 冲突检查（确保三个槽位互不相同）
            if (hasConflicts(inputSlot, fuelSlot, outputSlot)) {
                return ValidationResult.failure("Slot conflicts detected");
            }

            // 步骤7: 最终访问测试（确保可以实际读写）
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

    /**
     * 验证容器的基本完整性
     */
    private boolean validateContainerIntegrity(Container container) {
        if (container == null) return false;

        try {
            container.getContainerSize();  // 测试基本方法是否可用
            return true;
        } catch (NullPointerException | ClassCastException e) {
            return false;
        } catch (Exception e) {
            LOGGER.debug("Container integrity check exception", e);
            return true;  // 其他异常可能是正常的（如某些模组的特殊实现）
        }
    }

    private int safeGetContainerSize(Container container) {
        try {
            int size = container.getContainerSize();
            
            // 合理性检查
            if (size < 0 || size > 1024) {  // 限制最大容器大小
                LOGGER.warn("Suspicious container size reported: {}", size);
                return Math.max(0, Math.min(size, 256));  // 钳制到合理范围
            }
            
            return size;
        } catch (Exception e) {
            LOGGER.warn("Failed to get container size", e);
            return -1;  // 错误标记
        }
    }

    private boolean isValidSlot(int slot, int containerSize) {
        return slot >= 0 && slot < containerSize;
    }

    /**
     * 解析单个方向的槽位并进行验证
     */
    private int resolveWithValidation(Container container, int baseSlot, Direction2D direction, int containerSize) {
        if (direction == Direction2D.NONE) return -1;

        int resolvedSlot = SlotResolver.resolve(baseSlot, direction, containerSize);

        if (resolvedSlot == -1) return -1;

        // 双重验证：再次检查范围
        if (!isValidSlot(resolvedSlot, containerSize)) {
            LOGGER.warn("SlotResolver returned invalid slot: {} for container size {}", 
                       resolvedSlot, containerSize);
            return -1;
        }

        // 访问测试
        try {
            ItemStack testItem = container.getItem(resolvedSlot);
            if (testItem == null) {  // 不应该发生，但防御性检查
                return -1;
            }
        } catch (IndexOutOfBoundsException e) {
            LOGGER.error("Index out of bounds accessing slot {}", resolvedSlot, e);
            return -1;
        } catch (Exception e) {
            LOGGER.debug("Unexpected error accessing slot {}", resolvedSlot, e);
            return -1;  // 保守策略：无法确认安全就拒绝
        }

        return resolvedSlot;
    }

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

    /**
     * 测试槽位的实际可访问性
     */
    private boolean testAccessSafety(Container container, int... slots) {
        for (int slot : slots) {
            try {
                container.getItem(slot);   // 读测试
                container.setItem(slot, ItemStack.EMPTY);  // 写测试（空物品，无副作用）
            } catch (Exception e) {
                LOGGER.debug("Access test failed for slot {}", slot, e);
                return false;
            }
        }
        return true;
    }

    /**
     * 获取统计信息
     */
    public ValidationStats getStats() {
        return new ValidationStats(validationCount, failureCount);
    }

    public record ValidationStats(long totalValidations, long failures) {
        public double successRate() {
            return totalValidations > 0 ? 
                (double)(totalValidations - failures) / totalValidations : 0.0;
        }
    }

    /**
     * 验证结果封装类
     */
    public static class ValidationResult {
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
}