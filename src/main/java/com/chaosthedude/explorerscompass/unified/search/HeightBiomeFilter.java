package com.chaosthedude.explorerscompass.unified.search;

import java.util.function.Supplier;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;

/** 只针对无命名锚点、恒定高度偏移的原版 Jigsaw；在官方取得真实列后预筛。 */
final class HeightBiomeFilter {
    private static final ThreadLocal<HeightBiomeFilter> CURRENT=new ThreadLocal<>();
    static final class Metrics {
        final java.util.concurrent.atomic.LongAdder skipped=new java.util.concurrent.atomic.LongAdder(), nanos=new java.util.concurrent.atomic.LongAdder();
    }
    private static final ThreadLocal<Metrics> METRICS=new ThreadLocal<>();
    static <T> T measure(Metrics metrics,Supplier<T> action) {
        Metrics old=METRICS.get();METRICS.set(metrics);
        try{return action.get();}finally{if(old==null)METRICS.remove();else METRICS.set(old);}
    }
    private static final Rejected REJECTED=new Rejected();
    static final class Rejected extends RuntimeException {
        private Rejected(){super(null,null,false,false);}
    }
    private final WorldgenCache cache;
    private final java.util.function.Predicate<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> allowed;
    private final int minY,maxY;
    private final java.util.concurrent.atomic.LongAdder skipped=new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder nanos=new java.util.concurrent.atomic.LongAdder();
    HeightBiomeFilter(ServerLevel level,Structure structure,int offset) {
        if(level.getChunkSource().getGenerator().getClass()!=NoiseBasedChunkGenerator.class)throw new IllegalArgumentException("未知高度边界");
        cache=WorldgenCache.get(level);allowed=StructurePredictor.biomeRule(structure);
        minY=QuartPos.fromBlock(level.getMinBuildHeight()+offset);
        maxY=QuartPos.fromBlock(level.getMaxBuildHeight()+offset);
    }
    static void beforeHeight(int x,int z) {
        HeightBiomeFilter filter=CURRENT.get();if(filter==null)return;
        long before=System.nanoTime();boolean possible=false;
        try {
            // 不推断整列相同；遍历官方返回高度可能覆盖的每个 quart-Y。
            int qx=QuartPos.fromBlock(x),qz=QuartPos.fromBlock(z);
            possible=filter.cache.inColumn(qx,qz,()-> {
                int sea=Math.max(filter.minY,Math.min(filter.maxY,16));
                if(filter.cache.allowedBiome(qx,sea,qz,filter.allowed))return true;
                for(int y=filter.minY;y<=filter.maxY;y++)
                    if(y!=sea && filter.cache.allowedBiome(qx,y,qz,filter.allowed))return true;
                return false;
            });
        }finally{long used=System.nanoTime()-before;filter.nanos.add(used);if(METRICS.get()!=null)METRICS.get().nanos.add(used);}
        if(!possible){filter.skipped.increment();if(METRICS.get()!=null)METRICS.get().skipped.increment();throw REJECTED;}
    }
    <T> T with(Supplier<T> action) {
        HeightBiomeFilter old=CURRENT.get();CURRENT.set(this);
        try{return action.get();}catch(Rejected rejected){return null;}
        finally{if(old==null)CURRENT.remove();else CURRENT.set(old);}
    }
    long skipped(){return skipped.sum();} long nanos(){return nanos.sum();}
}
