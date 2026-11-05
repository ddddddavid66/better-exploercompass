package com.chaosthedude.explorerscompass.unified.search;

import com.david.compass.runtime.game.search.BiomeGenerationContext;
import com.david.compass.runtime.game.search.BiomeQueryFactory;
import com.david.compass.runtime.game.search.BiomeSearch;
import com.david.compass.runtime.game.search.GenerationStamp;
import com.david.compass.runtime.game.search.ScanCoordinator;
import com.david.compass.runtime.game.search.SearchCandidate;
import com.david.compass.runtime.game.search.SearchJob;
import com.david.compass.runtime.game.search.SearchReplacement;
import com.david.compass.runtime.game.search.TeleportService;

import com.david.compass.runtime.BudgetedSearchScheduler;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.chaosthedude.explorerscompass.unified.search.SearchRules;
import com.chaosthedude.explorerscompass.unified.CompassUnified;
import com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork;
import com.chaosthedude.explorerscompass.unified.network.CatalogService;
import com.chaosthedude.explorerscompass.unified.network.SearchStatusPacket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

/** 会话、NBT、缓存和通知均限定在服务端线程；后台只有 ScanCoordinator 的采样闭包。 */
public final class SearchService {
    public static final String KIND = "ECFiltersTargetKind", BIOME_KEY = "ECFiltersBiomeKey", FOUND_Y = "ECFiltersFoundY";
    public static final String TARGET_DIMENSION = "ECFiltersTargetDimension";
    private static final String TOKEN = "ECFiltersSearchToken";
    private static final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private static final Map<CacheKey, List<BlockPos>> biomeHistory = new HashMap<>();
    private static final Map<StructureCacheKey, Set<Long>> structureHistory = new HashMap<>();
    private static int cursor;
    private static long pendingPreparationNanos;
    private static final Map<UUID, Set<Long>> featureHistory = new HashMap<>();
    private record CacheKey(UUID player, ResourceLocation dimension, ResourceLocation biome) {}
    private record StructureCacheKey(UUID player, ResourceLocation dimension) {}
    private static final class Session {
        final ServerPlayer player;
        final ServerLevel level;
        final GenerationStamp stamp;
        final ItemStack stack;
        final UUID token = UUID.randomUUID();
        final ResourceLocation category;
        SearchJob job;
        BiomeQueryFactory.Plan queryPlan;
        List<ResourceLocation> requested;
        long startedAt=System.currentTimeMillis(),startedNanos=System.nanoTime();
        long priorSamples,priorCandidates,priorMain,priorWorker,priorPreparation,priorConfirmation,priorWaiting;int priorChecks,priorChunks;
        boolean terminalSent;
        String exceptions="";
        CompoundTag failureDetails=new CompoundTag();
        BiomeRequest request;
        boolean travel;
        long lastSamples = -1;
        int ticks;
        String lastStage = "";
        boolean group, next, fresh=true;
        Session(ServerPlayer player, ItemStack stack, ResourceLocation category, SearchJob job) {
            this.player = player; this.level = player.serverLevel(); this.stack = stack;
            stamp=GenerationStamp.capture(level);
            this.category = category; this.job = job;this.requested=List.of(category);
        }
        boolean valid() {
            return !player.isRemoved() && player.serverLevel() == level && stamp.current(level)
                    && (player.getMainHandItem() == stack || player.getOffhandItem() == stack)
                    && !stack.isEmpty() && stack.getTag() != null && stack.getTag().hasUUID(TOKEN)
                    && token.equals(stack.getTag().getUUID(TOKEN));
        }
    }
    private SearchService() {}

    public static boolean startStructures(ServerPlayer player, ItemStack stack, ResourceLocation category,
                                           List<ResourceLocation> requested) {
        return startStructures(player, stack, category, requested, !requested.contains(category), false);
    }

