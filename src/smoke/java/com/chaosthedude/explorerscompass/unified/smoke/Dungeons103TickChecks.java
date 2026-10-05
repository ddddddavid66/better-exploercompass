package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.unified.search.SearchConfig;
import com.chaosthedude.explorerscompass.unified.search.SearchJob;
import com.chaosthedude.explorerscompass.unified.search.SearchService;
import com.chaosthedude.explorerscompass.unified.search.TeleportService;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/** 真实服务器 tick 验证，不手动推进 SearchService，不包含于发布包。 */
@Mod.EventBusSubscriber(modid = "filterschecks")
public final class Dungeons103TickChecks {
    private static final List<String> NAMES = List.of("tower", "leviathan", "snowy_temple", "warped_garden",
            "soul_prison", "end_ruins", "reanimated_ruins_mossy", "reanimated_ruins_mesa", "reanimated_ruins_frozen");
    private static final UUID PLAYER_ID = UUID.fromString("a930f13e-1111-1111-1111-111111111103");
    private static final GameProfile PROFILE = new GameProfile(PLAYER_ID, "Dungeons103Check");
    private static final BlockPos ORIGIN = new BlockPos(Integer.getInteger("unified.dungeons103.originX", 0),
            Integer.getInteger("unified.dungeons103.originY", 70), Integer.getInteger("unified.dungeons103.originZ", 0));
    private static final long TIMEOUT_NANOS = 120_000_000_000L;
    private static final Path CSV = Path.of("dungeons103-validation.csv");
    private static final Path REPORT = Path.of("smoke-dungeons103-results.txt");
    private static final ArrayDeque<Case> cases = new ArrayDeque<>();
    private static final Map<ResourceLocation, Hit> nearest = new HashMap<>();
    private static final Map<ResourceLocation, Boolean> sourceAllowed = new HashMap<>();
    private static final List<String> failures = new ArrayList<>();
    private static final StringBuilder notes = new StringBuilder();
    private static final StringBuilder rows = new StringBuilder("case,seed,dimension,origin_x,origin_y,origin_z,target,request_kind,request_uuid,elapsed_ms,main_ms,worker_ms,preparation_ms,confirmation_ms,waiting_ms,samples,candidates,checks,chunk_requests,mode,result,found_id,x,y,z\n");
    private static MinecraftServer server;
    private static Run current;
    private static boolean configured, oldAsync, finishing;
    private static int oldXp, oldDurability, oldNext, completed;

