package com.chaosthedude.explorerscompass.unified.search;

import net.minecraft.server.level.ServerLevel;

/** 只比较身份：模组生成器可能自定义 equals，不能据此复用旧生成快照。 */
final class GenerationStamp {
    private static long epoch;
    private final Object generator, source, random, sampler, registries;
    private final long generation;
    GenerationStamp(Object generator,Object source,Object random,Object sampler,Object registries,long generation) {
        this.generator=generator;this.source=source;this.random=random;this.sampler=sampler;
        this.registries=registries;this.generation=generation;
    }
    static GenerationStamp capture(ServerLevel level) {
        var chunks=level.getChunkSource();var generator=chunks.getGenerator();var random=chunks.randomState();
        return new GenerationStamp(generator,generator.getBiomeSource(),random,random.sampler(),level.registryAccess(),epoch);
    }
    boolean same(GenerationStamp other) {
        return other!=null && generator==other.generator && source==other.source && random==other.random
            && sampler==other.sampler && registries==other.registries && generation==other.generation;
    }
    boolean current(ServerLevel level){
        var chunks=level.getChunkSource();var state=chunks.randomState();
        return generation==epoch && generator==chunks.getGenerator() && source==chunks.getGenerator().getBiomeSource()
                && random==state && sampler==state.sampler() && registries==level.registryAccess();
    }
    static void invalidate(){epoch++;}
}
