package com.chaosthedude.explorerscompass.unified.search;

import java.lang.reflect.Modifier;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;

/** 运行时诊断只提供可核实证据；有限采样和参数存在都不能证明可达或不存在。 */
public final class BiomeAvailability {
    private BiomeAvailability() {}
    public static Map<ResourceLocation,String> exclusions(ServerLevel level,Collection<ResourceLocation> targets) {
        var possible=new HashSet<ResourceLocation>();
        level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().forEach(h->h.unwrapKey().ifPresent(k->possible.add(k.location())));
        var result=new LinkedHashMap<ResourceLocation,String>();
        for(var key:targets)if(!possible.contains(key))result.put(key,"effective_source_excludes");
        return result;
    }
    public static List<String> describe(ServerLevel level) {
        var lines=new ArrayList<String>();var source=level.getChunkSource().getGenerator().getBiomeSource();
        lines.add("dimension="+level.dimension().location()+";seed="+level.getSeed()+";source="+source.getClass().getName());
        lines.add("audit="+BiomeSourceSafety.prepare(level));
        try {
            if(source.getClass()==MultiNoiseBiomeSource.class && ModList.get().isLoaded("terrablender")) {
                Object parameters=BiomeSourceSafety.call(source,"parameters","m_274409_");
                Class<?> api=Class.forName("terrablender.worldgen.IExtendedParameterList");
                int count=(int)api.getMethod("getTreeCount").invoke(parameters);
                var leaves=new TreeSet<String>();
                for(int i=0;i<count;i++)collect(api.getMethod("getTree",int.class).invoke(parameters,i),leaves);
                lines.add("effective_parameter_trees="+count+";leaf_biomes="+leaves);
                Class<?> type=Class.forName("terrablender.api.RegionType");
                Object aether=Class.forName("teamrazor.aeroblender.aether.AetherRegionType").getField("THE_AETHER").get(null);
                for(Object region:(List<?>)Class.forName("terrablender.api.Regions").getMethod("get",type).invoke(null,aether))
                    lines.add("provider="+region.getClass().getName()+";region="+region.getClass().getMethod("getName").invoke(region)
                            +";weight="+region.getClass().getMethod("getWeight").invoke(region));
            }
        } catch(ReflectiveOperationException | RuntimeException error) {lines.add("parameter_audit_incomplete="+error);}
        // 读取正在使用的 Forge 配置，而不是把磁盘默认值当作运行时配置。
        for(ModConfig config:ConfigTracker.INSTANCE.configSets().get(ModConfig.Type.COMMON))
            if(Set.of("aether","aether_redux","ancient_aether","deep_aether","aeroblender").contains(config.getModId()))
                lines.add("config="+config.getModId()+";"+config.getConfigData());
        lines.add("absence_policy=only_effective_source_exclusion;unreachable_climate_is_unknown");
        return List.copyOf(lines);
    }
    private static void collect(Object tree,Set<String> values) throws ReflectiveOperationException {
        var pending=new ArrayDeque<Object>();pending.add(tree);int remaining=131072;
        while(!pending.isEmpty()) {
            if(--remaining<0)throw new IllegalStateException("parameter tree limit");
            Object node=pending.removeLast();
            for(var field:node.getClass().getDeclaredFields()) {
                if(Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible())continue;
                Object value=field.get(node);
                if(value instanceof Holder<?> holder)holder.unwrapKey().ifPresent(key->values.add(key.location().toString()));
                else if(value!=null && field.getType().getName().endsWith("Climate$RTree$Node"))pending.add(value);
                else if(value instanceof Object[] children && field.getType().getComponentType().getName().contains("Climate$RTree$Node"))
                    for(Object child:children)if(child!=null)pending.add(child);
            }
        }
    }
}
