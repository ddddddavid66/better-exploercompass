package com.chaosthedude.explorerscompass.unified.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 只取主线程已有的 holder，不经过会等待区块的公开获取入口。 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Invoker("getUpdatingChunkIfPresent")
    ChunkHolder unified$getUpdatingChunkIfPresent(long position);
}
