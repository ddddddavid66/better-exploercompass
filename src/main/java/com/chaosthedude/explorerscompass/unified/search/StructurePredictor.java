package com.chaosthedude.explorerscompass.unified.search;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** 构造在主线程完成。后台对象不保留 ServerLevel，只提供高度边界。 */
final class StructurePredictor {
    private final RegistryAccess registries;
    private final ChunkGenerator generator;
    private final BiomeSource biomes;
    private final RandomState noise;
    private final StructureTemplateManager templates;
    private final LevelHeightAccessor height;
    private final long seed;
    private final PredictionHeights heights;
    StructurePredictor(ServerLevel level) {
        registries = level.registryAccess(); generator = level.getChunkSource().getGenerator();
        biomes = generator.getBiomeSource(); noise = level.getChunkSource().randomState();
        templates = level.getServer().getStructureManager(); seed = level.getSeed();
        height = LevelHeightAccessor.create(level.getMinBuildHeight(), level.getHeight());
        heights = new PredictionHeights(generator, noise, height);
    }
    static Predicate<Holder<Biome>> biomeRule(Structure structure) {
        Set<Biome> allowed = Collections.newSetFromMap(new IdentityHashMap<>());
        structure.biomes().forEach(holder -> allowed.add(holder.value()));
        return holder -> allowed.contains(holder.value());
    }
    BlockPos predict(Structure structure, Predicate<Holder<Biome>> allowed, ChunkPos chunk) {
        Structure.GenerationContext context = new Structure.GenerationContext(registries, generator,
                biomes, noise, templates, seed, chunk, height, allowed);
        // 不构建完整结构或放置方块；只求合法生成起点，包含群系校验。
        return heights.with(() -> LeviathanPrecheck.validate(structure.findValidGenerationPoint(context)
                .map(Structure.GenerationStub::position).orElse(null)));
    }
}
