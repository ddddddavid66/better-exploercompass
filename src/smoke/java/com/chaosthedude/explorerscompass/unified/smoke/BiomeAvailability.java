package com.chaosthedude.explorerscompass.unified.smoke;

import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

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
    /** 日常旅行只记录来源与注册集合；大规模参数树反射属于离线诊断，不阻塞启动。 */
    public static List<String> describe(ServerLevel level) {
        var source=level.getChunkSource().getGenerator().getBiomeSource();
        return List.of("dimension="+level.dimension().location()+";seed="+level.getSeed()+";source="+source.getClass().getName(),
                "absence_policy=only_effective_source_exclusion");
    }
}
