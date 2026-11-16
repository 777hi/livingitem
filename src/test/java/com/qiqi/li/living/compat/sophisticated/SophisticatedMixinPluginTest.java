package com.qiqi.li.living.compat.sophisticated;

import static com.qiqi.li.living.compat.sophisticated.MixinInjectionSignatureCheckTest.bytes;
import static com.qiqi.li.living.compat.sophisticated.MixinInjectionSignatureCheckTest.handler;
import static com.qiqi.li.living.compat.sophisticated.MixinInjectionSignatureCheckTest.method;
import static com.qiqi.li.living.compat.sophisticated.MixinInjectionSignatureCheckTest.mixin;
import static com.qiqi.li.living.compat.sophisticated.MixinInjectionSignatureCheckTest.node;
import static com.qiqi.li.living.compat.sophisticated.MixinInjectionSignatureCheckTest.target;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.qiqi.li.living.compat.sophisticated.MixinInjectionSignatureCheckTest.ResourceLoader;

class SophisticatedMixinPluginTest {
    private static final String PROBE = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlock";

    @Test
    void absentMod_skipsWithoutReadingTargetOrMixin() {
        ResourceLoader loader = new ResourceLoader();
        SophisticatedMixinPlugin plugin = new SophisticatedMixinPlugin(loader);
        plugin.onLoad("test");

        assertFalse(plugin.shouldApplyMixin("test.Target", "test.Mixin"));
        assertTrue(loader.reads.isEmpty());
        assertEquals(0, loader.loads);
    }

    @Test
    void compatibleClasses_areReadWithoutLoading() {
        ResourceLoader loader = installed();
        loader.put("test.Target", bytes(target("run()V")));
        loader.put("test.Mixin", bytes(mixin("run()V")));

        assertTrue(new SophisticatedMixinPlugin(loader).shouldApplyMixin("test.Target", "test.Mixin"));
        assertEquals(java.util.List.of("test/Target.class", "test/Mixin.class"), loader.reads);
        assertEquals(0, loader.loads);
    }

    @Test
    void missingTarget_skips() {
        ResourceLoader loader = installed();
        loader.put("test.Mixin", bytes(mixin("run()V")));
        assertFalse(new SophisticatedMixinPlugin(loader).shouldApplyMixin("test.Target", "test.Mixin"));
        assertEquals(0, loader.loads);
    }

    @Test
    void missingMixin_skips() {
        ResourceLoader loader = installed();
        loader.put("test.Target", bytes(target("run()V")));
        assertFalse(new SophisticatedMixinPlugin(loader).shouldApplyMixin("test.Target", "test.Mixin"));
        assertEquals(0, loader.loads);
    }

    @Test
    void malformedTargetResource_skips() {
        ResourceLoader loader = installed();
        loader.put("test.Target", new byte[] {0, 1, 2});
        loader.put("test.Mixin", bytes(mixin("run()V")));
        assertFalse(new SophisticatedMixinPlugin(loader).shouldApplyMixin("test.Target", "test.Mixin"));
    }

    @Test
    void malformedMixinResource_skips() {
        ResourceLoader loader = installed();
        loader.put("test.Target", bytes(target("run()V")));
        loader.put("test.Mixin", new byte[] {0, 1, 2});
        assertFalse(new SophisticatedMixinPlugin(loader).shouldApplyMixin("test.Target", "test.Mixin"));
    }

    @Test
    void resourceIdentityMismatch_skips() {
        ResourceLoader loader = installed();
        loader.put("test.Target", bytes(node("test/WrongTarget")));
        loader.put("test.Mixin", bytes(mixin("run()V")));
        assertFalse(new SophisticatedMixinPlugin(loader).shouldApplyMixin("test.Target", "test.Mixin"));
    }

    @Test
    void sameNameChangedSignature_skipsWholeMixin() {
        ResourceLoader loader = installed();
        loader.put("test.Target", bytes(target("run(J)V")));
        loader.put("test.Mixin", bytes(mixin("run(I)V")));
        assertFalse(new SophisticatedMixinPlugin(loader).shouldApplyMixin("test.Target", "test.Mixin"));
    }

    @Test
    void eachMixinUsesItsOwnDeclarations() {
        ResourceLoader loader = installed();
        var target = target("first()V");
        target.methods.add(method("second()V"));
        loader.put("test.Target", bytes(target));
        var firstMixin = mixin("first()V");
        firstMixin.name = "test/FirstMixin";
        var secondMixin = mixin("second()V");
        secondMixin.name = "test/SecondMixin";
        loader.put("test.FirstMixin", bytes(firstMixin));
        loader.put("test.SecondMixin", bytes(secondMixin));
        SophisticatedMixinPlugin plugin = new SophisticatedMixinPlugin(loader);

        assertTrue(plugin.shouldApplyMixin("test.Target", "test.FirstMixin"));
        assertTrue(plugin.shouldApplyMixin("test.Target", "test.SecondMixin"));
        target.methods.removeLast();
        loader.put("test.Target", bytes(target));
        assertTrue(plugin.shouldApplyMixin("test.Target", "test.FirstMixin"));
        assertFalse(plugin.shouldApplyMixin("test.Target", "test.SecondMixin"));
    }

    @Test
    void missingOneEnd_skipsRatherThanAcceptingOtherEnd() {
        ResourceLoader loader = installed();
        loader.put("test.Target", bytes(target("first()V")));
        var mixin = mixin("first()V");
        mixin.methods.add(handler("otherEnd", "second()V"));
        loader.put("test.Mixin", bytes(mixin));
        assertFalse(new SophisticatedMixinPlugin(loader).shouldApplyMixin("test.Target", "test.Mixin"));
    }

    private static ResourceLoader installed() {
        ResourceLoader loader = new ResourceLoader();
        loader.put(PROBE, bytes(node(PROBE.replace('.', '/'))));
        return loader;
    }
}