    /** 服务端从注册表推导组成员与群系交集，不接受客户端提供的任意结构数组。 */
    public static boolean requestStructures(ServerPlayer player, ItemStack stack, ResourceLocation category,
                                            boolean group, boolean next, ResourceLocation biome) {
        if (!held(player, stack)) return false;
        var compass = ExplorersCompass.explorersCompass;
        if (next) {
            if (isBiome(stack) || compass.getState(stack) != CompassState.FOUND) return false;
            var historyTag = stack.getOrCreateTag();
            int searches = historyTag.contains("ECFiltersNextSearches") ? historyTag.getInt("ECFiltersNextSearches")
                    : Math.max(0, historyTag.getList("PrevPos", net.minecraft.nbt.Tag.TAG_COMPOUND).size() - 1);
            if (SearchRules.nextLimit() <= 0 || searches >= SearchRules.nextLimit()) {
                SearchRules.message(player, "filters.next_limit"); return false;
            }
            var tag = stack.getOrCreateTag();
            // 仅在接续成功后写入，拒绝请求不能修改原指南针的历史状态。
            category = com.chaosthedude.explorerscompass.unified.data.TargetDimension.parse(tag.getString("ECFiltersStructureCategory"));
            if (category == null) {
                category = compass.getStructureKey(stack);
                group = tag.getBoolean("IsGroup");
                if (group) category = StructureUtils.getStructureKeysToTypeKeys(player.serverLevel()).get(category);
            } else group = tag.getBoolean("ECFiltersStructureGroup");
        }
        if (category == null) return false;
        List<ResourceLocation> keys;
        if (group) {
            ResourceLocation selectedGroup = category;
            keys = StructureUtils.getStructureKeysToTypeKeys(player.serverLevel()).entrySet().stream()
                    .filter(entry -> selectedGroup.equals(entry.getValue())).map(Map.Entry::getKey).toList();
        } else keys = List.of(category);
        if (biome != null) {
            var biomes = player.serverLevel().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            if (!biomes.containsKey(biome)) return false;
            keys = keys.stream().filter(key -> {
                if (EndDungeonAdapter.special(player.serverLevel(), key)) return EndDungeonAdapter.biomes(player.serverLevel()).contains(biome);
                if (GoldenCrateAdapter.special(player.serverLevel(),key)) return GoldenCrateAdapter.biomes(player.serverLevel()).contains(biome);
                var structure = StructureUtils.getStructureForKey(player.serverLevel(), key);
                return structure != null && structure.biomes().stream().anyMatch(holder -> biome.equals(biomes.getKey(holder.value())));
            }).toList();
        }
        return startStructures(player, stack, category, keys, group, next);
    }

