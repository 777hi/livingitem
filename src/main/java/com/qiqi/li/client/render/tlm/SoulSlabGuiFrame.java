package com.qiqi.li.client.render.tlm;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import net.minecraft.world.item.ItemStack;

/** 当前容器帧内允许透明化的真实槽栈；引用和槽位GUI矩阵必须同时匹配。 */
final class SoulSlabGuiFrame {
    static final class Token {
        private boolean active = true;
        boolean active() { return active; }
        void close() { active = false; }
    }

    private static final class Registration {
        private final Token token;
        private final Matrix4f itemPose;
        private boolean claimed;

        private Registration(Token token, Matrix4f itemPose) {
            this.token = token;
            this.itemPose = itemPose;
        }
    }

    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();
    private static final IdentityHashMap<ItemStack, List<Registration>> STACKS = new IdentityHashMap<>();

    private SoulSlabGuiFrame() {}

    static Token begin() {
        clear();
        Token token = new Token();
        CURRENT.set(token);
        return token;
    }

    static void register(ItemStack stack, Token token, PoseStack itemPose) {
        if (stack == null || stack.isEmpty() || itemPose == null || !isCurrent(token)) return;
        STACKS.computeIfAbsent(stack, ignored -> new ArrayList<>())
                .add(new Registration(token, new Matrix4f(itemPose.last().pose())));
    }

    static Token tokenFor(ItemStack stack) {
        if (stack == null) return null;
        Token current = CURRENT.get();
        if (!isCurrent(current)) return null;
        List<Registration> registrations = STACKS.get(stack);
        if (registrations == null) return null;
        for (Registration registration : registrations) {
            if (registration.token == current && !registration.claimed) return current;
        }
        return null;
    }

    static boolean claim(ItemStack stack, Token token, PoseStack actualPose) {
        if (stack == null || !isCurrent(token) || actualPose == null) return false;
        List<Registration> registrations = STACKS.get(stack);
        if (registrations == null) return false;
        for (Registration registration : registrations) {
            if (registration.token == token && !registration.claimed
                    && registration.itemPose.equals(actualPose.last().pose(), 0.0001f)) {
                registration.claimed = true;
                return true;
            }
        }
        return false;
    }

    static boolean takeClaim(ItemStack stack, Token token, PoseStack expectedPose) {
        if (stack == null || !isCurrent(token) || expectedPose == null) return false;
        List<Registration> registrations = STACKS.get(stack);
        if (registrations == null) return false;
        for (int i = 0; i < registrations.size(); i++) {
            Registration registration = registrations.get(i);
            if (registration.token == token && registration.claimed
                    && registration.itemPose.equals(expectedPose.last().pose(), 0.0001f)) {
                registrations.remove(i);
                if (registrations.isEmpty()) STACKS.remove(stack);
                return true;
            }
        }
        return false;
    }

    static boolean isCurrent(Token token) {
        return token != null && token.active() && CURRENT.get() == token;
    }

    static void clear() {
        Token token = CURRENT.get();
        if (token != null) token.close();
        CURRENT.remove();
        STACKS.clear();
    }
}
