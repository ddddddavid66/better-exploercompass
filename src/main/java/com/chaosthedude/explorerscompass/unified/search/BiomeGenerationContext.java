package com.chaosthedude.explorerscompass.unified.search;

import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/** 群系专用生成上下文；不持有结构预测器、结构计划或结构类型。
 * 迁移期间由旧 WorldgenCache 继承，调用方采样与缓存语义保持不变。
 */
class BiomeGenerationContext {
    final boolean sourceSafe;
    final GenerationStamp stamp;
    private final com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer<Long, Biome> biomePredictions =
            new com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer<>(32_768);
    final net.minecraft.world.level.biome.BiomeSource biomeSource;
    final net.minecraft.world.level.biome.Climate.Sampler noise;
    private final ColumnClimate columns;
    private final AlexBiomeScope.Context alex;
    BiomeGenerationContext(ServerLevel level) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("群系生成上下文只能由服务端线程创建");
        stamp=GenerationStamp.capture(level);
        alex=BiomeSourceSafety.prepare(level).allowed()?AlexBiomeScope.prepare(level.getSeed()):null;
        biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        noise = level.getChunkSource().randomState().sampler();
        columns=ColumnClimate.prepare(level,noise);
        sourceSafe=BiomeSourceSafety.prepare(level).allowed();
    }
    Biome predictBiome(int x, int y, int z) {
        // Quart 坐标和真实采样器严格隔离；只缓存审核过的不可变生成查询，仍逐点检查。
        return predictBiome(x,y,z,null);
    }
    private Biome predictBiome(int x,int y,int z,Runnable onQuery) {
        long key=net.minecraft.core.BlockPos.asLong(x,y,z);
        return biomePredictions.get(key,ignored->streamBiome(x,y,z,onQuery));
    }
    final class BiomeColumn {
        private final int x,z;
        private final ColumnClimate.Column climate;
        private BiomeColumn(int x,int z){this.x=x;this.z=z;climate=columns==null?null:columns.column(x,z);}
        Biome predict(int y){return predict(y,null);}
        Biome predict(int y,Runnable onQuery){return climate==null?predictBiome(x,y,z,onQuery):columns.with(climate,()->predictBiome(x,y,z,onQuery));}
    }
    BiomeColumn biomeColumn(int x,int z){return new BiomeColumn(x,z);}
    /** 流式扫描不污染高复用LRU；调用方独占列游标，来源作用域仍完整保留。 */
    Biome streamBiome(int x,int y,int z){return streamBiome(x,y,z,null);}
    private Biome streamBiome(int x,int y,int z,Runnable onQuery){return scoped(()->{
        if(onQuery!=null)onQuery.run();
        return biomeSource.getNoiseBiome(x,y,z,noise).value();
    });}
    final class StreamingColumn {
        private final int x,z;
        private final ColumnClimate.Column climate;
        private final Runnable onQuery;
        StreamingColumn(int x,int z,Runnable onQuery){this.x=x;this.z=z;this.onQuery=onQuery;climate=columns==null?null:columns.column(x,z);}
        Biome predict(int y){return climate==null?streamBiome(x,y,z,onQuery):columns.with(climate,()->streamBiome(x,y,z,onQuery));}
    }
    StreamingColumn streamingColumn(int x,int z){return streamingColumn(x,z,null);}
    StreamingColumn streamingColumn(int x,int z,Runnable onQuery){return new StreamingColumn(x,z,onQuery);}
    <T> T inColumn(int x,int z,java.util.function.Supplier<T> action) {return columns==null?action.get():columns.with(x,z,action);}
    boolean allowedBiome(int x,int y,int z,Predicate<Holder<Biome>> allowed) {
        long key=net.minecraft.core.BlockPos.asLong(x,y,z);
        Biome biome=predictBiome(x,y,z);
        return allowed.test(net.minecraft.core.Holder.direct(biome));
    }
    Biome peekBiome(int x,int y,int z) {
        return biomePredictions.peek(net.minecraft.core.BlockPos.asLong(x,y,z));
    }
    final <T> T scoped(Supplier<T> action){return alex==null?action.get():alex.with(action);}
    static void clearSharedState() {
        GenerationStamp.invalidate();
        AetherSamplingHints.clear();
        BiomeSourceSafety.clear();
        AlexBiomeScope.clear();
    }
}