    private static boolean startStructures(ServerPlayer player, ItemStack stack, ResourceLocation category,
                                             List<ResourceLocation> requested, boolean group, boolean next) {
        long before = System.nanoTime(), beforeMillis=System.currentTimeMillis();
        if (!held(player, stack)) return false;
        Set<ResourceLocation> allowed = CatalogService.allowedSet(player.serverLevel());
        List<ResourceLocation> keys = requested.stream().filter(allowed::contains).distinct().toList();
        if (keys.isEmpty()) return false;
        ResourceLocation costKey = next ? ExplorersCompass.explorersCompass.getStructureKey(stack) : category;
        if (!SearchRules.affordable(player, stack, costKey)) return false;
        Set<Long> previous = new HashSet<>();
        previous.addAll(structureHistory.getOrDefault(new StructureCacheKey(player.getUUID(), player.level().dimension().location()), Set.of()));
        // 接续原版旧指南针的“下一个”记录；扩展缓存仍按玩家与维度共享。
        if (next) for (var value : stack.getOrCreateTag().getList("PrevPos", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var point = (CompoundTag) value;
            previous.add(new ChunkPos(new BlockPos(point.getInt("X"), point.getInt("Y"), point.getInt("Z"))).toLong());
        }
        int radius = SearchConfig.RADIUS.get(); long samples = SearchConfig.STRUCTURE_SAMPLES.get();
        boolean feature = keys.size() == 1 && EndDungeonAdapter.special(player.serverLevel(), keys.get(0));
        if (feature && !player.serverLevel().dimension().equals(net.minecraft.world.level.Level.END)) return false;
        boolean crate=keys.size()==1 && GoldenCrateAdapter.special(player.serverLevel(),keys.get(0));
        if (crate && !GoldenCrateAdapter.DIMENSION.equals(player.serverLevel().dimension().location())) return false;
        SearchJob job;
        if (feature || crate) {
            Set<Long> anchors = new HashSet<>(featureHistory.getOrDefault(player.getUUID(), Set.of()));
            if (next) for (var value : stack.getOrCreateTag().getList("PrevPos", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                var point = (CompoundTag)value;
                anchors.add(new BlockPos(point.getInt("X"),point.getInt("Y"),point.getInt("Z")).asLong());
            }
            // Feature 不受原版“生成结构”开关控制，不能沿用 Structure 的半径截断。
            job = crate ? new GoldenCrateSearch(player.serverLevel(),player.blockPosition(),anchors,radius,samples,SearchConfig.ASYNC_STRUCTURES.get())
                    : new EndDungeonSearch(player.serverLevel(), player.blockPosition(), anchors, radius, samples, SearchConfig.ASYNC_STRUCTURES.get());
        } else job = new StructureSearch(player.serverLevel(), player.blockPosition(), keys, previous,
                player.getServer().getWorldData().worldGenOptions().generateStructures() ? radius : 0,
                samples, SearchConfig.ASYNC_STRUCTURES.get());
        com.chaosthedude.explorerscompass.unified.entertainment.AdventureTravel.interrupt(player, "entertainment.replaced");
        cancel(player, true);
        Session session = new Session(player, stack, category, job);
        session.group = group; session.next = next;session.requested=List.copyOf(keys);session.startedNanos=before;session.startedAt=beforeMillis;
        begin(session);
        SearchRules.charge(player, costKey);
        long used=System.nanoTime()-before;
        job.recordRequestPreparation(used);pendingPreparationNanos+=used;
        return true;
    }

    public static boolean startBiome(ServerPlayer player, ItemStack stack, ResourceLocation key) {
        return startBiome(player, stack, key, SearchConfig.BIOME_SAMPLES.get());
    }
    /** 服务端调用方提供本次额度快照；测试无需修改持久化 Forge 配置。 */
    public static boolean startBiome(ServerPlayer player, ItemStack stack, ResourceLocation key, long sampleLimit) {
        return startBiome(player, stack, key, sampleLimit, null);
    }
    /** 普通与娱乐请求共用参数和生成引擎；娱乐仅使用任务内的精确失败坐标。 */
    public static BiomeRequest startTravelBiome(ServerPlayer player,ItemStack stack,ResourceLocation key,
                                               java.util.Collection<BlockPos> failed) {
        return startTravelBiome(player,stack,key,failed,player.blockPosition());
    }
    public static BiomeRequest startTravelBiome(ServerPlayer player,ItemStack stack,ResourceLocation key,
                                               java.util.Collection<BlockPos> failed,BlockPos origin) {
        return startTravelBiome(player,stack,key,failed,origin,new SearchWindow(SearchConfig.RADIUS.get(),-1,SearchConfig.BIOME_SAMPLES.get()));
    }
    public record SearchWindow(int radius,int innerRadius,long quota,BiomeQueryFactory.Plan plan) {
        public SearchWindow(int radius,int innerRadius,long quota){this(radius,innerRadius,quota,null);}
    }
    public static BiomeRequest startTravelBiome(ServerPlayer player,ItemStack stack,ResourceLocation key,
                                               java.util.Collection<BlockPos> failed,BlockPos origin,SearchWindow window) {
        BiomeRequest request=new BiomeRequest();
        try { startBiome(player,stack,key,window.quota(),request,List.copyOf(failed),origin.immutable(),window); }
        catch(RuntimeException error) {
            if(request.session!=null && sessions.get(player.getUUID())==request.session)cancel(player,true,"error");
            request.state=BiomeRequest.State.ERROR;request.reason=error.toString();
        }
        return request;
    }
    /** 验收或诊断可观察普通入口的相同会话，不改变其历史缓存行为。 */
    public static BiomeRequest observeBiome(ServerPlayer player) {
        Session session=sessions.get(player.getUUID());
        if(session==null || !session.job.biome())return new BiomeRequest();
        if(session.request==null) {session.request=new BiomeRequest();session.request.attach(session);}
        return session.request;
    }
    private static boolean startBiome(ServerPlayer player,ItemStack stack,ResourceLocation key,long sampleLimit,
                                      BiomeRequest request) {
        return startBiome(player,stack,key,sampleLimit,request,List.of(),player.blockPosition());
    }
    private static boolean startBiome(ServerPlayer player,ItemStack stack,ResourceLocation key,long sampleLimit,
                                      BiomeRequest request,List<BlockPos> failed,BlockPos origin) {
        return startBiome(player,stack,key,sampleLimit,request,failed,origin,new SearchWindow(SearchConfig.RADIUS.get(),-1,sampleLimit));
    }
    private static boolean startBiome(ServerPlayer player,ItemStack stack,ResourceLocation key,long sampleLimit,
                                      BiomeRequest request,List<BlockPos> failed,BlockPos origin,SearchWindow window) {
        long before=System.nanoTime(),beforeMillis=System.currentTimeMillis();
        if(!held(player,stack) || SearchRules.broken(stack))return false;
        var source=player.serverLevel().getChunkSource().getGenerator().getBiomeSource();
        if(source.possibleBiomes().stream().noneMatch(holder->holder.unwrapKey()
                .map(resource->resource.location().equals(key)).orElse(false))) {
            if(request!=null)request.reason="source_absent";
            if(player.connection!=null)player.displayClientMessage(net.minecraft.network.chat.Component.translatable("filters.biome_absent"),true);
            return false;
        }
        if(request==null)com.chaosthedude.explorerscompass.unified.entertainment.AdventureTravel.interrupt(player,"entertainment.replaced");
        cancel(player,true);
        List<BlockPos> previous=request==null?biomeHistory.getOrDefault(new CacheKey(player.getUUID(),player.level().dimension().location(),key),List.of()):failed;
        var plan=window.plan()==null?BiomeQueryFactory.prepare(player.serverLevel(),origin,key,SearchConfig.biomeParameters()):window.plan();
        if(!plan.origin().equals(origin) || !plan.target().equals(key))throw new IllegalArgumentException("群系计划与本请求不匹配");
        SearchJob job=BiomeQueryFactory.create(plan,previous,window.radius(),Math.max(0,sampleLimit),
                SearchConfig.ASYNC_BIOMES.get(),request!=null,window.innerRadius());
        if(request!=null)request.queryPlan=plan;
        Session session=new Session(player,stack,key,job);session.startedNanos=before;session.startedAt=beforeMillis;session.queryPlan=plan;
        session.request=request;session.travel=request!=null;if(request!=null)request.attach(session);begin(session);
        long used=System.nanoTime()-before;job.recordRequestPreparation(used);pendingPreparationNanos+=used;
        return true;
    }
    /** 服务端唯一请求句柄：终态独立于空候选，取消必须匹配原会话。 */
    public static final class BiomeRequest {
        public enum State { RUNNING, FOUND, RANGE_EXHAUSTED, SAMPLE_LIMIT, REJECTED, ERROR, CANCELLED }
        private Session session;
        private State state=State.REJECTED;
        private String reason="rejected";
        private SearchCandidate candidate;
        private BiomeQueryFactory.Plan queryPlan;
        private final ProgressWatchdog watchdog=new ProgressWatchdog(System.nanoTime());
        private void attach(Session value){session=value;queryPlan=value.queryPlan;state=State.RUNNING;reason="";}
        public UUID token(){return session==null?null:session.token;}
        public State state(){
            // 先消费已经完成的结果，不能把恰好越过40秒的终态当作运行超时。
            if(state==State.RUNNING && session.job.done() && sessions.get(session.player.getUUID())==session)finish(session);
            return state;
        }
        public SearchCandidate candidate(){return candidate;}
        public BiomeQueryFactory.Plan queryPlan(){return queryPlan;}
        public String reason(){return reason;}
        public boolean stalled(){
            if(state()!=State.RUNNING)return false;
            return watchdog.stalled(samples(),session.job.confirmations+session.job.chunkLoads,System.nanoTime());
        }
        public long elapsed(){return session==null?0:System.nanoTime()-session.startedNanos;}
        public long samples(){return session==null?0:session.priorSamples+session.job.samples();}
        public CompoundTag failureDetails(){return session==null?new CompoundTag():session.failureDetails.isEmpty()?SearchFailureDetails.snapshot(session.level,session.job,session.requested,session.exceptions):session.failureDetails.copy();}
        public BlockPos origin(){return session==null?null:session.job.origin();}
        public String plan(){return session==null?reason:session.job.origin()+";source="+session.level.getChunkSource().getGenerator().getBiomeSource().getClass().getName()
                +";mode="+session.job.mode()+";radius="+session.job.radius()+";"+(session.job instanceof BiomeSearch biome?biome.samplingPlan():"");}
        public void cancel(String why){if(state()==State.RUNNING && sessions.get(session.player.getUUID())==session)SearchService.cancel(session.player,true,why);}
        public static boolean expired(State state,long elapsed){return state==State.RUNNING && elapsed>40_000_000_000L;}
    }
    private static boolean held(ServerPlayer player, ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == ExplorersCompass.explorersCompass
                && (player.getMainHandItem() == stack || player.getOffhandItem() == stack);
    }
    private static void begin(Session session) {
        SearchReplacement.replace(ExplorersCompass.MODID,session.player);
        CompoundTag tag = session.stack.getOrCreateTag();
        tag.remove(KIND); tag.remove(BIOME_KEY); tag.remove(FOUND_Y); tag.remove(TARGET_DIMENSION); tag.remove("ECUnifiedFeatureExpanded");
        if (session.job instanceof EndDungeonSearch || session.job instanceof GoldenCrateSearch) tag.putString(KIND,"feature");
        tag.putString(TARGET_DIMENSION, session.level.dimension().location().toString());
        if (session.job.biome()) {
            tag.putString(KIND, "biome"); tag.putString(BIOME_KEY, session.category.toString());
            tag.putString(TARGET_DIMENSION, session.level.dimension().location().toString());
        }
        tag.putUUID(TOKEN, session.token);
        if (!session.job.biome()) {
            tag.putString("ECFiltersStructureCategory", session.category.toString());
            tag.putBoolean("ECFiltersStructureGroup", session.group);
            tag.putBoolean("IsGroup", session.group);
            if (!session.next) { tag.putInt("ECFiltersNextSearches", 0); tag.remove("PrevPos"); }
            else if (!tag.contains("ECFiltersNextSearches"))
                tag.putInt("ECFiltersNextSearches", Math.max(0, tag.getList("PrevPos", net.minecraft.nbt.Tag.TAG_COMPOUND).size() - 1));
        }
        ExplorersCompass.explorersCompass.setSearching(session.stack, session.category, session.player);
        ExplorersCompass.explorersCompass.setSearchRadius(session.stack, 0, session.player);
        sessions.put(session.player.getUUID(), session);
        status(session);lifecycle(session,false,"searching","");
        session.job.prime();
    }

    /** 开发验收工具保留此入口；正式服务器 tick 由装配层驱动。 */
    public static void tick() { SearchEvents.runSearchTick(); }

    static long takePreparationTime() {
        long used=pendingPreparationNanos; pendingPreparationNanos=0; return used;
    }

    static BudgetedSearchScheduler.Lane searchLane(long preparation, long budget) {
        List<Session> round = new ArrayList<>();
        for(Session session:sessions.values()) {
            // 新结构任务保留一次核对的余量；较重的准备后首轮核对留到下一 tick。
            // 已运行任务仍参加本轮轮转，纯群系结果不需要区块核对。
            if(!session.fresh || preparation<=budget/4 || session.job.biome())round.add(session);
            session.fresh=false;
        }
        return new BudgetedSearchScheduler.Lane(round.size(), ignored -> stepSearch(round));
    }

    private static boolean stepSearch(List<Session> round) {
        if (round.isEmpty()) return false;
        Session session=round.get(Math.floorMod(cursor++,round.size()));
        if (sessions.get(session.player.getUUID())!=session) return false;
        if (!session.valid()) { cancel(session.player,true); return true; }
        try {
            boolean progress=session.job.step();
            if (session.job.done()) { finish(session); return true; }
            return progress;
        } catch (Exception error) {
                session.exceptions=com.chaosthedude.explorerscompass.unified.data.FailureReportText.clean(session.exceptions+com.chaosthedude.explorerscompass.unified.data.FailureReportText.exception(error),6000);
                session.job.cancel();
                if (session.job.asynchronous()) {
                    CompassUnified.LOGGER.warn("搜索 {} 后台预测失败，切换本次请求为兼容验证", session.token, error);
                    session.priorSamples+=session.job.samples();session.priorCandidates+=session.job.candidates();session.priorMain+=session.job.mainNanos;
                    session.priorChecks+=session.job.confirmations;session.priorChunks+=session.job.chunkLoads;
                    session.priorWorker+=session.job.workerNanos();session.priorPreparation+=session.job.preparationNanos();
                    session.priorConfirmation+=session.job.confirmationNanos();session.priorWaiting+=session.job.waitingNanos();
                    session.job = session.job.synchronousFallback().asFallback();
                } else {
                    CompassUnified.LOGGER.error("搜索 {} 兼容路径失败", session.token, error);
                    fail(session);status(session);lifecycle(session,true,"error",""); sessions.remove(session.player.getUUID());complete(session,null,"error");
                }
            return false;
        }
    }

    static void publishTickProgress() {
        for (Session session : sessions.values()) if (++session.ticks % 2 == 0
                && (session.job.samples() != session.lastSamples || !session.job.stage().equals(session.lastStage))) status(session);
    }
    private static void finish(Session session) {
        if (!session.valid()) { cancel(session.player, true); return; }
        SearchCandidate candidate = session.job.result();
        if (candidate == null) fail(session);
        else {
            var compass = ExplorersCompass.explorersCompass;
            // 所有完成写入都在主线程；只结束本玩家会话，不影响其他玩家。
            compass.setFound(session.stack, candidate.key(), candidate.position().getX(), candidate.position().getZ(), session.job.legacySamples());
            compass.setDisplayCoordinates(session.stack, ConfigHandler.GENERAL.displayCoordinates.get());
            session.stack.getOrCreateTag().putInt(FOUND_Y, candidate.position().getY());
            session.stack.getOrCreateTag().putString(TARGET_DIMENSION, session.level.dimension().location().toString());
            if (session.job.biome()) {
                session.stack.getOrCreateTag().putInt(FOUND_Y, candidate.position().getY());
                var key = new CacheKey(session.player.getUUID(), session.level.dimension().location(), session.category);
                if(!session.travel)biomeHistory.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate.position());
            } else {
                if (session.job instanceof EndDungeonSearch featureJob) {
                    session.stack.getOrCreateTag().putBoolean("ECUnifiedFeatureExpanded", featureJob.expanded());
                    featureHistory.computeIfAbsent(session.player.getUUID(), ignored -> new HashSet<>()).add(candidate.position().asLong());
                } else if (session.job instanceof GoldenCrateSearch) {
                    featureHistory.computeIfAbsent(session.player.getUUID(), ignored -> new HashSet<>()).add(candidate.position().asLong());
                } else structureHistory.computeIfAbsent(new StructureCacheKey(session.player.getUUID(), session.level.dimension().location()),
                        ignored -> new HashSet<>()).add(candidate.chunk().toLong());
                SearchRules.found(session.stack);
                var tag = session.stack.getOrCreateTag();
                var positions = tag.getList("PrevPos", net.minecraft.nbt.Tag.TAG_COMPOUND);
                var point = new CompoundTag();
                point.putInt("X", candidate.position().getX()); point.putInt("Y", candidate.position().getY()); point.putInt("Z", candidate.position().getZ());
                positions.add(point); tag.put("PrevPos", positions);
                if (session.next) tag.putInt("ECFiltersNextSearches", tag.getInt("ECFiltersNextSearches") + 1);
            }
        }
        status(session);
        lifecycle(session,true,candidate!=null?"found":session.job.stage(),candidate==null?"":candidate.key().toString());
        if (candidate != null) TeleportService.prepare(session.player, session.stack, session.token);
        CompassUnified.LOGGER.info("搜索 {} {}：{}，{} 次采样，{} 个候选，{} 次核对，{} 次区块请求，主线程 {} ms，总耗时 {} ms",
                session.token, session.job.biome() ? "群系" : "结构", candidate == null ? "未找到" : candidate.position(),
                session.job.samples(), session.job.candidates(), session.job.confirmations, session.job.chunkLoads,
                session.job.mainNanos / 1_000_000.0, (System.nanoTime() - session.job.startedNanos) / 1_000_000.0);
        if(!session.job.diagnostics().isEmpty())CompassUnified.LOGGER.info("搜索 {} 阶段摘要：{}",session.token,session.job.diagnostics());
        session.job.cancel(); sessions.remove(session.player.getUUID());
        complete(session,candidate,candidate!=null?"found":session.job.stage());
    }
    private static void complete(Session session,SearchCandidate result,String reason) {
        BiomeRequest request=session.request;
        if(request==null || request.state!=BiomeRequest.State.RUNNING)return;
        request.candidate=result;request.reason=reason;
        request.state=switch(reason) {
            case "found"->BiomeRequest.State.FOUND;
            case "range_exhausted"->BiomeRequest.State.RANGE_EXHAUSTED;
            case "sample_limit"->BiomeRequest.State.SAMPLE_LIMIT;
            case "error"->BiomeRequest.State.ERROR;
            default->BiomeRequest.State.CANCELLED;
        };
    }
    private static void fail(Session session) {
        ExplorersCompass.explorersCompass.setNotFound(session.stack, session.job.radius(), session.job.legacySamples());
    }
    private static void status(Session session) {
        session.lastSamples = session.job.samples();
        CompoundTag tag = session.stack.getOrCreateTag();
        tag.putInt("Samples", session.job.legacySamples());
        tag.putLong("ECFiltersSamples", session.lastSamples);
        tag.putLong("ECFiltersSampleLimit", session.job.sampleLimit());
        tag.putString("ECFiltersSearchMode", session.job.mode());
        session.lastStage = session.job.stage();
        tag.putString("ECFiltersSearchStage", session.lastStage);
        if (ExplorersCompass.explorersCompass.getState(session.stack) == CompassState.SEARCHING)
            ExplorersCompass.explorersCompass.setSearchRadius(session.stack, session.job.coveredRadius(), session.player);
        UnifiedNetwork.send(session.player, new SearchStatusPacket(session.token, session.stack.getOrCreateTag().copy()));
    }
    private static void lifecycle(Session session,boolean terminal,String result,String found) {
        if(session.terminalSent)return;
        var data=new CompoundTag();
        data.putUUID("player",session.player.getUUID());data.putLong("startedAt",session.startedAt);
        data.putString("category",session.category.toString());data.putString("kind",session.job.biome()?"biome":"structure");
        data.putString("dimension",session.level.dimension().location().toString());
        if (session.job instanceof EndDungeonSearch || session.job instanceof GoldenCrateSearch) data.putString("targetImplementation","feature");
        data.putString("requestKind",session.travel?"entertainment":session.next?"next":session.group?"group":"normal");
        data.putIntArray("origin",new int[]{session.job.origin().getX(),session.job.origin().getY(),session.job.origin().getZ()});
        var targets=new net.minecraft.nbt.ListTag();for(var key:session.requested)targets.add(net.minecraft.nbt.StringTag.valueOf(key.toString()));
        data.put("targets",targets);data.putString("mode",session.job.mode());
        if(terminal) {
            session.terminalSent=true;data.putString("result",result);data.putString("foundId",found);
            if(com.chaosthedude.explorerscompass.unified.data.FailureReportText.reportable(result)) {
                session.failureDetails=SearchFailureDetails.snapshot(session.level,session.job,session.requested,session.exceptions);
                if(!session.travel)data.put("failureReport",session.failureDetails.copy());
            }
            if (session.job.result()!=null) {
                var p=session.job.result().position();data.putIntArray("foundPos",new int[]{p.getX(),p.getY(),p.getZ()});
            }
            data.putLong("durationNanos",Math.max(0,System.nanoTime()-session.startedNanos));
            data.putLong("samples",session.priorSamples+session.job.samples());data.putLong("candidates",session.priorCandidates+session.job.candidates());
            data.putLong("mainNanos",session.priorMain+session.job.mainNanos);
            data.putLong("workerNanos",session.priorWorker+session.job.workerNanos());
            data.putLong("preparationNanos",session.priorPreparation+session.job.preparationNanos());
            data.putLong("confirmationNanos",session.priorConfirmation+session.job.confirmationNanos());
            data.putLong("waitingNanos",session.priorWaiting+session.job.waitingNanos());
            data.putInt("checks",session.priorChecks+session.job.confirmations);data.putInt("chunkRequests",session.priorChunks+session.job.chunkLoads);
        }
        UnifiedNetwork.send(session.player,new com.chaosthedude.explorerscompass.unified.network.SearchLifecyclePacket(session.token,terminal,data));
    }
    public static void cancel(ServerPlayer player,boolean inactive){cancel(player,inactive,"cancelled");}
    private static void cancel(ServerPlayer player, boolean inactive,String reason) {
        TeleportService.cancel(player.getUUID());
        Session session = sessions.remove(player.getUUID());
        if (session == null) return;
        lifecycle(session,true,"cancelled","");
        session.job.cancel();
        if (inactive && session.stack.getTag() != null && session.stack.getTag().hasUUID(TOKEN)
                && session.token.equals(session.stack.getTag().getUUID(TOKEN))) {
            ExplorersCompass.explorersCompass.setInactive(session.stack, player); status(session);
        }
        complete(session,null,reason);
    }
    public static void clearCache(UUID player) {
        featureHistory.remove(player);
        biomeHistory.keySet().removeIf(key -> key.player().equals(player));
        structureHistory.keySet().removeIf(key -> key.player().equals(player));
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        var entity = server == null ? null : server.getPlayerList().getPlayer(player);
        if (entity != null) for (var item : java.util.stream.Stream.concat(entity.getInventory().items.stream(), entity.getInventory().offhand.stream()).toList()) if (item.getItem() == ExplorersCompass.explorersCompass) {
            item.getOrCreateTag().remove("PrevPos"); item.getOrCreateTag().putInt("ECFiltersNextSearches", 0);
        }
    }
    public static void stopAll() {
        clearProduct();TeleportService.clear();BiomeGenerationContext.clearSharedState();
    }
    static void clearProduct() {
        pendingPreparationNanos=0;
        for (Session session : List.copyOf(sessions.values())) cancel(session.player, true);
        WorldgenCache.clear(); EndDungeonAdapter.clear(); EndDungeonIndex.clearRuntime(); GoldenCrateAdapter.clear(); GoldenCrateIndex.clearRuntime();
    }
    public static void shutdown() { clearProduct(); biomeHistory.clear(); structureHistory.clear(); featureHistory.clear(); TeleportEvents.clear(); cursor = 0; }
    public static boolean isBiome(ItemStack stack) { return stack.getTag() != null && "biome".equals(stack.getTag().getString(KIND)); }
    public static UUID token(ItemStack stack) { return stack.getTag() != null && stack.getTag().hasUUID(TOKEN) ? stack.getTag().getUUID(TOKEN) : null; }
    public static boolean owns(ServerPlayer player, ItemStack stack) { var session = sessions.get(player.getUUID()); return session != null && session.stack == stack && session.valid(); }
    public static int activeCount() { return sessions.size(); }
}
