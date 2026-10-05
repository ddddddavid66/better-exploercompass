package com.chaosthedude.explorerscompass.unified.mixin;

import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 某个模组通过全局变量偷读世界时也拒绝访问，使异常可以触发同步降级。 */
@Mixin(value = ServerChunkCache.class, remap = false)
public abstract class BackgroundChunkGuardMixin {
    @Inject(method = {"getChunk", "m_7587_", "getChunkNow", "m_7131_", "getChunkForLighting", "m_6196_",
            "getChunkFuture", "m_8431_"}, at = @At("HEAD"))
    private void filters$preventBackgroundChunkReads(CallbackInfoReturnable<?> callback) {
        if (Thread.currentThread().getName().startsWith("compassfilters-search-"))
            throw new IllegalStateException("Compass background prediction cannot access world chunks");
    }
}
