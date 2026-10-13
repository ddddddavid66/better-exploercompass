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
        final Set<ResourceLocation> observed=new HashSet<>(),deferred=new LinkedHashSet<>(),retried=new HashSet<>();
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
        ResourceLocation actual;
        long epoch,visitDeadline;
        int visitTicks,requests;
        boolean completed,finalPass,awaited;
        String detail="";
        Task(ServerPlayer player,ItemStack stack,Advancement advancement,List<AdventureRequirements.Visit> visits,EntertainmentFeature feature) {
            this.player=player;this.stack=stack;this.advancement=advancement;this.visits=visits;this.feature=feature;
            origin=player.position();yaw=player.getYRot();pitch=player.getXRot();observe();
            excluded.putAll(BiomeAvailability.exclusions(player.serverLevel(),visits.stream().map(AdventureRequirements.Visit::biome).toList()));
        }
        boolean valid(){return player.serverLevel().dimension().location().equals(feature.dimension()) && TeleportService.allowedTravel(player,stack);}
        void observe(){actual=TeleportService.loadedBiome(player);if(actual!=null && advancement==null)observed.add(actual);}
        boolean visited(AdventureRequirements.Visit visit){return advancement==null?observed.contains(visit.biome()):AdventureRequirements.visited(player,advancement,visit);}
        int count(){return (int)visits.stream().filter(this::visited).count();}
        int total(){return (int)visits.stream().filter(v->!excluded.containsKey(v.biome())).count();}
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
            Advancement advancement=feature==EntertainmentFeature.ADVENTURING_TIME?AdventureRequirements.advancement(player):null;
            if(feature==EntertainmentFeature.ADVENTURING_TIME && advancement==null)throw new IllegalArgumentException("entertainment.missing_advancement");
            var visits=advancement!=null?AdventureRequirements.read(advancement):aetherVisits(player);
            if(advancement!=null && player.getAdvancements().getOrStartProgress(advancement).isDone()){message(player,"entertainment.already_done");return;}
            if(visits.isEmpty()){message(player,"entertainment.no_biomes");return;}
            SearchService.cancel(player,true);Task task=new Task(player,stack,advancement,visits,feature);
            tasks.put(player.getUUID(),task);
            if(feature==EntertainmentFeature.AETHER_BIOMES)for(String line:BiomeAvailability.describe(player.serverLevel()))ExplorersCompass.LOGGER.info("Aether availability {}",line);
            publish(task,true);
        }catch(IllegalArgumentException error){message(player,error.getMessage());}
    }
    private static List<AdventureRequirements.Visit> aetherVisits(ServerPlayer player) {
        var current=TeleportService.loadedBiome(player);
        return CatalogService.snapshot(player.serverLevel(),UUID.randomUUID()).dimensionBiomes()
                .getOrDefault(EntertainmentFeature.AETHER_BIOMES.dimension(),Set.of()).stream()
                .sorted(Comparator.<ResourceLocation,Boolean>comparing(key->!key.equals(current)).thenComparing(Comparator.naturalOrder()))
                .map(key->new AdventureRequirements.Visit(key.toString(),key)).toList();
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
                task.completed=task.count()==task.total();returnHome(task,task.completed?"entertainment.completed":"entertainment.partial");return;
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
                task.failed.getOrDefault(task.current.biome(),Set.of()),task.anchor);
        ExplorersCompass.LOGGER.info("TRAVEL {} search_start biome={} request={} timeout={}/5 token={} final={} plan={}",
                task.id,task.current.biome(),task.requests,task.timeoutCount(),task.request.token(),task.finalPass,task.request.plan());
    }
    private static void searchState(Task task) {
        var state=task.request.state();
        if(state==SearchService.BiomeRequest.State.RUNNING) {
            if(SearchService.BiomeRequest.expired(state,task.request.elapsed())) {
                task.request.cancel("travel_timeout");
                // 取消前可能已完成，只有本请求真正按超时取消才消耗额度。
                if(task.request.state()==SearchService.BiomeRequest.State.CANCELLED && task.request.reason().equals("travel_timeout"))
                    if(timeout(task,"search"))task.phase=Phase.NEXT;
            }
            return;
        }
        ExplorersCompass.LOGGER.info("TRAVEL {} search_terminal biome={} state={} reason={} samples={} token={}",task.id,task.current.biome(),state,task.request.reason(),task.request.samples(),task.request.token());
        switch(state) {
            case FOUND->{task.candidate=task.request.candidate();task.phase=Phase.LAND;}
            case RANGE_EXHAUSTED,SAMPLE_LIMIT,REJECTED,ERROR->defer(task,state.name());
            case CANCELLED->returnHome(task,"entertainment.interrupted");
            default->{ }
        }
    }
    private static void land(Task task) {
        task.phase=Phase.LANDING;long epoch=++task.epoch;var key=task.current.biome();
        if(!TeleportService.travel(task.player,task.stack,result->{
            if(!owns(task,Phase.LANDING,epoch) || !task.current.biome().equals(key))return;
            task.observe();if(advanceVisited(task))return;
            ExplorersCompass.LOGGER.info("TRAVEL {} teleport_terminal biome={} result={} actual={}",task.id,key,result,task.actual);
            switch(result) {
                case MOVED->beginVisit(task);
                case TIMED_OUT->{if(timeout(task,"chunks"))task.phase=Phase.LAND;}
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
        task.epoch++;task.phase=Phase.NEXT;SearchService.cancel(task.player,true);
    }
    private static void defer(Task task,String reason) {
        task.observe();if(advanceVisited(task))return;
        var key=task.current.biome();task.deferred.add(key);
        task.deferredStarts.put(key,task.request==null || task.request.origin()==null?task.player.blockPosition():task.request.origin());
        task.deferredFailures.put(key,task.failed.getOrDefault(key,Set.of()).size());
        task.unresolved.put(key,reason);
        if(task.request!=null && task.request.reason().equals("source_absent")){task.excluded.put(key,"effective_source_excludes");task.unresolved.remove(key);}
        ExplorersCompass.LOGGER.info("TRAVEL {} deferred biome={} reason={} final={} timeout={}",task.id,key,reason,task.finalPass,task.timeoutCount());
        task.epoch++;task.current=null;task.candidate=null;task.request=null;task.awaited=false;task.phase=Phase.NEXT;
        SearchService.cancel(task.player,true);
    }
    private static boolean advanceVisited(Task task) {
        if(task.current==null || task.phase==Phase.RETURN || task.phase==Phase.RETURNING || !task.visited(task.current))return false;
        ExplorersCompass.LOGGER.info("TRAVEL {} visited biome={} actual={} timeout={}",task.id,task.current.biome(),task.actual,task.timeoutCount());
        task.unresolved.remove(task.current.biome());task.epoch++;task.current=null;task.candidate=null;task.request=null;
        task.awaited=false;task.detail="";task.phase=Phase.NEXT;SearchService.cancel(task.player,true);publish(task,true);return true;
    }
    private static boolean owns(Task task,Phase phase,long epoch){return tasks.get(task.player.getUUID())==task && task.phase==phase && task.epoch==epoch;}
    private static void returnHome(Task task,String reason) {
        task.epoch++;task.phase=Phase.RETURN;task.detail=reason;SearchService.cancel(task.player,true);publish(task,true);
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
