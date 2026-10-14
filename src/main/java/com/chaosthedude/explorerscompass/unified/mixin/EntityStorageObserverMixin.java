package com.chaosthedude.explorerscompass.unified.mixin;

import com.chaosthedude.explorerscompass.unified.creature.search.SavedCreatureIndex;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityStorage.class)
public abstract class EntityStorageObserverMixin {
    // 原版先提交保存，再使索引失效；后续扫描与该保存共用同一 I/O 队列。
    @Inject(method="storeEntities",at=@At("RETURN"))
    private void unified$stored(ChunkEntities<Entity> chunk,CallbackInfo ci) {
        SavedCreatureIndex.stored(((EntityStorageAccessor)(Object)this).unified$level(),chunk.getPos());
    }
}
