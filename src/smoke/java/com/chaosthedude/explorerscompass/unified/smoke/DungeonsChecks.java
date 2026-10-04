package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.unified.search.*;

import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import static com.chaosthedude.explorerscompass.unified.smoke.SmokeChecks.check;

/** 真实依赖、正式重映射入口和真实世界记录验收；不采集 benchmark 数据。 */
public final class DungeonsChecks {
    private static final List<String> NAMES = List.of("tower", "leviathan", "snowy_temple", "warped_garden",
            "soul_prison", "end_ruins", "reanimated_ruins_mossy", "reanimated_ruins_mesa", "reanimated_ruins_frozen");
    private DungeonsChecks() {}
    public static void verify(MinecraftServer server) throws Exception {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("dungeons_plus")) return;
        var overworld = server.overworld();
        gridRules(overworld);
        templateGuard(overworld);
        unknownRules(overworld);
        SearchCandidate firstTower = null;
        for (String name : NAMES) {
            ServerLevel level = server.getLevel(name.equals("soul_prison") ? Level.NETHER : name.equals("end_ruins") ? Level.END : Level.OVERWORLD);
            var key = new ResourceLocation("dungeons_plus", name);
            var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(key);
            check(structure != null, "actual registered Dungeons structure " + name);
            var job = new StructureSearch(level, new BlockPos(0, 70, 0), List.of(key), Set.of(), 40000, 25000000, true);
            try {
                finish(job, server);
                check(job.result() != null && job.mode().equals("async"), "actual async Dungeons search " + name + " mode=" + job.mode());
                var found = job.result();
                var chunk = level.getChunk(found.chunk().x, found.chunk().z, ChunkStatus.STRUCTURE_STARTS);
                var start = level.structureManager().getStartForStructure(SectionPos.bottomOf(chunk), found.structure(), chunk);
                check(start != null && start.isValid(), "actual Dungeons structure record " + name);
                var sync = new StructureSearch(level, new BlockPos(0, 70, 0), List.of(key), Set.of(), 40000, 25000000, false);
                try {
                    finish(sync, server);
                    check(sync.result() != null && sync.result().position().equals(found.position()), "async/sync XYZ identical " + name);
                    check(sync.mode().equals("sync"), "switch off runs on main " + name);
                } finally { sync.cancel(); }
                System.out.println("DUNGEONS PASS " + name + " " + found.position() + " mode=" + job.mode()
                        + " regions=" + job.samples() + " candidates=" + job.candidates() + " records=" + job.confirmations);
                if (name.equals("tower")) firstTower = found;
            } finally { job.cancel(); }
        }
        var towers = List.of(new ResourceLocation("dungeons_plus:tower"));
        var next = new StructureSearch(overworld, BlockPos.ZERO, towers, Set.of(firstTower.chunk().toLong()), 40000, 25000000, true);
        try { finish(next, server); check(next.result() != null && !next.result().chunk().equals(firstTower.chunk()), "next excludes prior tower"); }
        finally { next.cancel(); }
        var towerChunk = overworld.getChunk(firstTower.chunk().x, firstTower.chunk().z, ChunkStatus.STRUCTURE_STARTS);
        var section = SectionPos.bottomOf(towerChunk);
        var original = overworld.structureManager().getStartForStructure(section, firstTower.structure(), towerChunk);
        overworld.structureManager().setStartForStructure(section, firstTower.structure(), StructureStart.INVALID_START, towerChunk);
        var missing = new StructureSearch(overworld, BlockPos.ZERO, towers, Set.of(), 40000, 25000000, true);
        try { finish(missing, server); check(missing.result() != null && !missing.result().chunk().equals(firstTower.chunk()), "missing old Dungeons start continues"); }
        finally { missing.cancel(); overworld.structureManager().setStartForStructure(section, firstTower.structure(), original, towerChunk); }
        var ruins = NAMES.stream().filter(name -> name.startsWith("reanimated_ruins")).map(name -> new ResourceLocation("dungeons_plus", name)).toList();
        var group = new StructureSearch(overworld, BlockPos.ZERO, ruins, Set.of(), 40000, 25000000, true);
        try { finish(group, server); check(group.result() != null && ruins.contains(group.result().key()), "all three ruin variants kept in one placement group"); }
        finally { group.cancel(); }
        var cancellation = new StructureSearch(overworld, BlockPos.ZERO, towers, Set.of(), 40000, 25000000, true);
        cancellation.step(); cancellation.cancel();
        check(!cancellation.step() && cancellation.result() == null, "cancelled prediction cannot produce late result");
        var fallback = new StructureSearch(overworld, BlockPos.ZERO, towers, Set.of(), 40000, 25000000, true).synchronousFallback();
        try { finish(fallback, server); check(fallback.mode().equals("mixed") && !fallback.asynchronous() && fallback.result() != null, "fallback retains async grid without recursive retries"); }
        finally { fallback.cancel(); }
        SearchService.stopAll();
        var afterReload = new StructureSearch(overworld, BlockPos.ZERO, towers, Set.of(), 40000, 25000000, true);
        try { finish(afterReload, server); check(afterReload.result() != null, "worldgen snapshots rebuild after invalidation"); }
        finally { afterReload.cancel(); }
        System.out.println("DUNGEONS ALL PASS: nine structures, official grid parity, snapshots, sync switches, group, next, missing old chunks, cancellation, fallback, reload");
    }
    private static void finish(SearchJob job, MinecraftServer server) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (!job.done() && System.nanoTime() < deadline) {
            boolean advanced = job.step();
            if (!SmokeChecks.pump(server) && !advanced) Thread.sleep(1);
        }
        check(job.done(), "Dungeons search completes before test deadline");
    }
    private static void unknownRules(ServerLevel level) throws Exception {
        var key=new ResourceLocation("dungeons_plus:leviathan");
        var original=level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(key);
        var ops=RegistryOps.create(JsonOps.INSTANCE,level.registryAccess());
        var json=net.minecraft.world.level.levelgen.structure.Structure.DIRECT_CODEC.encodeStart(ops,original).getOrThrow(false,System.out::println).getAsJsonObject();
        var type=Class.forName("com.chaosthedude.explorerscompass.unified.search.DungeonsPrediction");
        var create=type.getDeclaredMethod("of",ServerLevel.class,ResourceLocation.class,net.minecraft.world.level.levelgen.structure.Structure.class);create.setAccessible(true);
        var allowed=type.getDeclaredMethod("allowed");allowed.setAccessible(true);
        var named=json.deepCopy();named.addProperty("start_jigsaw_name","unifiedchecks:custom_name");
        var customStart=net.minecraft.world.level.levelgen.structure.Structure.DIRECT_CODEC.parse(ops,named).getOrThrow(false,System.out::println);
        check(!(boolean)allowed.invoke(create.invoke(null,level,key,customStart)),"custom named jigsaw stays on compatibility path");
        var missing=json.deepCopy();missing.remove("capability");
        var customCapability=net.minecraft.world.level.levelgen.structure.Structure.DIRECT_CODEC.parse(ops,missing).getOrThrow(false,System.out::println);
        check(!(boolean)allowed.invoke(create.invoke(null,level,key,customCapability)),"unknown/absent capability stays on compatibility path");
        // 不改注册表：用独立解码对象检验分类，原世界的结构与数据包保持真实规则。
        System.out.println("DUNGEONS POLICY PASS: unverified named start and absent capability rejected before background prediction");
    }
    private static void templateGuard(ServerLevel level) throws Exception {
        var manager = level.getServer().getStructureManager();
        var key = new ResourceLocation("unifiedchecks:nonexistent");
        var snapshot = new StructureTemplate();
        boolean isolated = SearchPool.executor().submit(() -> PredictionTemplates.with(Map.of(key, snapshot), () ->
                manager.getOrCreate(key) == snapshot && manager.get(key).orElseThrow() == snapshot)).get(10, TimeUnit.SECONDS);
        check(isolated && manager.get(key).isEmpty(), "background returns snapshot without mutating manager or loading resource");
        var blocked = SearchPool.executor().submit(() -> PredictionTemplates.with(Map.of(), () -> {
            try { manager.getOrCreate(key); return false; } catch (IllegalStateException expected) { return true; }
        })).get(10, TimeUnit.SECONDS);
        check(blocked, "snapshot miss blocked on worker");
        check(!SearchPool.executor().submit(PredictionTemplates::active).get(10, TimeUnit.SECONDS), "template context cleared after exception");
        var chunksBlocked = SearchPool.executor().submit(() -> {
            try { level.getChunkSource().getChunk(0, 0, ChunkStatus.FULL, false); return false; }
            catch (IllegalStateException expected) { return true; }
        }).get(10, TimeUnit.SECONDS);
        check(chunksBlocked, "world chunks blocked on prediction worker");
    }
    private static Object adapter(StructurePlacement placement, net.minecraft.core.RegistryAccess registries) throws Exception {
        var type=Class.forName("com.chaosthedude.explorerscompass.unified.search.GelGridPlacement");
        var method=type.getDeclaredMethod("of", StructurePlacement.class, net.minecraft.core.RegistryAccess.class);
        method.setAccessible(true); return method.invoke(null,placement,registries);
    }
    private static Object parameter(Object adapter, String name) throws Exception {
        var field=adapter.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(adapter);
    }
    @SuppressWarnings("unchecked")
    private static java.util.Optional<ChunkPos> feature(Object adapter, net.minecraft.world.level.chunk.ChunkGeneratorStructureState state, int x, int z) throws Exception {
        var method=adapter.getClass().getDeclaredMethod("feature", net.minecraft.world.level.chunk.ChunkGeneratorStructureState.class,int.class,int.class);
        method.setAccessible(true);return (java.util.Optional<ChunkPos>)method.invoke(adapter,state,x,z);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void gridRules(ServerLevel level) throws Exception {
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var holder = registry.getHolder(ResourceKey.create(Registries.STRUCTURE, new ResourceLocation("dungeons_plus:tower"))).orElseThrow();
        var state = level.getChunkSource().getGeneratorState(); state.ensureStructuresGenerated();
        var placement = state.getPlacementsForStructure(holder).get(0);
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        var base = StructurePlacement.CODEC.encodeStart(ops, placement).getOrThrow(false, System.out::println).getAsJsonObject();
        for (long seed : List.of(1L, 42L, level.getSeed())) {
        var sampledState = net.minecraft.world.level.chunk.ChunkGeneratorStructureState.createForNormal(
                level.getChunkSource().randomState(), seed, level.getChunkSource().getGenerator().getBiomeSource(),
                level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET));
        for (int offset : List.of(0, 3, 7)) for (float frequency : List.of(0f, .75f, 1f)) for (boolean near : List.of(false, true)) {
            var json = base.deepCopy(); json.addProperty("spacing", 7); json.addProperty("offset", offset);
            json.addProperty("frequency", frequency); json.addProperty("allowed_near_spawn", near);
            var rule = StructurePlacement.CODEC.parse(ops, json).getOrThrow(false, System.out::println);
            var adapter = adapter(rule, level.registryAccess());
            check(adapter != null && parameter(adapter, "spacing").equals(7) && parameter(adapter, "offset").equals(offset), "actual placement parameters decoded");
            Set<Long> expected = new HashSet<>(), actual = new HashSet<>();
            for (int x = -32; x <= 32; x++) for (int z = -32; z <= 32; z++) if (rule.isStructureChunk(sampledState, x, z)) expected.add(new ChunkPos(x, z).toLong());
            for (int x = -6; x <= 5; x++) for (int z = -6; z <= 5; z++) {
                var candidate = feature(adapter, sampledState, x, z);
                if (candidate.isEmpty()) continue;
                var chunk = candidate.get();
                if (chunk.x >= -32 && chunk.x <= 32 && chunk.z >= -32 && chunk.z <= 32 && rule.isStructureChunk(sampledState, chunk.x, chunk.z)) actual.add(chunk.toLong());
            }
            check(expected.equals(actual), "region candidates equal exhaustive official placement across negative borders and offset=spacing");
        }
        }
        var zoneType = Class.forName("com.legacy.structure_gel.api.structure.GridStructurePlacement$TaggedExclusionZone");
        var zone = zoneType.getConstructor(HolderSet.class, int.class).newInstance(HolderSet.direct(holder), 1);
        var zoneField = zoneType.getDeclaredField("CODEC"); zoneField.setAccessible(true);
        var zoneCodec = (com.mojang.serialization.Codec)zoneField.get(null);
        var withZone = base.deepCopy();
        withZone.add("exclusion_zone", (com.google.gson.JsonElement)zoneCodec.encodeStart(ops, zone).getOrThrow(false, System.out::println));
        var excluded = StructurePlacement.CODEC.parse(ops, withZone).getOrThrow(false, System.out::println);
        check((boolean)parameter(adapter(excluded, level.registryAccess()), "exclusion"), "tagged exclusion remains main-thread validated");
        System.out.println("DUNGEONS GRID PASS: official exhaustive parity on 54 frequency/offset/spawn combinations across seeds 1/42/789123; tagged exclusion detected");
    }
}
