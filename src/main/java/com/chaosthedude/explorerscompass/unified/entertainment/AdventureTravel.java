package com.chaosthedude.explorerscompass.unified.entertainment;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork;
import com.chaosthedude.explorerscompass.unified.network.CatalogService;
import net.minecraft.resources.ResourceLocation;
import com.chaosthedude.explorerscompass.unified.search.*;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import java.util.*;
import net.minecraft.advancements.Advancement;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** 服务端旅行状态机：每玩家一个任务；回调只推进所属任务，绝不直接授予进度。 */
public final class AdventureTravel {
    private enum Phase { NEXT, SEARCH, LAND, LANDING, VISIT, RETURN, RETURNING }
    private static final Map<UUID, Task> tasks = new LinkedHashMap<>();
    public static final int MAX_ATTEMPTS=5;
    private static long revision;
    private static final class Task {
        final UUID id = UUID.randomUUID();
        final ServerPlayer player;
        final ItemStack stack;
        final Advancement advancement;
        final EntertainmentFeature feature;
        final Set<ResourceLocation> observed=new HashSet<>();
        final List<AdventureRequirements.Visit> visits;
        final Vec3 origin;
        final float yaw, pitch;
        Phase phase = Phase.NEXT;
        AdventureRequirements.Visit current;
        int attempts, visitTicks;
        long searchDeadline, visitDeadline, attemptRevision;
        String detail = "";
        boolean completed,awaitedTarget;
        ResourceLocation actualBiome;
        Task(ServerPlayer player, ItemStack stack, Advancement advancement, List<AdventureRequirements.Visit> visits,EntertainmentFeature feature) {
            this.player=player;this.stack=stack;this.advancement=advancement;this.visits=visits;this.feature=feature;
            observe();
            origin=player.position();yaw=player.getYRot();pitch=player.getXRot();
        }
        boolean valid() {
            return player.serverLevel().dimension().location().equals(feature.dimension()) && TeleportService.allowedTravel(player,stack);
        }
        void observe() {
            actualBiome=TeleportService.loadedBiome(player);
            if(actualBiome!=null && feature==EntertainmentFeature.AETHER_BIOMES)observed.add(actualBiome);
        }
        boolean visited(AdventureRequirements.Visit visit) {
            return advancement==null?observed.contains(visit.biome()):AdventureRequirements.visited(player,advancement,visit);
        }
        int count() { return (int)visits.stream().filter(this::visited).count(); }
        boolean done() { return advancement==null?count()==visits.size():player.getAdvancements().getOrStartProgress(advancement).isDone(); }
    }
    private AdventureTravel() {}
    public static boolean active(UUID player) { return tasks.containsKey(player); }
    public static void start(ServerPlayer player) {start(player,EntertainmentFeature.ADVENTURING_TIME);}
    public static void start(ServerPlayer player,EntertainmentFeature feature) {
        if(active(player.getUUID())) {sync(player);return;}
        ItemStack stack=ItemUtils.getHeldItem(player,ExplorersCompass.explorersCompass);
        if(!player.serverLevel().dimension().location().equals(feature.dimension()) || !TeleportService.allowedTravel(player,stack)) {
            message(player,!player.serverLevel().dimension().location().equals(feature.dimension())?feature.unavailableKey():"entertainment.unavailable");return;
        }
        try {
            Advancement advancement=feature==EntertainmentFeature.ADVENTURING_TIME?AdventureRequirements.advancement(player):null;
            if(feature==EntertainmentFeature.ADVENTURING_TIME && advancement==null)throw new IllegalArgumentException("entertainment.missing_advancement");
            var visits=advancement!=null?AdventureRequirements.read(advancement):aetherVisits(player);
            if(advancement!=null && player.getAdvancements().getOrStartProgress(advancement).isDone()) {message(player,"entertainment.already_done");return;}
            if(visits.isEmpty()) {message(player,"entertainment.no_biomes");return;}
            SearchService.cancel(player,true);
            Task task=new Task(player,stack,advancement,visits,feature);
            tasks.put(player.getUUID(),task);publish(task,true);
        } catch(IllegalArgumentException error) {message(player,error.getMessage());}
    }
    private static List<AdventureRequirements.Visit> aetherVisits(ServerPlayer player) {
        // 直接复用目录的实际维度群系集合，附属模组和数据包无需重复维护名单。
        var current=player.serverLevel().getBiome(player.blockPosition()).unwrapKey().map(key->key.location()).orElse(null);
        return CatalogService.snapshot(player.serverLevel(),UUID.randomUUID()).dimensionBiomes()
                .getOrDefault(EntertainmentFeature.AETHER_BIOMES.dimension(),Set.of()).stream()
                .sorted(Comparator.<ResourceLocation,Boolean>comparing(key->!key.equals(current)).thenComparing(Comparator.naturalOrder()))
                .map(key->new AdventureRequirements.Visit(key.toString(),key)).toList();
    }
    public static void stop(ServerPlayer player,UUID id) {
        Task task=tasks.get(player.getUUID());
        if(task==null || id==null || !task.id.equals(id) || task.phase==Phase.RETURN || task.phase==Phase.RETURNING)return;
        returnHome(task,"entertainment.stopped");
    }
    public static void sync(ServerPlayer player) {
        Task task=tasks.get(player.getUUID());if(task!=null)publish(task,true);
    }
    public static void interrupt(ServerPlayer player,String reason) {
        Task task=tasks.remove(player.getUUID());if(task==null)return;
        // 先移除拥有权，再取消子任务；旧回调因此无法启动下一站。
        SearchService.cancel(player,true);TeleportService.cancel(player.getUUID());
        task.detail=reason;publish(task,false);message(player,reason);
    }
    public static void clear() {
        for(Task task:List.copyOf(tasks.values()))interrupt(task.player,"entertainment.interrupted");
    }
    public static void tick() {
        for(Task task:List.copyOf(tasks.values())) {
            if(!task.valid()) {interrupt(task.player,"entertainment.interrupted");continue;}
            try {
                step(task);
                if(tasks.get(task.player.getUUID())==task && task.player.getServer().getTickCount()%10==0)publish(task,true);
            } catch(Exception error) {
                ExplorersCompass.LOGGER.error("自动群系旅行失败 {}",task.id,error);
                if(task.phase==Phase.RETURN || task.phase==Phase.RETURNING)finish(task,false);
                else returnHome(task,"entertainment.error");
            }
        }
    }
    private static void step(Task task) {
        task.observe();
        if(advanceVisited(task))return;
        // 已经实际进入目标时，等待原版条件自然更新；旧传送结果不能再次触发搜索。
        if(task.current!=null && !task.awaitedTarget && task.current.biome().equals(task.actualBiome)
                && task.phase!=Phase.VISIT && task.phase!=Phase.RETURN && task.phase!=Phase.RETURNING) {
            task.awaitedTarget=true;task.attemptRevision++;task.phase=Phase.VISIT;
            SearchService.cancel(task.player,true);beginVisit(task);return;
        }
        if(task.phase==Phase.SEARCH && System.nanoTime()>=task.searchDeadline) {
            task.attemptRevision++;task.phase=Phase.VISIT;
            SearchService.cancel(task.player,true);beginVisit(task);task.detail="entertainment.search_timeout";return;
        }
        switch(task.phase) {
            case NEXT -> next(task);
            case LAND -> {
                task.phase=Phase.LANDING;
                long attempt=task.attemptRevision;
                if(!TeleportService.travel(task.player,task.stack,result->{
                    if(!owns(task,Phase.LANDING,attempt))return;
                    beginVisit(task);
                    if(result!=TeleportService.TravelResult.MOVED)task.detail="entertainment.no_landing";
                }))beginVisit(task);
            }
            case VISIT -> {
                // 只有实际区块可读取的 tick 才计入自然进度等待，加载期间绝不提前重搜。
                if(task.actualBiome!=null)task.visitTicks++;
                boolean atTarget=task.current.biome().equals(task.actualBiome);
                if(atTarget)task.awaitedTarget=true;
                if(task.visitTicks>=(atTarget?200:20) || System.nanoTime()>=task.visitDeadline)retry(task);
            }
            case RETURN -> {
                task.phase=Phase.RETURNING;
                if(!TeleportService.returnTravel(task.player,task.stack,task.origin,task.yaw,task.pitch,
                        success->{if(owns(task,Phase.RETURNING))finish(task,success);}))finish(task,false);
            }
            default -> { }
        }
    }
    private static void next(Task task) {
        task.observe();if(advanceVisited(task))return;
        if(task.done()) {
            task.completed=true;returnHome(task,"entertainment.completed");return;
        }
        if(task.current==null) {
            task.current=task.visits.stream().filter(v->!task.visited(v)).findFirst().orElse(null);
        }
        if(task.current==null) {returnHome(task,"entertainment.unsupported_advancement");return;}
        if(task.attempts>=MAX_ATTEMPTS) {returnHome(task,"entertainment.no_landing");return;}
        task.attempts++;long attempt=++task.attemptRevision;task.detail="";task.awaitedTarget=false;
        task.phase=Phase.SEARCH;task.searchDeadline=System.nanoTime()+60_000_000_000L;
        ExplorersCompass.LOGGER.info("娱乐旅行 {} 目标={} attempt={}/5 search_start revision={}",task.id,task.current.biome(),task.attempts,attempt);
        boolean accepted=SearchService.startTravelBiome(task.player,task.stack,task.current.biome(),candidate->{
            if(!owns(task,Phase.SEARCH,attempt))return;
            task.observe();if(advanceVisited(task))return;
            if(candidate==null) {task.detail="entertainment.not_found";beginVisit(task);}else task.phase=Phase.LAND;
        });
        if(!accepted && owns(task,Phase.SEARCH,attempt)) {task.detail="entertainment.not_found";beginVisit(task);}
    }
    private static void beginVisit(Task task) {
        task.phase=Phase.VISIT;task.visitTicks=0;task.visitDeadline=System.nanoTime()+60_000_000_000L;
    }
    private static boolean advanceVisited(Task task) {
        if(task.current==null || task.phase==Phase.RETURN || task.phase==Phase.RETURNING || !task.visited(task.current))return false;
        ExplorersCompass.LOGGER.info("娱乐旅行 {} 目标={} visited actual={} attempts={}",task.id,task.current.biome(),task.actualBiome,task.attempts);
        // 先使回调失效再取消子任务；否则同步取消回调会重新推进已完成目标。
        task.attemptRevision++;task.current=null;task.attempts=0;task.detail="";task.awaitedTarget=false;task.phase=Phase.NEXT;
        SearchService.cancel(task.player,true);publish(task,true);return true;
    }
    private static boolean owns(Task task,Phase phase) { return tasks.get(task.player.getUUID())==task && task.phase==phase; }
    private static boolean owns(Task task,Phase phase,long attempt) {return owns(task,phase) && task.attemptRevision==attempt;}
    private static void retry(Task task) {
        task.observe();if(advanceVisited(task))return;
        task.attemptRevision++;task.phase=Phase.NEXT;SearchService.cancel(task.player,true);
        if(task.attempts>=MAX_ATTEMPTS)returnHome(task,task.detail.isEmpty()?"entertainment.no_landing":task.detail);
    }
    private static void returnHome(Task task,String reason) {
        task.attemptRevision++;task.detail=reason;task.phase=Phase.RETURN;
        SearchService.cancel(task.player,true);TeleportService.cancel(task.player.getUUID());publish(task,true);
    }
    private static void finish(Task task,boolean returned) {
        if(!tasks.remove(task.player.getUUID(),task))return;
        if(!returned)task.detail="entertainment.return_failed";
        publish(task,false);message(task.player,task.detail);
    }
    private static void publish(Task task,boolean active) {
        String phase=active?task.phase.name().toLowerCase(java.util.Locale.ROOT):task.completed?"completed":"stopped";
        if(active && task.phase==Phase.LANDING)phase=TeleportService.travelPhase(task.player.getUUID());
        UnifiedNetwork.send(task.player,new EntertainmentStatus(task.id,active,task.count(),task.visits.size(),
                task.current==null?null:task.current.biome(),phase,task.detail,++revision,task.feature,TeleportService.mode(task.player.getUUID()),task.attempts));
    }
    private static void message(ServerPlayer player,String key) {
        if(player.connection!=null)player.displayClientMessage(Component.translatable(key),false);
    }
}
