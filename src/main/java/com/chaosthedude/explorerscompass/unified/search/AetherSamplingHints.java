package com.chaosthedude.explorerscompass.unified.search;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;

/** 主线程读取实际生效的参数/密度函数；这里只产生顺序提示，不产生否定生成证明。 */
final class AetherSamplingHints {
    record Hint(OptionalInt height,String evidence) {}
    private AetherSamplingHints() {}

    static Hint prepare(ServerLevel level,ResourceLocation target,int initialY) {
        try {
            var source=level.getChunkSource().getGenerator().getBiomeSource();
            if(!(source instanceof MultiNoiseBiomeSource multi))return unknown("non_multi_noise_source");
            var ops=RegistryOps.create(JsonOps.INSTANCE,level.registryAccess());
            var depth=DensityFunction.HOLDER_HELPER_CODEC.encodeStart(ops,level.getChunkSource().randomState().sampler().depth())
                    .result().orElseThrow();
            depth=resolve(level,depth,new java.util.HashSet<>());
            if(!depth.isJsonObject() || !depth.getAsJsonObject().get("type").getAsString().equals("minecraft:y_clamped_gradient"))
                return unknown("depth_not_audited_linear_gradient");
            var gradient=depth.getAsJsonObject();double from=gradient.get("from_value").getAsDouble(),to=gradient.get("to_value").getAsDouble();
            int fromY=gradient.get("from_y").getAsInt(),toY=gradient.get("to_y").getAsInt();
            if(from==to || fromY>=toY || !Double.isFinite(from+to))return unknown("invalid_depth_gradient");
            var linear=new BiomeSamplingProfile.LinearDepth(fromY,toY,from,to);
            var parameters=((com.chaosthedude.explorerscompass.unified.mixin.ClimateParametersAccessor)(Object)multi).unified$parameters();
            List<Climate.Parameter> ranges=new ArrayList<>();
            for(var pair:parameters.values())if(matches(pair.getSecond(),target))ranges.add(pair.getFirst().depth());
            // TerraBlender额外region的真实树不在values()中；已初始化后读取树叶，不重新执行region注册代码。
            try {
                Class<?> api=Class.forName("terrablender.worldgen.IExtendedParameterList");
                if(api.isInstance(parameters) && Boolean.TRUE.equals(api.getMethod("isInitialized").invoke(parameters))) {
                    int count=(int)api.getMethod("getTreeCount").invoke(parameters);
                    for(int i=0;i<count;i++) {
                        Object tree=api.getMethod("getTree",int.class).invoke(parameters,i);
                        if(tree!=null)readTree(tree,target,ranges);
                    }
                }
            }catch(ClassNotFoundException ignored) { }
            int best=0;long distance=Long.MAX_VALUE;
            for(var range:ranges) {
                var preferred=linear.preferred(Climate.unquantizeCoord(range.min()),Climate.unquantizeCoord(range.max()),
                        level.getMinBuildHeight(),level.getMaxBuildHeight());
                if(preferred.isEmpty())continue;
                int height=preferred.getAsInt();
                long gap=Math.abs((long)height-initialY);
                if(gap<distance){distance=gap;best=height;}
            }
            return distance==Long.MAX_VALUE?unknown("target_parameter_not_accessible"):
                    new Hint(OptionalInt.of(best),"runtime_depth_gradient_and_target_parameters");
        }catch(ReflectiveOperationException | RuntimeException unknown) {
            return unknown("priority_unavailable_"+unknown.getClass().getSimpleName());
        }
    }

    private static Hint unknown(String reason){return new Hint(OptionalInt.empty(),reason);}
    private static boolean matches(Holder<Biome> holder,ResourceLocation target) {
        return holder.unwrapKey().map(key->key.location().equals(target)).orElse(false);
    }
    private static JsonElement resolve(ServerLevel level,JsonElement value,Set<String> seen) {
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())return value;
        String id=value.getAsString();if(!seen.add(id) || seen.size()>32)throw new IllegalArgumentException("cyclic density function");
        var function=level.registryAccess().registryOrThrow(Registries.DENSITY_FUNCTION).get(new ResourceLocation(id));
        if(function==null)throw new IllegalArgumentException("unknown density function");
        return resolve(level,DensityFunction.DIRECT_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,level.registryAccess()),function)
                .result().orElseThrow(),seen);
    }
    private static void readTree(Object tree,ResourceLocation target,List<Climate.Parameter> ranges)throws ReflectiveOperationException {
        for(Field field:tree.getClass().getDeclaredFields())if(field.getType().getName().endsWith("Climate$RTree$Node")) {
            if(!field.trySetAccessible())return;
            walk(field.get(tree),target,ranges,Collections.newSetFromMap(new IdentityHashMap<>()));return;
        }
    }
    private static void walk(Object node,ResourceLocation target,List<Climate.Parameter> ranges,Set<Object> seen)throws ReflectiveOperationException {
        if(node==null || !seen.add(node))return;
        Object value=null;Object[] children=null;
        for(Field field:node.getClass().getDeclaredFields()) {
            if(java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible())continue;
            if(field.getType()==Object.class)value=field.get(node);
            if(field.getType().isArray() && field.getType().getComponentType().getName().endsWith("Climate$RTree$Node"))children=(Object[])field.get(node);
        }
        if(value instanceof Holder<?> holder && holder.unwrapKey().map(key->key.location().equals(target)).orElse(false)) {
            for(Class<?> type=node.getClass();type!=null;type=type.getSuperclass())for(Field field:type.getDeclaredFields())
                if(field.getType().isArray() && field.getType().getComponentType()==Climate.Parameter.class && field.trySetAccessible()) {
                    var bounds=(Climate.Parameter[])field.get(node);if(bounds.length>4)ranges.add(bounds[4]);return;
                }
        }
        if(children!=null)for(Object child:children)walk(child,target,ranges,seen);
    }
}
