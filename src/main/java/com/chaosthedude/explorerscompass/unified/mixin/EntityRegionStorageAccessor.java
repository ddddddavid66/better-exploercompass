package com.chaosthedude.explorerscompass.unified.mixin;

import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.*;

@Mixin(RegionFileStorage.class)
public interface EntityRegionStorageAccessor {
    @Accessor("folder") Path unified$folder();
    @Invoker("getRegionFile") RegionFile unified$getRegionFile(ChunkPos pos) throws IOException;
}
