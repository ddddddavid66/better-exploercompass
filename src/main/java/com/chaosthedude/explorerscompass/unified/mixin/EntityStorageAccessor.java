package com.chaosthedude.explorerscompass.unified.mixin;

import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityStorage.class)
public interface EntityStorageAccessor {
    @Accessor("level") net.minecraft.server.level.ServerLevel unified$level();
    @Accessor("worker") IOWorker unified$worker();
}
