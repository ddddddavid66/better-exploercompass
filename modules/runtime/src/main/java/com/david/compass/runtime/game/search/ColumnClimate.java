package com.david.compass.runtime.game.search;

import com.david.compass.runtime.game.mixin.DensityAddAccessor;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/** 对五个气候密度函数证明与 Y 无关后，一列只算一次；深度仍逐 quart-Y 精确计算。 */
public final class ColumnClimate {
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private record Scope(Climate.Sampler sampler,int x,int z,float temperature,float humidity,float continentalness,float erosion,float weirdness,DensityFunction depth,double fixedDepth,boolean fixedFirst) {}
    private final Climate.Sampler original;
    private DensityFunction fixedDepth,variableDepth;
    private boolean fixedFirst;
    private ColumnClimate(Climate.Sampler original){this.original=original;}
    static ColumnClimate prepare(ServerLevel level,Climate.Sampler sampler) {
        try {
            var ops=RegistryOps.create(JsonOps.INSTANCE,level.registryAccess());
            for(var function:java.util.List.of(sampler.temperature(),sampler.humidity(),sampler.continentalness(),sampler.erosion(),sampler.weirdness())) {
                var json=DensityFunction.HOLDER_HELPER_CODEC.encodeStart(ops,function).result().orElseThrow();
                if(!independent(level,json,new HashSet<>()))return null;
            }
            var result=new ColumnClimate(sampler);
            DensityFunction depth=sampler.depth();
            for(int i=0;i<32;i++) {
                if(depth instanceof DensityFunctions.HolderHolder holder){depth=holder.function().value();continue;}
                if(depth instanceof DensityFunctions.MarkerOrMarked marker){depth=marker.wrapped();continue;}
                break;
            }
            var json=DensityFunction.DIRECT_CODEC.encodeStart(ops,depth).result().orElseThrow();
            if(json.isJsonObject() && json.getAsJsonObject().get("type").getAsString().equals("minecraft:add")
                    && depth instanceof com.david.compass.runtime.game.mixin.DensityAddAccessor pair) {
                var first=pair.unified$first();var second=pair.unified$second();
                if(independent(level,DensityFunction.HOLDER_HELPER_CODEC.encodeStart(ops,first).result().orElseThrow(),new HashSet<>())) {
                    result.fixedDepth=first;result.variableDepth=second;result.fixedFirst=true;
                }else if(independent(level,DensityFunction.HOLDER_HELPER_CODEC.encodeStart(ops,second).result().orElseThrow(),new HashSet<>())) {
                    result.fixedDepth=second;result.variableDepth=first;
                }
            }
            return result;
        }catch(RuntimeException unknown){return null;}
    }
    private static boolean independent(ServerLevel level,JsonElement value,Set<String> path) {
        if(value.isJsonPrimitive()) {
            if(value.getAsJsonPrimitive().isNumber())return true;
            String id=value.getAsString();if(path.size()>=64 || !path.add(id))return false;
            try {
                var function=level.registryAccess().registryOrThrow(Registries.DENSITY_FUNCTION).get(new ResourceLocation(id));
                if(function==null)return false;
                var json=DensityFunction.DIRECT_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,level.registryAccess()),function).result().orElseThrow();
                return independent(level,json,path);
            }finally{path.remove(id);}
        }
        if(!value.isJsonObject())return false;
        var json=value.getAsJsonObject();if(!json.has("type"))return false;
        String type=json.get("type").getAsString();
        if(type.equals("minecraft:shift_a") || type.equals("minecraft:shift_b"))return true;
        // 这里只使用 SinglePointContext：其 Blender 恒为 empty，两个混合项恒为 1 / 0。
        if(type.equals("minecraft:blend_alpha") || type.equals("minecraft:blend_offset"))return true;
        if(type.equals("minecraft:noise"))return json.get("y_scale").getAsDouble()==0;
        if(type.equals("minecraft:shifted_noise"))return json.get("y_scale").getAsDouble()==0
                && independent(level,json.get("shift_x"),path) && independent(level,json.get("shift_y"),path) && independent(level,json.get("shift_z"),path);
        if(Set.of("minecraft:add","minecraft:mul","minecraft:min","minecraft:max").contains(type))
            return independent(level,json.get("argument1"),path) && independent(level,json.get("argument2"),path);
        if(Set.of("minecraft:cache_2d","minecraft:cache_once","minecraft:flat_cache","minecraft:interpolated",
                "minecraft:abs","minecraft:square","minecraft:cube","minecraft:half_negative","minecraft:quarter_negative","minecraft:squeeze").contains(type))
            return independent(level,json.get("argument"),path);
        if(type.equals("minecraft:spline"))return independentSpline(level,json.get("spline"),path);
        if(type.equals("minecraft:clamp"))return independent(level,json.get("input"),path);
        return false;
    }
    private static boolean independentSpline(ServerLevel level,JsonElement value,Set<String> path) {
        if(value.isJsonPrimitive())return value.getAsJsonPrimitive().isNumber();
        if(!value.isJsonObject())return false;
        var spline=value.getAsJsonObject();
        if(!independent(level,spline.get("coordinate"),path))return false;
        for(var point:spline.getAsJsonArray("points"))if(!independentSpline(level,point.getAsJsonObject().get("value"),path))return false;
        return true;
    }
    <T> T with(int quartX,int quartZ,java.util.function.Supplier<T> action) {
        return with(column(quartX,quartZ),action);
    }
    /** 同列多个quart-Y复用五项固定气候；Scope不可变，具体绑定仍为线程局部。 */
    static final class Column {
        private final Scope scope;
        private Column(Scope scope){this.scope=scope;}
    }
    Column column(int quartX,int quartZ) {
        var point=new DensityFunction.SinglePointContext(QuartPos.toBlock(quartX),0,QuartPos.toBlock(quartZ));
        return new Column(new Scope(original,quartX,quartZ,(float)original.temperature().compute(point),
                (float)original.humidity().compute(point),(float)original.continentalness().compute(point),
                (float)original.erosion().compute(point),(float)original.weirdness().compute(point),variableDepth,
                fixedDepth==null?0:fixedDepth.compute(point),fixedFirst));
    }
    <T> T with(Column column,java.util.function.Supplier<T> action) {
        Scope old=CURRENT.get();
        CURRENT.set(column.scope);
        try{return action.get();}finally{if(old==null)CURRENT.remove();else CURRENT.set(old);}
    }
    public static Climate.TargetPoint sample(Climate.Sampler sampler,int x,int y,int z) {
        Scope scope=CURRENT.get();if(scope==null || scope.sampler!=sampler || scope.x!=x || scope.z!=z)return null;
        var point=new DensityFunction.SinglePointContext(QuartPos.toBlock(x),QuartPos.toBlock(y),QuartPos.toBlock(z));
        double variable=(scope.depth==null?sampler.depth():scope.depth).compute(point);
        float depth=(float)(scope.depth==null?variable:scope.fixedFirst?scope.fixedDepth+variable:variable+scope.fixedDepth);
        return Climate.target(scope.temperature,scope.humidity,scope.continentalness,scope.erosion,depth,scope.weirdness);
    }
}
