package com.chaosthedude.explorerscompass.unified.entertainment;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.network.*;
import com.chaosthedude.explorerscompass.unified.search.*;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import java.util.*;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** 搜索终态、移动终态和实际到访独立推进；只有真正超时消耗恢复额度。 */
public final class AdventureTravel {
    private enum Phase { NEXT, SEARCH, LAND, LANDING, VISIT, RETURN, RETURNING }
    private static final Map<UUID,Task> tasks=new LinkedHashMap<>();
    public static final int MAX_TIMEOUTS=5,MAX_ATTEMPTS=MAX_TIMEOUTS;
    private static long revision;
    private static final class Task {
        final UUID id=UUID.randomUUID();
        final ServerPlayer player;
        final ItemStack stack;
        final Advancement advancement;
        final EntertainmentFeature feature;
        final List<AdventureRequirements.Visit> visits;
        final Set<ResourceLocation> deferred=new LinkedHashSet<>(),retried=new HashSet<>();
        final Map<ResourceLocation,String> excluded=new LinkedHashMap<>(),unresolved=new LinkedHashMap<>();
        final Map<ResourceLocation,Integer> timeouts=new HashMap<>();
        final Map<ResourceLocation,BlockPos> deferredStarts=new HashMap<>();
        final Map<ResourceLocation,Integer> deferredFailures=new HashMap<>();
        final Map<ResourceLocation,Set<BlockPos>> failed=new HashMap<>();
        final Vec3 origin;
        final float yaw,pitch;
        Phase phase=Phase.NEXT;
        AdventureRequirements.Visit current;
        SearchService.BiomeRequest request;
        SearchCandidate candidate;
        BlockPos anchor;
        BiomeQueryFactory.Plan queryPlan;
        ResourceLocation actual;
        long epoch,visitDeadline;
        int visitTicks,requests,expansion;
        long coveredSamples;
        final long startedAt=System.currentTimeMillis(),startedNanos=System.nanoTime();
        String travelException="";boolean reported;
        net.minecraft.nbt.CompoundTag lastFailure=new net.minecraft.nbt.CompoundTag();
        boolean completed,finalPass,awaited;
        String detail="";
        Task(ServerPlayer player,ItemStack stack,Advancement advancement,List<AdventureRequirements.Visit> visits,EntertainmentFeature feature) {
            this.player=player;this.stack=stack;this.advancement=advancement;this.visits=visits;this.feature=feature;
            origin=player.position();yaw=player.getYRot();pitch=player.getXRot();observe();
            excluded.putAll(AdventureRequirements.exclusions(player.serverLevel(),visits.stream().map(AdventureRequirements.Visit::biome).toList()));
        }
        boolean valid(){return player.serverLevel().dimension().location().equals(feature.dimension()) && TeleportService.allowedTravel(player,stack);}
        void observe(){actual=TeleportService.loadedBiome(player);}
        boolean visited(AdventureRequirements.Visit visit){return AdventureRequirements.visited(player,advancement,visit);}
        int count(){return (int)visits.stream().filter(this::visited).count();}
        int total(){return visits.size();}
        int timeoutCount(){return current==null?0:timeouts.getOrDefault(current.biome(),0);}
    }
    private AdventureTravel() {}
    public static boolean active(UUID player){return tasks.containsKey(player);}
    public static void start(ServerPlayer player){start(player,EntertainmentFeature.ADVENTURING_TIME);}
    public static void start(ServerPlayer player,EntertainmentFeature feature) {
        if(active(player.getUUID())){sync(player);return;}
        ItemStack stack=ItemUtils.getHeldItem(player,ExplorersCompass.explorersCompass);
        if(!player.serverLevel().dimension().location().equals(feature.dimension()) || !TeleportService.allowedTravel(player,stack)) {
            message(player,!player.serverLevel().dimension().location().equals(feature.dimension())?feature.unavailableKey():"entertainment.unavailable");return;
        }
        try {
            Advancement advancement=player.getServer().getAdvancements().getAdvancement(feature.advancement());
            if(advancement==null)throw new IllegalArgumentException("entertainment.missing_advancement");
            var visits=AdventureRequirements.read(advancement);
            if(player.getAdvancements().getOrStartProgress(advancement).isDone()){message(player,"entertainment.already_done");return;}
            if(visits.isEmpty()){message(player,"entertainment.no_biomes");return;}
            com.chaosthedude.explorerscompass.unified.search.SearchReplacement.replace(ExplorersCompass.MODID,player);
            SearchService.cancel(player,true);Task task=new Task(player,stack,advancement,visits,feature);
            tasks.put(player.getUUID(),task);
            publish(task,true);
        }catch(IllegalArgumentException error){message(player,error.getMessage());}
    }
    public static void stop(ServerPlayer player,UUID id) {
        Task task=tasks.get(player.getUUID());
        if(task!=null && task.id.equals(id) && task.phase!=Phase.RETURN && task.phase!=Phase.RETURNING)returnHome(task,"entertainment.stopped");
    }
    public static void sync(ServerPlayer player){Task t=tasks.get(player.getUUID());if(t!=null)publish(t,true);}
    public static void interrupt(ServerPlayer player,String reason) {
        Task task=tasks.remove(player.getUUID());if(task==null)return;
        task.epoch++;SearchService.cancel(player,true);task.detail=reason;publish(task,false);message(player,reason);
    }
    public static void clear(){for(Task task:List.copyOf(tasks.values()))interrupt(task.player,"entertainment.interrupted");}
    public static void tick() {
        for(Task task:List.copyOf(tasks.values())) {
            if(!task.valid()){interrupt(task.player,"entertainment.interrupted");continue;}
            try {
                step(task);
                if(tasks.get(task.player.getUUID())==task && task.player.getServer().getTickCount()%10==0)publish(task,true);
            }catch(Exception error) {
                task.travelException=com.chaosthedude.explorerscompass.unified.data.FailureReportText.exception(error);
                ExplorersCompass.LOGGER.error("自动旅行 {} 异常",task.id,error);
                if(task.phase==Phase.RETURN || task.phase==Phase.RETURNING)finish(task,false);else returnHome(task,"entertainment.error");
            }
        }
    }
    private static void step(Task task) {
        task.observe();if(advanceVisited(task))return;
        if(task.current!=null && !task.awaited && task.current.biome().equals(task.actual)
                && task.phase!=Phase.VISIT && task.phase!=Phase.RETURN && task.phase!=Phase.RETURNING) {
            task.awaited=true;task.epoch++;task.phase=Phase.VISIT;SearchService.cancel(task.player,true);beginVisit(task);return;
        }
        switch(task.phase) {
            case NEXT->next(task);
            case SEARCH->searchState(task);
            case LAND->land(task);
            case VISIT->{
                if(task.actual!=null)task.visitTicks++;
                boolean target=task.current.biome().equals(task.actual);if(target)task.awaited=true;
                if(task.actual==null && System.nanoTime()>=task.visitDeadline){if(timeout(task,"arrival_chunks"))task.phase=Phase.LAND;}
                else if(task.visitTicks>=(target?200:20) || System.nanoTime()>=task.visitDeadline)failedCandidate(task);
            }
            case RETURN->{
                task.phase=Phase.RETURNING;long epoch=++task.epoch;
                if(!TeleportService.returnTravel(task.player,task.stack,task.origin,task.yaw,task.pitch,
                        success->{if(owns(task,Phase.RETURNING,epoch))finish(task,success);}))finish(task,false);
            }
            default->{ }
        }
    }
    private static void next(Task task) {
        if(task.current==null) {
            task.current=task.visits.stream().filter(v->!task.visited(v) && !task.excluded.containsKey(v.biome())
                    && (task.finalPass?task.deferred.contains(v.biome()) && !task.retried.contains(v.biome()):!task.deferred.contains(v.biome())))
                    .findFirst().orElse(null);
            if(task.current==null && !task.finalPass){task.finalPass=true;next(task);return;}
            if(task.current==null) {
                task.completed=task.player.getAdvancements().getOrStartProgress(task.advancement).isDone();returnHome(task,task.completed?"entertainment.completed":"entertainment.partial");return;
            }
            if(task.finalPass) {
                var key=task.current.biome();task.retried.add(key);
                if(task.player.blockPosition().equals(task.deferredStarts.get(key))
                        && task.failed.getOrDefault(key,Set.of()).size()==task.deferredFailures.getOrDefault(key,0)) {
                    task.current=null;next(task);return;
                }
            }
            // 失败落地可能进入洞穴；同一目标保留原搜索高度，避免丢失薄层目标。
            task.anchor=task.player.blockPosition().immutable();
        }
        task.epoch++;task.detail="";task.awaited=false;task.phase=Phase.SEARCH;task.requests++;
        task.request=SearchService.startTravelBiome(task.player,task.stack,task.current.biome(),
                task.failed.getOrDefault(task.current.biome(),Set.of()),task.anchor,
                new SearchService.SearchWindow(TravelExpansion.radius(SearchConfig.RADIUS.get(),task.expansion),
                    task.expansion==0?-1:TravelExpansion.radius(SearchConfig.RADIUS.get(),task.expansion-1),
                    Math.max(0,TravelExpansion.quota(SearchConfig.BIOME_SAMPLES.get(),task.expansion)-task.coveredSamples),task.queryPlan));
        task.queryPlan=task.request.queryPlan();
        ExplorersCompass.LOGGER.info("TRAVEL {} search_start biome={} request={} timeout={}/5 token={} final={} plan={}",
                task.id,task.current.biome(),task.requests,task.timeoutCount(),task.request.token(),task.finalPass,task.request.plan());
    }
    private static void searchState(Task task) {
        var state=task.request.state();
        if(state==SearchService.BiomeRequest.State.RUNNING) {
            if(task.request.stalled()) {
                task.lastFailure=task.request.failureDetails();
                task.request.cancel("travel_timeout");
                // 取消前可能已完成，只有本请求真正按超时取消才消耗额度。
                if(task.request.state()==SearchService.BiomeRequest.State.CANCELLED && task.request.reason().equals("travel_timeout"))
                    if(timeout(task,"search"))task.phase=Phase.NEXT;
            }
            return;
        }
        ExplorersCompass.LOGGER.info("TRAVEL {} search_terminal biome={} state={} reason={} samples={} token={}",task.id,task.current.biome(),state,task.request.reason(),task.request.samples(),task.request.token());
        if(state!=SearchService.BiomeRequest.State.FOUND)task.lastFailure=task.request.failureDetails();
        switch(state) {
            case FOUND->{task.candidate=task.request.candidate();task.phase=Phase.LAND;}
            case RANGE_EXHAUSTED->{
                task.coveredSamples+=task.request.samples();
                if(task.expansion<2 && TravelExpansion.radius(SearchConfig.RADIUS.get(),task.expansion+1)>TravelExpansion.radius(SearchConfig.RADIUS.get(),task.expansion)) {
                    task.expansion++;task.detail="扩大搜索范围："+TravelExpansion.radius(SearchConfig.RADIUS.get(),task.expansion)+" 格";task.phase=Phase.NEXT;
                } else {task.unresolved.put(task.current.biome(),"RANGE_EXHAUSTED_4X");returnHome(task,"entertainment.range_failed");}
            }
            case SAMPLE_LIMIT,REJECTED,ERROR->{task.unresolved.put(task.current.biome(),state.name()+":"+task.request.reason());returnHome(task,"entertainment.search_failed");}
            case CANCELLED->returnHome(task,"entertainment.interrupted");
            default->{ }
        }
    }
    private static void land(Task task) {
        task.phase=Phase.LANDING;long epoch=++task.epoch;var key=task.current.biome();
        if(!TeleportService.travelDetailed(task.player,task.stack,outcome->{
            if(!owns(task,Phase.LANDING,epoch) || !task.current.biome().equals(key))return;
            task.observe();if(advanceVisited(task))return;
            var result=outcome.result();
            if(result==TeleportService.TravelResult.NO_POSITION && !outcome.provenUnusable().isEmpty())
                task.failed.computeIfAbsent(key,k->new LinkedHashSet<>()).addAll(outcome.provenUnusable().stream().filter(p->!p.equals(task.candidate.position())).toList());
            ExplorersCompass.LOGGER.info("TRAVEL {} teleport_terminal biome={} result={} actual={}",task.id,key,result,task.actual);
            switch(result) {
                case MOVED->beginVisit(task);
                case TIMED_OUT->{if(timeout(task,"chunks"))task.phase=Phase.LAND;}
                case BUDGET_EXHAUSTED->{task.detail="继续高度复核";task.phase=Phase.LAND;}
                case NO_POSITION->failedCandidate(task);
                case ERROR->defer(task,"TELEPORT_ERROR");
                case CANCELLED->returnHome(task,"entertainment.interrupted");
            }
        }))defer(task,"TELEPORT_REJECTED");
    }
    private static boolean timeout(Task task,String phase) {
        task.observe();if(advanceVisited(task))return false;
        int count=task.timeouts.merge(task.current.biome(),1,Integer::sum);
        ExplorersCompass.LOGGER.warn("TRAVEL {} timeout biome={} phase={} count={}/5",task.id,task.current.biome(),phase,count);
        if(count>=MAX_TIMEOUTS){returnHome(task,"entertainment.timeout_limit");return false;}return true;
    }
    private static void beginVisit(Task task){task.phase=Phase.VISIT;task.visitTicks=0;task.visitDeadline=System.nanoTime()+60_000_000_000L;}
    private static void failedCandidate(Task task) {
        task.observe();if(advanceVisited(task))return;
        if(task.candidate==null){defer(task,"NO_CANDIDATE");return;}
        var failed=task.failed.computeIfAbsent(task.current.biome(),key->new LinkedHashSet<>());
        if(!failed.add(task.candidate.position())){defer(task,"REPEATED_CANDIDATE");return;}
        task.expansion=0;task.coveredSamples=0;task.epoch++;task.phase=Phase.NEXT;SearchService.cancel(task.player,true);
    }
    private static void defer(Task task,String reason) {
        task.observe();if(advanceVisited(task))return;
        var key=task.current.biome();task.deferred.add(key);
        task.deferredStarts.put(key,task.request==null || task.request.origin()==null?task.player.blockPosition():task.request.origin());
        task.deferredFailures.put(key,task.failed.getOrDefault(key,Set.of()).size());
        task.unresolved.put(key,reason);
        if(task.request!=null && task.request.reason().equals("source_absent")){task.excluded.put(key,"effective_source_excludes");task.unresolved.remove(key);}
        ExplorersCompass.LOGGER.info("TRAVEL {} deferred biome={} reason={} final={} timeout={}",task.id,key,reason,task.finalPass,task.timeoutCount());
        task.epoch++;task.current=null;task.candidate=null;task.request=null;task.queryPlan=null;task.awaited=false;task.phase=Phase.NEXT;
        SearchService.cancel(task.player,true);
    }
    private static boolean advanceVisited(Task task) {
        if(task.current==null || task.phase==Phase.RETURN || task.phase==Phase.RETURNING || !task.visited(task.current))return false;
        ExplorersCompass.LOGGER.info("TRAVEL {} visited biome={} actual={} timeout={}",task.id,task.current.biome(),task.actual,task.timeoutCount());
        task.unresolved.remove(task.current.biome());task.expansion=0;task.coveredSamples=0;task.epoch++;task.current=null;task.candidate=null;task.request=null;task.queryPlan=null;
        task.awaited=false;task.detail="";task.phase=Phase.NEXT;SearchService.cancel(task.player,true);publish(task,true);return true;
    }
    private static boolean owns(Task task,Phase phase,long epoch){return tasks.get(task.player.getUUID())==task && task.phase==phase && task.epoch==epoch;}
    private static void returnHome(Task task,String reason) {
        if(!reason.equals("entertainment.stopped") && !reason.equals("entertainment.completed") && !reason.equals("entertainment.interrupted"))reportFailure(task,reason);
        task.epoch++;task.phase=Phase.RETURN;task.detail=reason;SearchService.cancel(task.player,true);publish(task,true);
    }
    /** 内部扩圈/重试只有最终失败才报告；返程和手动停止不产生虚假的搜索终态。 */
    private static void reportFailure(Task task,String reason) {
        if(task.reported)return;task.reported=true;
        var data=new net.minecraft.nbt.CompoundTag();var details=task.lastFailure.copy();
        var key=task.current==null?task.visits.stream().filter(v->!task.visited(v)).map(AdventureRequirements.Visit::biome).findFirst().orElse(task.feature.dimension()):task.current.biome();
        data.putUUID("player",task.player.getUUID());data.putBoolean("reportOnly",true);data.putLong("startedAt",task.startedAt);
        data.putString("category",key.toString());data.putString("kind","biome");data.putString("dimension",task.feature.dimension().toString());
        data.putString("requestKind","entertainment");data.putString("result","travel_failed");
        var targets=new net.minecraft.nbt.ListTag();for(var visit:task.visits)if(!task.visited(visit))targets.add(net.minecraft.nbt.StringTag.valueOf(visit.biome().toString()));data.put("targets",targets);
        for(String field:List.of("candidates","checks","chunkRequests","mainNanos","workerNanos","preparationNanos","confirmationNanos","waitingNanos"))if(details.contains(field))data.put(field,details.get(field).copy());
        data.putIntArray("origin",new int[]{(int)Math.floor(task.origin.x),(int)Math.floor(task.origin.y),(int)Math.floor(task.origin.z)});
        data.putLong("durationNanos",System.nanoTime()-task.startedNanos);
        if(task.request!=null)data.putLong("samples",task.request.samples());
        data.putString("mode",TeleportService.mode(task.player.getUUID()).name());
        details.putString("travelSummary",com.chaosthedude.explorerscompass.unified.data.FailureReportText.clean(
            "reason="+reason+"; requests="+task.requests+"; timeouts="+task.timeouts+"; unresolved="+task.unresolved+"; excluded="+task.excluded,6000));
        if(!task.travelException.isEmpty())details.putString("exceptions",task.travelException);
        details.putString("mod",net.minecraftforge.fml.ModList.get().getModContainerById("explorerscompass").orElseThrow().getModInfo().getVersion().toString());
        details.putString("minecraft",net.minecraft.SharedConstants.getCurrentVersion().getName());details.putString("forge",net.minecraftforge.versions.forge.ForgeVersion.getVersion());
        data.put("failureReport",details);UnifiedNetwork.send(task.player,new SearchLifecyclePacket(task.id,true,data));
    }
    private static void finish(Task task,boolean returned) {
        if(!tasks.remove(task.player.getUUID(),task))return;
        if(!returned)task.detail="entertainment.return_failed";
        publish(task,false);message(task.player,task.detail);
        for(var entry:task.excluded.entrySet())task.player.displayClientMessage(Component.translatable("entertainment.excluded",entry.getKey(),entry.getValue()),false);
        for(var visit:task.visits)if(!task.visited(visit) && !task.excluded.containsKey(visit.biome()))
            task.player.displayClientMessage(Component.translatable("entertainment.unresolved",visit.biome(),task.unresolved.getOrDefault(visit.biome(),"unfinished")),false);
        ExplorersCompass.LOGGER.info("TRAVEL {} finished visited={}/{} returned={} excluded={} unresolved={} timeouts={}",task.id,task.count(),task.total(),returned,task.excluded,task.unresolved,task.timeouts);
    }
    private static void publish(Task task,boolean active) {
        String phase=active?task.phase.name().toLowerCase(Locale.ROOT):task.completed?"completed":"stopped";
        if(active && task.phase==Phase.LANDING)phase=TeleportService.travelPhase(task.player.getUUID());
        UnifiedNetwork.send(task.player,new EntertainmentStatus(task.id,active,task.count(),task.total(),task.current==null?null:task.current.biome(),
                phase,task.detail,++revision,task.feature,TeleportService.mode(task.player.getUUID()),task.timeoutCount()));
    }
    private static void message(ServerPlayer player,String key){if(player.connection!=null)player.displayClientMessage(Component.translatable(key),false);}
}
