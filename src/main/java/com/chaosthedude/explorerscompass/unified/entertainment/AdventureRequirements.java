package com.chaosthedude.explorerscompass.unified.entertainment;

import com.google.gson.*;
import java.util.*;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** 读取当前服务器的真实进度，不硬编码跨版本群系表，不直接授予条件。 */
public final class AdventureRequirements {
    public static final ResourceLocation ID = new ResourceLocation("minecraft:adventure/adventuring_time");
    public record Visit(String criterion, ResourceLocation biome) {}
    private AdventureRequirements() {}
    public static Advancement advancement(ServerPlayer player) {
        return player.getServer().getAdvancements().getAdvancement(ID);
    }
    public static List<Visit> read(Advancement advancement) {
        if (advancement == null) throw new IllegalArgumentException("entertainment.missing_advancement");
        for (var requirement : advancement.getRequirements())
            if (requirement.length != 1) throw new IllegalArgumentException("entertainment.unsupported_advancement");
        var criteria = advancement.deconstruct().serializeToJson().getAsJsonObject("criteria");
        var visits = new ArrayList<Visit>();
        for (var entry : criteria.entrySet()) {
            var criterion = entry.getValue().getAsJsonObject();
            if (!"minecraft:location".equals(criterion.get("trigger").getAsString()))
                throw new IllegalArgumentException("entertainment.unsupported_advancement");
            var keys = new LinkedHashSet<ResourceLocation>();
            findBiomes(criterion.get("conditions"), keys);
            if (keys.size() != 1) throw new IllegalArgumentException("entertainment.unsupported_advancement");
            visits.add(new Visit(entry.getKey(), keys.iterator().next()));
        }
        return List.copyOf(visits);
    }
    public static Map<ResourceLocation,String> exclusions(net.minecraft.server.level.ServerLevel level,Collection<ResourceLocation> targets) {
        var possible=new HashSet<ResourceLocation>();
        level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().forEach(h->h.unwrapKey().ifPresent(k->possible.add(k.location())));
        var result=new LinkedHashMap<ResourceLocation,String>();
        for(var key:targets)if(!possible.contains(key))result.put(key,"effective_source_excludes");
        return result;
    }
    private static void findBiomes(JsonElement element, Set<ResourceLocation> result) {
        if (element == null) return;
        if (element.isJsonArray()) { for (var child : element.getAsJsonArray()) findBiomes(child, result); return; }
        if (!element.isJsonObject()) return;
        for (var entry : element.getAsJsonObject().entrySet()) {
            if (entry.getKey().equals("biome") && entry.getValue().isJsonPrimitive()) {
                var key = ResourceLocation.tryParse(entry.getValue().getAsString());
                if (key != null) result.add(key);
            } else findBiomes(entry.getValue(), result);
        }
    }
    public static boolean visited(ServerPlayer player, Advancement advancement, Visit visit) {
        var criterion = player.getAdvancements().getOrStartProgress(advancement).getCriterion(visit.criterion());
        return criterion != null && criterion.isDone();
    }
}
