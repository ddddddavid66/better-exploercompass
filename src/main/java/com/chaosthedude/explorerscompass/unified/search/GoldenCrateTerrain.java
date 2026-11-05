package com.chaosthedude.explorerscompass.unified.search;

import com.david.compass.runtime.game.search.BiomeSourceSafety;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/** 只持有不可变生成上下文；噪声地形不是已生成世界的证明，评分失败一律保留候选。 */
final class GoldenCrateTerrain {
    private final ChunkGenerator generator;
    private final RandomState noise;
    private final LevelHeightAccessor height;
    private final Cache<Long,NoiseColumn> columns=CacheBuilder.newBuilder().maximumSize(4096).concurrencyLevel(1).build();
    private volatile String unavailable="";
    final AtomicLong nanos=new AtomicLong(),evaluated=new AtomicLong(),likely=new AtomicLong();
    GoldenCrateTerrain(ServerLevel level) {
        generator=level.getChunkSource().getGenerator();noise=level.getChunkSource().randomState();
        height=LevelHeightAccessor.create(level.getMinBuildHeight(),level.getHeight());
        var safety=BiomeSourceSafety.prepare(level);
        if(!safety.allowed())unavailable="generation source not audited";
    }
    int score(BlockPos crate) {
        if(!unavailable.isEmpty())return 0;
        long begin=System.nanoTime();
        try {
            evaluated.incrementAndGet();
            int x=crate.getX()-6,y=crate.getY()-1,z=crate.getZ()-6;
            var center=column(x,z);
            int score=(center.getBlock(y+1).isAir()?1:0)+(center.getBlock(y+10).isAir()?1:0)
                    +(center.getBlock(y-1).isSolid()?1:0);
            // 中心条件不成立时仍入低优先级队列，避免为低概率候选计算其余三列。
            if(score<3)return score;
            if(column(x+12,z+12).getBlock(y-2).isSolid())score++;
            if(column(x+12,z).getBlock(y-2).isSolid())score++;
            if(column(x,z+12).getBlock(y-2).isSolid())score++;
            if(score==6)likely.incrementAndGet();
            return score;
        } catch(RuntimeException error) {
            unavailable=error.getClass().getSimpleName()+": "+error.getMessage();return 0;
        } finally {nanos.addAndGet(System.nanoTime()-begin);}
    }
    private NoiseColumn column(int x,int z) throws RuntimeException {
        long key=ChunkPos.asLong(x,z);
        try {return columns.get(key,()->generator.getBaseColumn(x,z,height,noise));}
        catch(java.util.concurrent.ExecutionException error) {throw new IllegalStateException(error.getCause());}
    }
    String reason() {return unavailable;}
    void clear() {columns.invalidateAll();}
}
