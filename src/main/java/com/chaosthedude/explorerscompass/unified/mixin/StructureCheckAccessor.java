package com.chaosthedude.explorerscompass.unified.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 已加载的结构记录可以同步查询；未知记录必须交给异步区块准备。 */
@Mixin(StructureCheck.class)
public interface StructureCheckAccessor {
    @Accessor("loadedChunks")
    Long2ObjectMap<Object2IntMap<Structure>> unified$getLoadedChunks();
}
