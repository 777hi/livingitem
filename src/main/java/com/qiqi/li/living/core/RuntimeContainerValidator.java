package com.qiqi.li.living.core;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.qiqi.li.living.core.model.Pos2D;

public final class RuntimeContainerValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeContainerValidator.class);

    public static final RuntimeContainerValidator INSTANCE = new RuntimeContainerValidator();

    private long validationCount = 0;
    private long failureCount = 0;

    private RuntimeContainerValidator() {}

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

    private boolean isValidSlot(int slot, int containerSize) {
        return slot >= 0 && slot < containerSize;
    }

    private int resolveWithValidation(Container container, int baseSlot, Pos2D direction, int containerSize) {
        if (direction == Pos2D.NONE) return -1;

        int resolvedSlot = SlotResolver.resolve(baseSlot, direction, containerSize);

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

    public ValidationStats getStats() {
        return new ValidationStats(validationCount, failureCount);
    }

    public record ValidationStats(long totalValidations, long failures) {
        public double successRate() {
            return totalValidations > 0 ?
                (double)(totalValidations - failures) / totalValidations : 0.0;
        }
    }

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