package com.qiqi.li.living.compat.sophisticated;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** 只读 class 资源核验注入目标签名；不加载第三方类，不证明注入位置或运行行为。 */
final class MixinInjectionSignatureCheck {
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";

    private MixinInjectionSignatureCheck() {}

    static ClassNode readClass(ClassLoader loader, String className) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(className.replace('.', '/') + ".class")) {
            if (stream == null) throw new IOException("class 资源缺失: " + className);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!className.replace('.', '/').equals(node.name)) {
                throw new IOException("class 资源身份不符: " + className + " -> " + node.name);
            }
            return node;
        }
    }

    record Failure(String handler, String selector, String reason) {}

    static List<Failure> check(ClassNode target, ClassNode mixin) {
        List<Failure> failures = new ArrayList<>();
        int handlers = 0;
        for (MethodNode handler : mixin.methods) {
            AnnotationNode inject = annotation(handler, INJECT);
            if (inject == null) continue;
            handlers++;
            Object declared = value(inject, "method");
            if (!(declared instanceof List<?> selectors) || selectors.isEmpty()) {
                failures.add(new Failure(handler.name, "", "缺少注入目标声明"));
                continue;
            }
            for (Object selector : selectors) {
                if (!(selector instanceof String signature) || !isExactSelector(signature)) {
                    failures.add(new Failure(handler.name, String.valueOf(selector), "必须声明完整方法签名"));
                    continue;
                }
                int descriptorStart = signature.indexOf('(');
                String name = signature.substring(0, descriptorStart);
                String descriptor = signature.substring(descriptorStart);
                MethodNode method = target.methods.stream()
                    .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(descriptor))
                    .findFirst().orElse(null);
                if (method == null) {
                    failures.add(new Failure(handler.name, signature, "目标方法或签名不匹配"));
                } else if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    failures.add(new Failure(handler.name, signature, "目标方法没有可注入的方法体"));
                } else if ((method.access & Opcodes.ACC_STATIC) != (handler.access & Opcodes.ACC_STATIC)) {
                    failures.add(new Failure(handler.name, signature, "目标与 handler 的静态性不匹配"));
                }
            }
        }
        if (handlers == 0) failures.add(new Failure("", "", "没有可核验的 Inject 声明"));
        return List.copyOf(failures);
    }

    private static boolean isExactSelector(String selector) {
        int start = selector.indexOf('(');
        if (start <= 0 || !selector.substring(0, start).matches("[A-Za-z_$][A-Za-z0-9_$]*")) return false;
        String descriptor = selector.substring(start);
        try {
            Type[] arguments = Type.getArgumentTypes(descriptor);
            Type result = Type.getReturnType(descriptor);
            for (Type argument : arguments) {
                if (argument.getSort() == Type.VOID || argument.getSort() == Type.METHOD) return false;
            }
            return descriptor.equals(Type.getMethodDescriptor(result, arguments));
        } catch (RuntimeException malformed) {
            return false;
        }
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
}
