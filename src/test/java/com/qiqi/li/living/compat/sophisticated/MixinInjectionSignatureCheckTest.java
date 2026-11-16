package com.qiqi.li.living.compat.sophisticated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.injection.struct.InjectorGroupInfo;
import org.spongepowered.asm.mixin.injection.throwables.InjectionValidationException;

class MixinInjectionSignatureCheckTest {
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";

    @Test
    void exactSignature_isAccepted() {
        assertTrue(MixinInjectionSignatureCheck.check(target("run(I)V"), mixin("run(I)V")).isEmpty());
    }

    @Test
    void renamedMethod_isRejected() {
        assertRejected(target("other(I)V"), mixin("run(I)V"), "目标方法或签名不匹配");
    }

    @Test
    void sameNameDifferentArgument_isRejected() {
        assertRejected(target("run(J)V"), mixin("run(I)V"), "目标方法或签名不匹配");
    }

    @Test
    void sameNameDifferentReturn_isRejected() {
        assertRejected(target("run(I)Z"), mixin("run(I)V"), "目标方法或签名不匹配");
    }

    @Test
    void overloadDoesNotHideMatchingSignature() {
        ClassNode target = target("run(J)V");
        target.methods.add(method("run(I)V"));
        assertTrue(MixinInjectionSignatureCheck.check(target, mixin("run(I)V")).isEmpty());
    }

    @Test
    void missingOneHandlerTarget_rejectsWholeMixin() {
        ClassNode mixin = mixin("first()V");
        mixin.methods.add(handler("second", "second()V"));
        var failures = MixinInjectionSignatureCheck.check(target("first()V"), mixin);
        assertEquals(1, failures.size());
        assertEquals("second", failures.getFirst().handler());
        assertEquals("second()V", failures.getFirst().selector());
    }

    @Test
    void incompleteAndMalformedSelectors_areRejected() {
        for (String selector : List.of("run", "run(I)", "run(V)V", "run()Vtrailing", "run(*)V")) {
            assertRejected(target("run(I)V"), mixin(selector), "必须声明完整方法签名");
        }
    }

    @Test
    void missingSelectorAndNoInject_areRejected() {
        ClassNode mixin = mixin("run()V");
        mixin.methods.getFirst().invisibleAnnotations.getFirst().values.clear();
        assertRejected(target("run()V"), mixin, "缺少注入目标声明");
        assertRejected(target("run()V"), node("test/EmptyMixin"), "没有可核验的 Inject 声明");
    }

    @Test
    void abstractAndNativeTargets_areRejected() {
        for (int access : List.of(Opcodes.ACC_ABSTRACT, Opcodes.ACC_NATIVE)) {
            ClassNode target = target("run()V");
            target.methods.getFirst().access |= access;
            assertRejected(target, mixin("run()V"), "目标方法没有可注入的方法体");
        }
    }

    @Test
    void staticMismatch_isRejected() {
        ClassNode target = target("run()V");
        target.methods.getFirst().access |= Opcodes.ACC_STATIC;
        assertRejected(target, mixin("run()V"), "目标与 handler 的静态性不匹配");
        ClassNode mixin = mixin("run()V");
        mixin.methods.getFirst().access |= Opcodes.ACC_STATIC;
        assertTrue(MixinInjectionSignatureCheck.check(target, mixin).isEmpty());
    }

    @Test
    void runtimeVisibleDeclaration_isAlsoRead() {
        ClassNode mixin = mixin("run()V");
        MethodNode handler = mixin.methods.getFirst();
        handler.visibleAnnotations = handler.invisibleAnnotations;
        handler.invisibleAnnotations = null;
        assertTrue(MixinInjectionSignatureCheck.check(target("run()V"), mixin).isEmpty());
    }

