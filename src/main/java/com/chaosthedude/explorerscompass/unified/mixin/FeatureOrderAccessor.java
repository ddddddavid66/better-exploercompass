package com.chaosthedude.explorerscompass.unified.mixin;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读取生成器真正使用的顺序，不能用注册表顺序代替装饰随机种子的索引。 */
@Mixin(ChunkGenerator.class)
public interface FeatureOrderAccessor {
    @Accessor("featuresPerStep")
    Supplier<List<FeatureSorter.StepFeatureData>> unified$featuresPerStep();
}
