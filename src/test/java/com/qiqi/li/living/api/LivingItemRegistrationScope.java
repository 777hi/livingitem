package com.qiqi.li.living.api;

import java.util.ArrayList;
import java.util.List;

/** 测试源注册作用域；安装与恢复只影响 Function 表，不重复注册领域副作用。 */
public final class LivingItemRegistrationScope implements AutoCloseable {
    private final LivingItemManager.RegistrationState original;
    private boolean closed;

    private LivingItemRegistrationScope(List<LivingItemFunction> functions) {
        List<LivingItemFunction> fixture = List.copyOf(functions);
        original = LivingItemManager.snapshotForTest();
        LivingItemManager.clearRegistrationForTest();
        try {
            for (LivingItemFunction function : fixture) LivingItemManager.registerFunction(function);
        } catch (RuntimeException | Error exception) {
            LivingItemManager.restoreForTest(original);
            throw exception;
        }
    }

    public static LivingItemRegistrationScope isolated(List<LivingItemFunction> functions) {
        return new LivingItemRegistrationScope(functions);
    }

    public static LivingItemRegistrationScope withAdditional(LivingItemFunction function) {
        List<LivingItemFunction> functions = new ArrayList<>(LivingItemManager.getAllFunctions());
        functions.add(function);
        return new LivingItemRegistrationScope(functions);
    }

    @Override
    public void close() {
        if (closed) return;
        LivingItemManager.restoreForTest(original);
        closed = true;
    }
}
