package com.chaosthedude.explorerscompass.unified.creature.client;

import java.util.*;
import com.chaosthedude.explorerscompass.unified.data.WorldDirectory;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** 连接级生物筛选目录，不写探险者的结构列表和历史统计。 */
public final class CreatureWorldCatalog {
    private static WorldDirectory.Snapshot data;
    private static ResourceLocation dimension;
    private static long requestedAt;
    private CreatureWorldCatalog() {}
    public static ResourceLocation currentDimension() {
        var level=Minecraft.getInstance().level; return level==null?null:level.dimension().location();
    }
    public static boolean ready() { return data!=null && Objects.equals(dimension,currentDimension()); }
    public static List<ResourceLocation> dimensions() { return ready()?List.copyOf(data.dimensions().keySet()):List.of(); }
    public static List<ResourceLocation> biomesIn(ResourceLocation selected) {
        if (!ready()) return List.of();
        return selected==null?data.biomes():data.biomes().stream()
                .filter(data.dimensions().getOrDefault(selected,Set.of())::contains).toList();
    }
    static void accept(ResourceLocation current,WorldDirectory.Snapshot snapshot) {
        if (!Objects.equals(current,currentDimension())) return;
        dimension=current; data=snapshot;
    }
    static void request() { requestedAt=System.currentTimeMillis(); CreatureClient.requestWorldCatalog(); }
    static void tick() {
        if (!ready() && CreatureClient.catalog!=null && System.currentTimeMillis()-requestedAt>20000) request();
    }
    static void clear() { data=null;dimension=null;requestedAt=0; }
}
