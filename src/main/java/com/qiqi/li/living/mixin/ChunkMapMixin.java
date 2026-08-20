package com.qiqi.li.living.mixin;

import com.qiqi.li.living.domain.map.TeleportHelper;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public class ChunkMapMixin {

    @Inject(method = "getPlayerViewDistance", at = @At("HEAD"), cancellable = true)
    private void onGetPlayerViewDistance(ServerPlayer player, CallbackInfoReturnable<Integer> cir) {
        Integer reduced = TeleportHelper.getReducedViewDistance(player);
        if (reduced != null) {
            cir.setReturnValue(reduced);
        }
    }
}