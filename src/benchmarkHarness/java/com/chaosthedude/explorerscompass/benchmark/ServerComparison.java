package com.chaosthedude.explorerscompass.benchmark;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** 一个引擎一次运行 50 个正常请求；世界与生成器均独立，遵循真实服务器 tick。 */
@Mod("compassbench")
@Mod.EventBusSubscriber(modid = "compassbench")
public final class ServerComparison {
    private static final List<String> TARGETS = List.of("minecraft:village_plains", "minecraft:desert_pyramid", "minecraft:mansion", "minecraft:plains", "minecraft:lush_caves");
    private static final Path CSV = Path.of("comparison.csv");
    private static final String ENGINE = System.getProperty("compassbench.engine", "unknown");
    private static MinecraftServer server;
    private static ServerLevel level;
    private static LevelStorageSource.LevelStorageAccess storage;
    private static EngineAccess access;
    private static Map<ResourceKey<Level>, ServerLevel> levels;
    private static ResourceKey<Level> key;
    private static ServerPlayer player;
    private static ItemStack stack;
    private static int index;
    private static long started;
    private static double previousMain;
    private static final List<Double> ticks = new ArrayList<>();
    private static String failure = "";
    private static boolean requestPending;
    private static int settling=60;
    private static final boolean WARM_ONLY=Boolean.getBoolean("compassbench.warmOnly");
    private static final int REPEATS=Math.max(1,Integer.getInteger("compassbench.repeats",5));
    private static final int PREWARM=WARM_ONLY?Math.max(1,Integer.getInteger("compassbench.prewarm",10)):0;
    private static int perTarget() { return WARM_ONLY?REPEATS+PREWARM:REPEATS*2; }
    private static int target() { return index / perTarget(); }
    private static int repeat() { return WARM_ONLY?index%perTarget()-PREWARM:index/2%REPEATS; }
    private static boolean warm() { return WARM_ONLY || index % 2 == 1; }
    private static String cacheLabel() { return WARM_ONLY&&repeat()<0?"warmup":warm()?"warm":"cold"; }
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("compassbench.enabled")) return;
        server = event.getServer();
        try {
            access = new EngineAccess(); access.configure(); levels = levelMap();
            Files.writeString(CSV, "engine,seed,target,cache,repeat,radius,wall_ms,main_ms,search_tick_p95_ms,search_tick_max_ms,worker_ms,preparation_ms,confirmation_ms,waiting_ms,samples,candidates,checks,chunk_requests,mode,result,verified,x,y,z\n");
            next();
        } catch (Throwable error) { fail(error); }
    }
    @SuppressWarnings("unchecked")
    private static Map<ResourceKey<Level>, ServerLevel> levelMap() throws Exception {
        for (Field field : MinecraftServer.class.getDeclaredFields()) if (Map.class.isAssignableFrom(field.getType())) {
            field.setAccessible(true); Object value = field.get(server);
            if (value instanceof Map<?,?> map && map.get(Level.OVERWORLD) == server.overworld())
                return (Map<ResourceKey<Level>, ServerLevel>)map;
        }
        throw new IllegalStateException("Server dimension map unavailable");
    }
    private static void freshWorld() throws Exception {
        storage = LevelStorageSource.createDefault(Path.of("benchmark-worlds")).createAccess("cold_" + target() + "_" + repeat());
        key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation("compassbench", "overworld_" + target() + "_" + repeat()));
        // 同源世界副本，不复制活动世界的 session.lock。原始主世界没有用于任何被测搜索。
        Path source = server.getWorldPath(LevelResource.ROOT), destination = storage.getLevelPath(LevelResource.ROOT);
        for (String name : new String[]{"level.dat", "level.dat_old", "region", "entities", "poi", "data"}) {
            Path input = source.resolve(name);
            Path base = name.startsWith("level.dat") ? destination : storage.getDimensionPath(key);
            if (!Files.exists(input)) continue;
            if (Files.isDirectory(input)) try (var files = Files.walk(input)) {
                for (Path path : files.toList()) {
                    Path output = base.resolve(source.relativize(path));
                    if (Files.isDirectory(path)) Files.createDirectories(output);
                    else Files.copy(path, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } else Files.copy(input, base.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        var original = server.overworld();
        var generator = original.getChunkSource().getGenerator();
        var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        // 编解码创建独立生成器，而非让冷热用例共享生成器的内部懒缓存。
        generator = net.minecraft.world.level.chunk.ChunkGenerator.CODEC.parse(ops,
                net.minecraft.world.level.chunk.ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow(false, ignored -> {})).getOrThrow(false, ignored -> {});
        ChunkProgressListener listener = new ChunkProgressListener() {
            public void updateSpawnPos(ChunkPos pos) {} public void onStatusChange(ChunkPos pos, ChunkStatus status) {}
            public void start() {} public void stop() {}
        };
        level = new ServerLevel(server, net.minecraft.Util.backgroundExecutor(), storage,
                new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData()), key,
                new LevelStem(original.dimensionTypeRegistration(), generator), listener, false, original.getSeed(), List.of(), false, null);
        level.noSave = true; levels.put(key, level); server.markWorldsDirty();
    }
    private static void next() throws Exception {
        if (index == TARGETS.size()*perTarget()) { finish(); return; }
        if (!warm() || WARM_ONLY&&index%perTarget()==0) freshWorld();
        player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "CompassBench"));
        player.setGameMode(GameType.SURVIVAL); player.setPos(0, 64, 0);
        stack = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("explorerscompass:explorerscompass")));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack); ticks.clear();
        requestPending = true;
    }
    /** 统一在 START 发起；冷世界准备开销不会改变请求在 tick 内的位置。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void startRequest(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !requestPending || server == null) return;
        if(settling>0){settling--;return;}
        requestPending = false;
        try {
        started = System.nanoTime();
        boolean accepted = access.start(player, stack, new ResourceLocation(TARGETS.get(target())), target() >= 3);
        previousMain = 0;
        if (!accepted) throw new IllegalStateException("Normal request rejected " + TARGETS.get(target()));
        System.out.println("COMPASSBENCH start " + index + " " + TARGETS.get(target()) + " " + (warm() ? "warm" : "cold"));
        } catch (Throwable error) { fail(error); }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null || requestPending || !failure.isEmpty()) return;
        try {
            double main = access.mainNanos();
            if (!Double.isNaN(main)) { ticks.add(Math.max(0, main - previousMain) / 1e6); previousMain = main; }
            boolean timeout = System.nanoTime() - started > Long.getLong("compassbench.timeoutSeconds", 180) * 1_000_000_000L;
            int state = stack.getOrCreateTag().getInt("State");
            if (state == 1 && !timeout) return;
            long ended = System.nanoTime(); var tag = stack.getOrCreateTag();
            String result = timeout ? "timeout" : state == 2 ? "found" : tag.getString("ECFiltersSearchStage");
            if (result.isEmpty()) result = "not_found";
            int x = tag.getInt("FoundX"), z = tag.getInt("FoundZ");
            int y = tag.contains("ECFiltersFoundY") ? tag.getInt("ECFiltersFoundY") : tag.contains("FoundY") ? tag.getInt("FoundY") : 64;
            boolean verified = state == 2 && !timeout && validate(x, y, z);
            String row = csv(ENGINE, server.overworld().getSeed(), TARGETS.get(target()), cacheLabel(), repeat(), 40000,
                    (ended - started) / 1e6, main / 1e6, percentile(ticks, .95), percentile(ticks, 1),
                    access.metric("workerNanos", "priorWorker", false) / 1e6,
                    access.metric("preparationNanos", "priorPreparation", false) / 1e6,
                    access.metric("confirmationNanos", "priorConfirmation", false) / 1e6,
                    access.metric("waitingNanos", "priorWaiting", false) / 1e6,
                    access.metric("samples", "priorSamples", false), access.metric("candidates", "priorCandidates", false),
                    access.metric("confirmations", "priorChecks", true), access.metric("chunkLoads", "priorChunks", true),
                    access.mode(), result, verified, x, y, z);
            Files.writeString(CSV, row, java.nio.file.StandardOpenOption.APPEND);
            System.out.println("COMPASSBENCH result " + row.trim());
            access.cancel(player);
            if (warm() && (!WARM_ONLY || (index+1)%perTarget()==0)) { levels.remove(key); server.markWorldsDirty(); level.close(); storage.close(); level = null; storage = null; }
            index++; next();
        } catch (Throwable error) { fail(error); }
    }
    private static boolean validate(int x, int y, int z) {
        ResourceLocation id = new ResourceLocation(TARGETS.get(target()));
        if (target() >= 3) {
            Biome biome = level.registryAccess().registryOrThrow(Registries.BIOME).get(id);
            return level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(x >> 2, y >> 2, z >> 2,
                    level.getChunkSource().randomState().sampler()).value() == biome;
        }
        var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(id);
        // STRUCTURE_STARTS 已就绪不代表 StructureCheck 的持久化索引已更新；读取真实起始记录。
        // 正确性复核在 ended 时间戳以后进行；暖缓存的记录可能存在但区块已卸载。
        // 独立读取真实记录，不把 StructureCheck 的缓存状态误判为结构不存在。
        var chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.STRUCTURE_STARTS, true);
        if (chunk == null) return false;
        var start = level.structureManager().getStartForStructure(net.minecraft.core.SectionPos.bottomOf(chunk), structure, chunk);
        return start != null && start.isValid() && !start.getPieces().isEmpty();
    }
    private static double percentile(List<Double> values, double quantile) {
        if (values.isEmpty()) return Double.NaN;
        var sorted = values.stream().sorted().toList(); return sorted.get(Math.max(0, (int)Math.ceil(sorted.size() * quantile) - 1));
    }
    private static String csv(Object... values) {
        return java.util.Arrays.stream(values).map(value -> value instanceof Double number
                ? Double.isNaN(number) ? "N/A" : String.format(Locale.ROOT, "%.6f", number) : value.toString())
                .collect(java.util.stream.Collectors.joining(",")) + "\n";
    }
    private static void fail(Throwable error) {
        failure = error.toString(); error.printStackTrace();
        try { if (access != null && player != null) access.cancel(player); } catch (Exception ignored) { }
        finish();
    }
    private static void finish() {
        try {
            if (level != null) { levels.remove(key); server.markWorldsDirty(); level.close(); }
            if (storage != null) storage.close();
            Files.writeString(Path.of("comparison-status.txt"), failure.isEmpty() ? "COMPLETE: "+index+" requests\n" : "FAILED: " + failure + "\n");
        } catch (Exception error) { error.printStackTrace(); }
        server.halt(false); server = null;
    }
}
