package com.chaosthedude.explorerscompass.unified.search;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.PerlinSimplexNoise;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

/** 可选 2.0.2 适配。官方 Voronoi 对象按分片独占；不在后台调用被注入的群系来源。 */
final class AlexCavesAdapter {
    static final Set<String> CAVES=Set.of("magnetic_caves","primordial_caves","toxic_caves","abyssal_chasm","forlorn_hollows","candy_cavity");
    static boolean target(ResourceLocation id){return id!=null && id.getNamespace().equals("alexscaves") && CAVES.contains(id.getPath());}
    private final long seed;
    private final double separation,size,widthRandom,spacingRandom;
    private final int biomeCount,rarity,spawnDistance;
    private final boolean enabled;
    private final JsonObject condition;
    private final Climate.Sampler climate;
    private final Method get2,distance,hash,cellPos,setOffset;
    private final java.lang.reflect.Constructor<?> constructor;
    private final ThreadLocal<Worker> workers=ThreadLocal.withInitial(this::worker);
    private record Worker(Object voronoi,PerlinSimplexNoise x,PerlinSimplexNoise z) {}
    private AlexCavesAdapter(ServerLevel level,ResourceLocation id) throws ReflectiveOperationException {
        if(!ModList.get().getModContainerById("alexscaves").map(mod->mod.getModInfo().getVersion().toString().equals("2.0.2")).orElse(false))
            throw new IllegalStateException("未审核的 Alex 洞穴版本");
        var generator=level.getChunkSource().getGenerator();
        if(generator.getClass()!=NoiseBasedChunkGenerator.class || generator.getBiomeSource().getClass()!=MultiNoiseBiomeSource.class)
            throw new IllegalStateException("Alex 专用预测暂不审核此生成器或包装来源");
        // 常规气候表中直接注册目标时，目标不一定经过稀有域注入，不能用稀有域作否定剪枝。
        var source=(MultiNoiseBiomeSource)generator.getBiomeSource();
        for(var pair:((com.chaosthedude.explorerscompass.unified.mixin.ClimateParametersAccessor)source).unified$parameters().values())if(pair.getSecond().unwrapKey().map(key->target(key.location())).orElse(false))
            throw new IllegalStateException("Alex 群系直接进入气候表，需要完整来源兼容查询");
        Object config=Class.forName("com.github.alexmodguy.alexscaves.AlexsCaves").getField("COMMON_CONFIG").get(null);
        size=number(config,"caveBiomeMeanWidth")*.25;
        separation=size+number(config,"caveBiomeMeanSeparation")*.25;
        widthRandom=number(config,"caveBiomeWidthRandomness");spacingRandom=number(config,"caveBiomeSpacingRandomness");
        if(!Double.isFinite(separation) || separation<=0)throw new IllegalStateException("无效洞穴间距");
        var conditions=(Map<?,?>)Class.forName("com.github.alexmodguy.alexscaves.server.config.BiomeGenerationConfig").getField("BIOMES").get(null);
        Object selected=null;
        for(var entry:conditions.entrySet())if(((ResourceKey<?>)entry.getKey()).location().equals(id))selected=entry.getValue();
        if(selected==null)throw new IllegalStateException("洞穴生成配置尚未初始化");
        condition=new Gson().toJsonTree(selected).getAsJsonObject(); // 主线程深拷贝私有范围与维度列表。
        biomeCount=conditions.size();rarity=condition.get("alexscavesRarityOffset").getAsInt();
        spawnDistance=condition.get("distanceFromSpawn").getAsInt();
        boolean dimension=false;
        for(var value:condition.getAsJsonArray("dimensions"))if(value.getAsString().equals(level.dimension().location().toString()))dimension=true;
        enabled=dimension && !condition.get("disabledCompletely").getAsBoolean();
        seed=level.getSeed();climate=level.getChunkSource().randomState().sampler();
        Class<?> voronoi=Class.forName("com.github.alexmodguy.alexscaves.server.misc.VoronoiGenerator");
        constructor=voronoi.getConstructor(long.class);get2=voronoi.getMethod("get2",double.class,double.class);
        setOffset=voronoi.getMethod("setOffsetAmount",double.class);
        Class<?> info=get2.getReturnType();distance=info.getMethod("distance");hash=info.getMethod("hash");cellPos=info.getMethod("cellPos");
    }
    static AlexCavesAdapter prepare(ServerLevel level,ResourceLocation id) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("主线程准备 Alex 生成快照");
        try{return new AlexCavesAdapter(level,id);}
        catch(ReflectiveOperationException|RuntimeException error) {
            org.apache.logging.log4j.LogManager.getLogger("compass_runtime").warn("{} 使用四格高度兼容查询: {}",id,error.toString());return null;
        }
    }
    private static double number(Object owner,String name) throws ReflectiveOperationException {
        Object value=owner.getClass().getField(name).get(owner);
        return ((Number)value.getClass().getMethod("get").invoke(value)).doubleValue();
    }
    private Worker worker() {
        try {
            Object voronoi=constructor.newInstance(seed);setOffset.invoke(voronoi,spacingRandom);
            return new Worker(voronoi,new PerlinSimplexNoise(new XoroshiroRandomSource(1234),List.of(0)),
                    new PerlinSimplexNoise(new XoroshiroRandomSource(4321),List.of(0)));
        }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    Vec3 center(int x,int z) {
        if(!enabled)return null;
        // 保留 2.0.2 官方 int 乘法语义，包括远坐标溢出；不悄悄改变生成条件。
        int bx=QuartPos.fromSection(x),bz=QuartPos.toBlock(z);
        if(bx*bx+bz*bz<(double)spawnDistance*spawnDistance)return null;
        Worker worker=workers.get();double u=x/separation,v=z/separation;
        try {
            Object info=get2.invoke(worker.voronoi,u+widthRandom*worker.x.getValue(u,v,false),v+widthRandom*worker.z.getValue(u,v,false));
            if(((Number)distance.invoke(info)).doubleValue()>=size/separation)return null;
            int kind=(int)((((Number)hash.invoke(info)).doubleValue()+1)*.5*biomeCount);
            return kind==rarity?((Vec3)cellPos.invoke(info)).scale(separation):null;
        }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    boolean matches(int x,int y,int z,Vec3 center) {
        var atCenter=climate.sample((int)Math.floor(center.x),y,(int)Math.floor(center.z));
        return range("continentalness",atCenter.continentalness()) && range("erosion",atCenter.erosion())
                // 字段交叉使用是 2.0.2 官方规则，不在定位器中修正它。
                && range("humidity",atCenter.temperature()) && range("temperature",atCenter.humidity())
                && range("weirdness",atCenter.weirdness()) && range("depth",climate.sample(x,y,z).depth());
    }
    private boolean range(String name,long quantized) {
        if(!condition.has(name) || condition.get(name).isJsonNull())return true;
        var array=condition.getAsJsonArray(name);if(array.size()<2)return true;
        float value=Climate.unquantizeCoord(quantized);
        return value>=array.get(0).getAsFloat() && value<=array.get(1).getAsFloat();
    }
}