    @Test
    void compiledDeclarations_haveIndependentNativeRequirements() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        List<String> mixins;
        try (InputStream resource = loader.getResourceAsStream("living_item.mixins-sophisticated.json")) {
            assertNotNull(resource);
            var config = JsonParser.parseString(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
            assertFalse(config.get("required").getAsBoolean());
            String prefix = config.get("package").getAsString() + ".";
            mixins = config.getAsJsonArray("mixins").asList().stream()
                .map(name -> prefix + name.getAsString()).toList();
        }
        Set<String> groups = new HashSet<>();
        int handlers = 0;
        for (String name : mixins) {
            ClassNode mixin = MixinInjectionSignatureCheck.readClass(loader, name);
            ClassNode target = node("test/DeclaredTarget");
            for (MethodNode method : mixin.methods) {
                AnnotationNode inject = annotation(method, INJECT);
                if (inject == null) continue;
                handlers++;
                assertEquals(0, value(inject, "require"));
                assertEquals(0, value(inject, "expect"));
                assertEquals(Boolean.FALSE, value(inject, "remap"));
                AnnotationNode group = annotation(method, GROUP);
                assertNotNull(group, method.name);
                assertEquals(1, value(group, "min"));
                String groupName = (String) value(group, "name");
                assertTrue(groupName.startsWith("living_item$"));
                assertTrue(groups.add(groupName), "每个 handler 必须使用独立组: " + method.name);
                assertTrue(value(group, "max") == null || (Integer) value(group, "max") < 0,
                    "RETURN 可多次命中，不设单次上限");
                for (Object selector : (List<?>) value(inject, "method")) {
                    MethodNode declaredTarget = method((String) selector);
                    declaredTarget.access |= method.access & Opcodes.ACC_STATIC;
                    target.methods.add(declaredTarget);
                }
            }
            var failures = MixinInjectionSignatureCheck.check(target, mixin);
            assertTrue(failures.isEmpty(), name + ": " + failures);
        }
        assertEquals(5, handlers);
        assertEquals(5, groups.size());
    }

    @Test
    void nativeGroups_doNotLetMultipleReturnsHideMissingHead() throws Exception {
        InjectorGroupInfo head = new InjectorGroupInfo("head");
        InjectorGroupInfo tail = new InjectorGroupInfo("tail");
        head.setMinRequired(1);
        tail.setMinRequired(1);
        InjectionInfo headInfo = mock(InjectionInfo.class);
        InjectionInfo tailInfo = mock(InjectionInfo.class);
        when(headInfo.getInjectedCallbackCount()).thenReturn(0);
        when(tailInfo.getInjectedCallbackCount()).thenReturn(2);
        head.add(headInfo);
        tail.add(tailInfo);

        assertEquals(tail, tail.validate());
        assertThrows(InjectionValidationException.class, head::validate);
    }

    private static void assertRejected(ClassNode target, ClassNode mixin, String reason) {
        var failures = MixinInjectionSignatureCheck.check(target, mixin);
        assertFalse(failures.isEmpty());
        assertEquals(reason, failures.getFirst().reason());
    }

    static ClassNode node(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        return node;
    }

    static ClassNode target(String selector) {
        ClassNode node = node("test/Target");
        node.methods.add(method(selector));
        return node;
    }

    static ClassNode mixin(String selector) {
        ClassNode node = node("test/Mixin");
        node.methods.add(handler("handler", selector));
        return node;
    }

    static MethodNode method(String selector) {
        int start = selector.indexOf('(');
        return new MethodNode(Opcodes.ACC_PUBLIC, selector.substring(0, start), selector.substring(start), null, null);
    }

    static MethodNode handler(String name, String selector) {
        MethodNode node = new MethodNode(Opcodes.ACC_PRIVATE, name, "()V", null, null);
        AnnotationNode inject = new AnnotationNode(INJECT);
        inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector))));
        node.invisibleAnnotations = new ArrayList<>(List.of(inject));
        return node;
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        for (List<AnnotationNode> annotations : List.of(
                method.visibleAnnotations == null ? List.<AnnotationNode>of() : method.visibleAnnotations,
                method.invisibleAnnotations == null ? List.<AnnotationNode>of() : method.invisibleAnnotations)) {
            for (AnnotationNode annotation : annotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, node.access, node.name, null, "java/lang/Object", null);
        for (MethodNode method : node.methods) {
            var visitor = writer.visitMethod(method.access, method.name, method.desc, null, null);
            if (method.invisibleAnnotations != null) {
                for (AnnotationNode annotation : method.invisibleAnnotations) {
                    annotation.accept(visitor.visitAnnotation(annotation.desc, false));
                }
            }
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                visitor.visitCode();
                visitor.visitInsn(Opcodes.RETURN);
                int locals = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
                for (Type argument : Type.getArgumentTypes(method.desc)) locals += argument.getSize();
                visitor.visitMaxs(0, locals);
            }
            visitor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    static final class ResourceLoader extends ClassLoader {
        final Map<String, byte[]> resources = new HashMap<>();
        final List<String> reads = new ArrayList<>();
        int loads;

        ResourceLoader() {
            super(null);
        }

        void put(String name, byte[] bytes) {
            resources.put(name.replace('.', '/') + ".class", bytes);
        }

        @Override
        public URL getResource(String name) {
            if (!resources.containsKey(name)) return null;
            try {
                return new URL("file:/fixture/" + name);
            } catch (java.net.MalformedURLException impossible) {
                throw new AssertionError(impossible);
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            reads.add(name);
            byte[] bytes = resources.get(name);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) {
            loads++;
            throw new AssertionError("资源核验不得加载类: " + name);
        }
    }
}