    private record Hit(ResourceLocation key, BlockPos position) {}
    private record Case(String label, ResourceLocation target, boolean async, boolean next, boolean group,
                        boolean clear, ResourceLocation biome) {}
    private record Run(Case test, ServerPlayer player, ItemStack stack, Object session, long started,
                       UUID token, Hit previous) {}
    private Dungeons103TickChecks() {}

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("unified.dungeons103") || !event.getServer().isDedicatedServer()) return;
        server = event.getServer();
        try {
            require(ModList.get().isLoaded("dungeons_plus"), "Dungeons Plus must be installed for this suite");
            require(ModList.get().isLoaded("structure_gel"), "Structure Gel must be installed for this suite");
            oldAsync = SearchConfig.ASYNC_STRUCTURES.get();
            oldXp = ConfigHandler.GENERAL.defaultXpLevels.get();
            oldDurability = ConfigHandler.GENERAL.compassDurability.get();
            oldNext = ConfigHandler.GENERAL.maxNextSearches.get();
            configured = true;
            ConfigHandler.GENERAL.defaultXpLevels.set(0);
            ConfigHandler.GENERAL.compassDurability.set(0);
            ConfigHandler.GENERAL.maxNextSearches.set(Math.max(1, oldNext));
            notes.append("Real ServerTick END lifecycle; actual SearchService sessions; no benchmark or tight-loop pumping.\n")
                    .append("Origin: ").append(ORIGIN).append("; radius: ").append(SearchConfig.RADIUS.get())
                    .append("; sample limit: ").append(SearchConfig.STRUCTURE_SAMPLES.get()).append("\n")
                    .append("Seed: ").append(server.overworld().getSeed()).append("\n");
            for (ServerLevel level : List.of(server.overworld(), server.getLevel(Level.NETHER), server.getLevel(Level.END))) {
                require(level != null, "all vanilla dimensions exist");
                auditSource(level);
            }
            for (String name : NAMES) {
                ResourceLocation key = id(name);
                require(level(key).registryAccess().registryOrThrow(Registries.STRUCTURE).containsKey(key), "registered " + key);
                cases.add(new Case("normal_async", key, true, false, false, true, null));
                cases.add(new Case("repeat_warm", key, true, false, false, true, null));
                cases.add(new Case("find_next", key, true, true, false, false, null));
                cases.add(new Case("sync_compatibility", key, false, false, false, true, null));
            }
            ResourceLocation group = StructureUtils.getStructureKeysToTypeKeys(server.overworld()).get(id("reanimated_ruins_mossy"));
            require(group != null, "reanimated ruins have a registered group");
            List<ResourceLocation> members = StructureUtils.getStructureKeysToTypeKeys(server.overworld()).entrySet().stream()
                    .filter(entry -> group.equals(entry.getValue())).map(Map.Entry::getKey).sorted().toList();
            require(members.equals(List.of(id("reanimated_ruins_frozen"), id("reanimated_ruins_mesa"), id("reanimated_ruins_mossy"))),
                    "reanimated ruins group contains exactly the three variants: " + members);
            cases.add(new Case("ruins_group", group, true, false, true, true, null));
            var mossy = StructureUtils.getStructureForKey(server.overworld(), id("reanimated_ruins_mossy"));
            ResourceLocation biome = mossy.biomes().stream().map(holder -> holder.unwrapKey().orElseThrow().location())
                    .sorted().findFirst().orElseThrow();
            cases.add(new Case("ruins_group_biome_filter", group, true, false, true, true, biome));
            notes.append("Group filter biome: ").append(biome).append("\n");
            Files.writeString(CSV, rows);
        } catch (Throwable error) {
            fail("suite initialization", error);
            finish();
        }
    }

    /** LOWEST 在原搜索事件之后观察结果；不会把测试的推进时间冒充服务端搜索时间。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null || finishing) return;
        try {
            if (current != null) {
                if (!SearchService.owns(current.player, current.stack)) complete("", System.nanoTime());
                else if (System.nanoTime() - current.started >= TIMEOUT_NANOS) {
                    SearchService.cancel(current.player, true);
                    complete("timeout", System.nanoTime());
                }
                return;
            }
            if (!cases.isEmpty()) start(cases.removeFirst());
            else {
                checkCancellationAndReplacement();
                require(SearchService.activeCount() == 0, "all search sessions released");
                require(TeleportService.ticketCount() == 0, "all teleport tickets released");
                require(verificationTickets() == 0, "all search verification tickets released");
                finish();
            }
        } catch (Throwable error) {
            fail(current == null ? "suite tick" : current.test.label + " " + current.test.target, error);
            if (current != null) {
                SearchService.cancel(current.player, true);
                try { complete("test_error", System.nanoTime()); }
                catch (Throwable secondary) { fail("record failed case", secondary); current = null; }
            } else finish();
        }
    }

    private static void start(Case test) throws Exception {
        SearchConfig.ASYNC_STRUCTURES.set(test.async);
        ServerLevel level = level(test.target);
        ServerPlayer player = FakePlayerFactory.get(level, PROFILE);
        player.setGameMode(GameType.CREATIVE);
        player.setPos(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ());
        ItemStack stack = player.getMainHandItem();
        if (stack.getItem() != ExplorersCompass.explorersCompass) {
            stack = new ItemStack(ExplorersCompass.explorersCompass);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        }
        SearchService.cancel(player, true);
        if (test.clear) clearPersonal(player, stack);
        Hit previous = found(stack);
        long start = System.nanoTime();
        boolean accepted = SearchService.requestStructures(player, stack, test.target, test.group, test.next, test.biome);
        if (!accepted) {
            failures.add(test.label + " " + test.target + ": valid test request rejected");
            rows.append(row(test, player, null, System.nanoTime() - start, null, "rejected", null));
            Files.writeString(CSV, rows);
            completed++;
            return;
        }
        Object session = sessions().get(player.getUUID());
        require(session != null, "accepted request has an active session");
        current = new Run(test, player, stack, session, number(session, "startedNanos"), SearchService.token(stack), previous);
    }

    private static void complete(String forcedResult, long ended) throws Exception {
        Run run = current;
        SearchJob job = (SearchJob)field(run.session, "job");
        Hit hit = forcedResult.isEmpty() ? found(run.stack) : null;
        String result = forcedResult.isEmpty() ? hit == null ? run.stack.getOrCreateTag().getString("ECFiltersSearchStage") : "found" : forcedResult;
        rows.append(row(run.test, run.player, run.token, Math.max(0, ended - run.started), run.session, result, hit));
        Files.writeString(CSV, rows);
        notes.append(run.test.label).append(' ').append(run.test.target).append(": ").append(result)
                .append("; mode=").append(job.mode()).append("; elapsed_ms=").append(ms(ended - run.started))
                .append("; samples=").append(job.samples()).append("; hit=").append(hit).append('\n');
        current = null;
        completed++;
        // 传送预备不是搜索的一部分；立即释放，避免其工作污染后续搜索用例。
        TeleportService.cancel(run.player.getUUID());
        require(TeleportService.ticketCount() == 0, "case teleport tickets released");
        require(verificationTickets() == 0, "case search verification tickets released");
        if (hit == null) failures.add(run.test.label + " " + run.test.target + ": " + result);
        else {
            if (run.test.async && Boolean.TRUE.equals(sourceAllowed.get(run.player.level().dimension().location()))
                    && !job.mode().equals("async")) failures.add(run.test.label + " " + run.test.target + ": audited source still used " + job.mode());
            if (run.test.label.equals("normal_async")) nearest.put(run.test.target, hit);
            if (run.test.label.equals("repeat_warm") || run.test.label.equals("sync_compatibility")) {
                Hit normal = nearest.get(run.test.target);
                if (normal == null || !normal.equals(hit)) failures.add(run.test.label + " " + run.test.target + ": XYZ mismatch normal=" + normal + ", actual=" + hit);
            }
            if (run.test.next && (run.previous == null || sameChunk(run.previous, hit)))
                failures.add("find_next " + run.test.target + ": did not exclude previous chunk " + run.previous);
            if (run.test.group && !hit.key.getPath().startsWith("reanimated_ruins_"))
                failures.add(run.test.label + ": unexpected group hit " + hit);
            if (run.test.biome != null) {
                var target = StructureUtils.getStructureForKey(run.player.serverLevel(), hit.key);
                if (target.biomes().stream().noneMatch(holder -> run.test.biome.equals(holder.unwrapKey().orElseThrow().location())))
                    failures.add(run.test.label + ": group result violates selected biome " + run.test.biome);
            }
        }
    }

    private static String row(Case test, ServerPlayer player, UUID token, long duration, Object session,
                              String result, Hit hit) throws Exception {
        SearchJob job = session == null ? null : (SearchJob)field(session, "job");
        return csv(test.label, player.serverLevel().getSeed(), player.level().dimension().location(), ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ(),
                test.target, test.next ? "next" : test.group ? "group" : "normal", token == null ? "" : token, ms(duration),
                job == null ? "" : ms(number(session, "priorMain") + job.mainNanos),
                job == null ? "" : ms(number(session, "priorWorker") + job.workerNanos()),
                job == null ? "" : ms(number(session, "priorPreparation") + job.preparationNanos()),
                job == null ? "" : ms(number(session, "priorConfirmation") + job.confirmationNanos()),
                job == null ? "" : ms(number(session, "priorWaiting") + job.waitingNanos()),
                job == null ? "" : number(session, "priorSamples") + job.samples(),
                job == null ? "" : number(session, "priorCandidates") + job.candidates(),
                job == null ? "" : number(session, "priorChecks") + job.confirmations,
                job == null ? "" : number(session, "priorChunks") + job.chunkLoads,
                job == null ? "" : job.mode(), result, hit == null ? "" : hit.key,
                hit == null ? "" : hit.position.getX(), hit == null ? "" : hit.position.getY(), hit == null ? "" : hit.position.getZ());
    }

    private static void checkCancellationAndReplacement() throws Exception {
        SearchConfig.ASYNC_STRUCTURES.set(true);
        var player = FakePlayerFactory.get(server.overworld(), PROFILE);
        var stack = new ItemStack(ExplorersCompass.explorersCompass);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.setGameMode(GameType.CREATIVE);
        player.setPos(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ());
        clearPersonal(player, stack);
        require(SearchService.requestStructures(player, stack, id("tower"), false, false, null), "cancellation request accepted");
        UUID old = SearchService.token(stack);
        require(SearchService.requestStructures(player, stack, id("leviathan"), false, false, null), "replacement request accepted");
        require(!old.equals(SearchService.token(stack)), "replacement has a distinct token");
        require(SearchService.activeCount() == 1, "replacement retains one player session");
        SearchService.cancel(player, true);
        require(!SearchService.owns(player, stack), "cancel removes replacement session");
        notes.append("Cancellation/replacement: PASS; distinct tokens and one active session, then zero sessions/tickets.\n");
    }

    private static void auditSource(ServerLevel level) throws Exception {
        Class<?> type = Class.forName("com.chaosthedude.explorerscompass.unified.search.BiomeSourceSafety");
        Method prepare = type.getDeclaredMethod("prepare", ServerLevel.class);
        prepare.setAccessible(true);
        Object result = prepare.invoke(null, level);
        Method allowed = result.getClass().getDeclaredMethod("allowed"), reason = result.getClass().getDeclaredMethod("reason"), chain = result.getClass().getDeclaredMethod("chain");
        allowed.setAccessible(true); reason.setAccessible(true); chain.setAccessible(true);
        boolean safe = (boolean)allowed.invoke(result);
        sourceAllowed.put(level.dimension().location(), safe);
        notes.append("Source audit ").append(level.dimension().location()).append(": allowed=").append(safe)
                .append("; reason=").append(reason.invoke(result)).append("; chain=").append(chain.invoke(result)).append('\n');
        require(safe, "known validation source is audited: " + level.dimension().location() + " " + reason.invoke(result));
    }

    private static void finish() {
        if (finishing) return;
        finishing = true;
        if (current != null) SearchService.cancel(current.player, true);
        if (configured) {
            SearchConfig.ASYNC_STRUCTURES.set(oldAsync);
            ConfigHandler.GENERAL.defaultXpLevels.set(oldXp);
            ConfigHandler.GENERAL.compassDurability.set(oldDurability);
            ConfigHandler.GENERAL.maxNextSearches.set(oldNext);
        }
        String result = (failures.isEmpty() ? "PASS" : "FAIL") + ": real-tick Dungeons Plus 1.0.3 checks; completed=" + completed
                + "; failures=" + failures.size() + "; no fixed one-second assertion.\n" + notes
                + (failures.isEmpty() ? "" : "Failures:\n" + String.join("\n", failures) + "\n");
        try { Files.writeString(CSV, rows); Files.writeString(REPORT, result); }
        catch (Exception error) { error.printStackTrace(); }
        System.out.println(result);
        server.halt(false);
    }

    private static void clearPersonal(ServerPlayer player, ItemStack stack) {
        SearchService.clearCache(player.getUUID());
        stack.getOrCreateTag().remove("PrevPos");
        stack.getOrCreateTag().putInt("ECFiltersNextSearches", 0);
    }
    private static Hit found(ItemStack stack) {
        var compass = ExplorersCompass.explorersCompass;
        if (compass.getState(stack) != CompassState.FOUND) return null;
        var tag = stack.getOrCreateTag();
        return new Hit(compass.getStructureKey(stack), new BlockPos(tag.getInt("FoundX"), tag.getInt(SearchService.FOUND_Y), tag.getInt("FoundZ")));
    }
    private static boolean sameChunk(Hit a, Hit b) {
        return a.position.getX() >> 4 == b.position.getX() >> 4 && a.position.getZ() >> 4 == b.position.getZ() >> 4;
    }
    private static ResourceLocation id(String name) { return new ResourceLocation("dungeons_plus", name); }
    private static ServerLevel level(ResourceLocation target) {
        return server.getLevel(target.getPath().equals("soul_prison") ? Level.NETHER : target.getPath().equals("end_ruins") ? Level.END : Level.OVERWORLD);
    }
    @SuppressWarnings("unchecked") private static Map<UUID, ?> sessions() throws Exception {
        Field value = SearchService.class.getDeclaredField("sessions"); value.setAccessible(true);
        return (Map<UUID, ?>)value.get(null);
    }
    private static Object field(Object owner, String name) throws Exception {
        Field value = owner.getClass().getDeclaredField(name); value.setAccessible(true); return value.get(owner);
    }
    private static long number(Object owner, String name) throws Exception { return ((Number)field(owner, name)).longValue(); }
    private static int verificationTickets() throws Exception {
        Class<?> type = Class.forName("com.chaosthedude.explorerscompass.unified.search.StructureVerification");
        Method value = type.getDeclaredMethod("ticketCount"); value.setAccessible(true); return (int)value.invoke(null);
    }
    private static String ms(long nanos) { return String.format(Locale.ROOT, "%.6f", nanos / 1_000_000.0); }
    private static String csv(Object... values) {
        var row = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i != 0) row.append(',');
            row.append('"').append(String.valueOf(values[i]).replace("\"", "\"\"")).append('"');
        }
        return row.append('\n').toString();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void fail(String context, Throwable error) { failures.add(context + ": " + error); error.printStackTrace(); }
}
