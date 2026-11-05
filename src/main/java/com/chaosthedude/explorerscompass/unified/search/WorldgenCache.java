package com.chaosthedude.explorerscompass.unified.search;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;

/** 主线程创建不可变生成上下文；生命周期随世界与数据包重载结束。 */
final class WorldgenCache extends BiomeGenerationContext {
    private static final Map<ServerLevel, WorldgenCache> WORLDS = new IdentityHashMap<>();
    private StructurePredictor predictor;
    private final ServerLevel level;
    private record PredictionKey(Structure structure, long chunk) {}
    private static final class Prediction {
        final net.minecraft.core.BlockPos point;
        Prediction(net.minecraft.core.BlockPos point){this.point=point;}
        net.minecraft.core.BlockPos point(){return point;}
    }
    private final com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer<PredictionKey, Prediction> predictions =
            new com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer<>(32_768);
    private final Map<Structure, Predicate<Holder<Biome>>> biomeRules = new IdentityHashMap<>();
    private final Map<Structure, StartPrediction> dungeons = new IdentityHashMap<>();
    private final Map<net.minecraft.world.level.levelgen.structure.placement.StructurePlacement,Boolean> regionPolicies=new IdentityHashMap<>();
    boolean prefixRule(net.minecraft.world.level.levelgen.structure.placement.StructurePlacement placement,ServerLevel level) {
        return regionPolicies.computeIfAbsent(placement,rule->{
            if(rule.getClass()!=net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement.class)return false;
            // 排除依赖可能包含未知模组规则，不能把它的否定结果复用为确定生成事实。
            try {
                return net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.CODEC.encodeStart(
                        net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE,level.registryAccess()),rule)
                        .result().map(json->!json.getAsJsonObject().has("exclusion_zone")).orElse(false);
            }catch(RuntimeException unknown){return false;}
        });
    }
    static final class Prefix {
        final int x,z;final long upper,cost;final java.util.Set<Long> excluded;final List<SearchCandidate> candidates;
        final StructureSearch.Group group;
        Prefix(int x,int z,long upper,long cost,java.util.Set<Long> excluded,List<SearchCandidate> candidates) {
            this(x,z,upper,cost,excluded,candidates,null);
        }
        Prefix(int x,int z,long upper,long cost,java.util.Set<Long> excluded,List<SearchCandidate> candidates,StructureSearch.Group group) {
            this.x=x;this.z=z;this.upper=upper;this.cost=cost;this.excluded=excluded;this.candidates=candidates;this.group=group;
        }
        boolean covers(net.minecraft.core.BlockPos origin,java.util.Set<Long> currentExcluded,long maximum,long quota) {
            return x==origin.getX() && z==origin.getZ() && upper<=maximum && cost<=quota && currentExcluded.containsAll(excluded);
        }
    }
    // 结构键只含注册 ID；群系键含起点和采样参数，两个键空间不会相等。合计上限 256。
    private final com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer<List<?>,Prefix> prefixes=
            new com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer<>(256);
    private static final class PlanKey {
        final List<net.minecraft.resources.ResourceLocation> keys;final boolean compatible;
        PlanKey(List<net.minecraft.resources.ResourceLocation> keys,boolean compatible){this.keys=keys;this.compatible=compatible;}
        // 显式比较避免 record 动态比较器的首次引导落入第一轮热搜索。
        @Override public boolean equals(Object other) {
            return this==other || other instanceof PlanKey key && compatible==key.compatible && keys.equals(key.keys);
        }
        @Override public int hashCode() { return 31*keys.hashCode()+(compatible?1:0); }
    }
    private final com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer<PlanKey,List<StructureSearch.Group>> plans =
            new com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer<>(256);
    // 少量近期位置只作为下次请求的提示；每次使用仍检查真实结构记录。
    private final com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer<Structure, SearchCandidate> hints =
            new com.chaosthedude.explorerscompass.unified.data.BoundedMemoizer<>(256);
    private WorldgenCache(ServerLevel level) {
        super(level);
        this.level=level;
    }
    static WorldgenCache get(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("生成缓存只能由服务端线程创建");
        WorldgenCache known=WORLDS.get(level);
        if(known==null || !known.stamp.current(level) || known.biomeSource!=level.getChunkSource().getGenerator().getBiomeSource()
                || known.noise!=level.getChunkSource().randomState().sampler()
                || known.sourceSafe!=BiomeSourceSafety.prepare(level).allowed()) {
            known=new WorldgenCache(level);WORLDS.put(level,known);
        }
        return known;
    }
    SearchCandidate hint(Structure structure) { return hints.peek(structure); }
    Prefix prefix(List<net.minecraft.resources.ResourceLocation> keys,net.minecraft.core.BlockPos origin,
                  java.util.Set<Long> excluded,long maximum,long quota) {
        Prefix known=prefixes.peek(keys);
        return known!=null && known.covers(origin,excluded,maximum,quota)?known:null;
    }
    Prefix prefix(List<?> keys) {return prefixes.peek(keys);}
    void rememberPrefix(List<?> keys,Prefix prefix) {prefixes.remember(keys,prefix);}
    void rememberHint(SearchCandidate candidate) { hints.remember(candidate.structure(),candidate); }
    List<StructureSearch.Group> structurePlan(List<net.minecraft.resources.ResourceLocation> keys, boolean compatible,
                                            Supplier<List<StructureSearch.Group>> prepare) {
        if(predictor==null)predictor=new StructurePredictor(level);
        // 只缓存注册表分组和预测策略，不能包含玩家位置、历史排除或可变采样游标。
        PlanKey key=new PlanKey(keys,compatible);
        List<StructureSearch.Group> known=plans.peek(key);
        if(known!=null)return known;
        // 计划仅在服务端线程读取／建立，不需要缓存加载 future。
        List<StructureSearch.Group> created=prepare.get();plans.remember(key,created);return created;
    }
    Predicate<Holder<Biome>> biomeRule(Structure structure) {
        return biomeRules.computeIfAbsent(structure, StructurePredictor::biomeRule);
    }
    StartPrediction dungeons(ServerLevel level, net.minecraft.resources.ResourceLocation key, Structure structure) {
        return dungeons.computeIfAbsent(structure, ignored -> StartPrediction.of(level, key, structure));
    }
    net.minecraft.core.BlockPos predict(Structure structure, Predicate<Holder<Biome>> biomes, net.minecraft.world.level.ChunkPos chunk) {
        // 缓存正/负生成预测，绝不缓存真实区块核对；快照失效后旧任务只能写入自己的旧缓存。
        return predictions.get(new PredictionKey(structure, chunk.toLong()), ignored -> {
            try {
                var point = scoped(()->predictor.predict(structure, biomes, chunk));
                return new Prediction(point == null ? null : point.immutable());
            }catch(HeightBiomeFilter.Rejected impossible) {
                // 全高度群系证明同样属于负生成结果；不能让它逃出缓存并在下次重算。
                return new Prediction(null);
            }
        }).point();
    }
    static void clear() { WORLDS.clear(); BiomeGenerationContext.clearSharedState(); }
}
