package com.chaosthedude.explorerscompass.unified.entertainment;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork;
import com.chaosthedude.explorerscompass.unified.search.*;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import java.util.*;
import net.minecraft.advancements.Advancement;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** 服务端旅行状态机：每玩家一个任务；回调只推进所属任务，绝不直接授予进度。 */
public final class AdventureTravel {
    private enum Phase { NEXT, SEARCH, LAND, LANDING, VISIT, RETURN, RETURNING }
    private static final Map<UUID, Task> tasks = new LinkedHashMap<>();
    private static long revision;
    private static final class Task {
        final UUID id = UUID.randomUUID();
        final ServerPlayer player;
        final ItemStack stack;
        final Advancement advancement;
        final List<AdventureRequirements.Visit> visits;
        final Vec3 origin;
        final float yaw, pitch;
        Phase phase = Phase.NEXT;
        AdventureRequirements.Visit current;
        int attempts, arrivedTick;
        long stationDeadline;
        String detail = "";
        boolean completed;
        Task(ServerPlayer player, ItemStack stack, Advancement advancement, List<AdventureRequirements.Visit> visits) {
            this.player=player;this.stack=stack;this.advancement=advancement;this.visits=visits;
            origin=player.position();yaw=player.getYRot();pitch=player.getXRot();
        }
        boolean valid() {
            return player.serverLevel().dimension().equals(Level.OVERWORLD) && TeleportService.allowedTravel(player,stack);
        }
        int count() { return (int)visits.stream().filter(v->AdventureRequirements.visited(player,advancement,v)).count(); }
    }
    private AdventureTravel() {}
    public static boolean active(UUID player) { return tasks.containsKey(player); }
    public static void start(ServerPlayer player) {
        if(active(player.getUUID())) {sync(player);return;}
        ItemStack stack=ItemUtils.getHeldItem(player,ExplorersCompass.explorersCompass);
        if(!player.serverLevel().dimension().equals(Level.OVERWORLD) || !TeleportService.allowedTravel(player,stack)) {
            message(player,"entertainment.unavailable");return;
        }
        try {
            Advancement advancement=AdventureRequirements.advancement(player);
            var visits=AdventureRequirements.read(advancement);
            if(player.getAdvancements().getOrStartProgress(advancement).isDone()) {message(player,"entertainment.already_done");return;}
            SearchService.cancel(player,true);
            Task task=new Task(player,stack,advancement,visits);
            tasks.put(player.getUUID(),task);publish(task,true);
        } catch(IllegalArgumentException error) {message(player,error.getMessage());}
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
        if(task.phase!=Phase.NEXT && task.phase!=Phase.RETURN && task.phase!=Phase.RETURNING
                && System.nanoTime()>=task.stationDeadline) {returnHome(task,"entertainment.station_timeout");return;}
        switch(task.phase) {
            case NEXT -> next(task);
            case LAND -> {
                task.phase=Phase.LANDING;
                if(!TeleportService.travel(task.player,task.stack,success->{
                    if(!owns(task,Phase.LANDING))return;
                    if(success) {task.phase=Phase.VISIT;task.arrivedTick=task.player.getServer().getTickCount();}
                    else retry(task);
                }))retry(task);
            }
            case VISIT -> {
                if(AdventureRequirements.visited(task.player,task.advancement,task.current)) {
                    task.current=null;task.attempts=0;task.phase=Phase.NEXT;
                } else if(task.player.getServer().getTickCount()-task.arrivedTick>=40)retry(task);
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
        if(task.player.getAdvancements().getOrStartProgress(task.advancement).isDone()) {
            task.completed=true;returnHome(task,"entertainment.completed");return;
        }
        if(task.current==null) {
            task.current=task.visits.stream().filter(v->!AdventureRequirements.visited(task.player,task.advancement,v)).findFirst().orElse(null);
            task.stationDeadline=System.nanoTime()+60_000_000_000L;
        }
        if(task.current==null) {returnHome(task,"entertainment.unsupported_advancement");return;}
        if(System.nanoTime()>=task.stationDeadline) {returnHome(task,"entertainment.station_timeout");return;}
        task.attempts++;task.phase=Phase.SEARCH;
        boolean accepted=SearchService.startTravelBiome(task.player,task.stack,task.current.biome(),candidate->{
            if(!owns(task,Phase.SEARCH))return;
            if(candidate==null)returnHome(task,"entertainment.not_found");else task.phase=Phase.LAND;
        });
        if(!accepted)returnHome(task,"entertainment.not_found");
    }
    private static boolean owns(Task task,Phase phase) { return tasks.get(task.player.getUUID())==task && task.phase==phase; }
    private static void retry(Task task) {
        if(task.attempts>=3)returnHome(task,"entertainment.no_landing");else task.phase=Phase.NEXT;
    }
    private static void returnHome(Task task,String reason) {
        task.detail=reason;task.phase=Phase.RETURN;
        SearchService.cancel(task.player,true);TeleportService.cancel(task.player.getUUID());publish(task,true);
    }
    private static void finish(Task task,boolean returned) {
        if(!tasks.remove(task.player.getUUID(),task))return;
        if(!returned)task.detail="entertainment.return_failed";
        publish(task,false);message(task.player,task.detail);
    }
    private static void publish(Task task,boolean active) {
        String phase=active?task.phase.name().toLowerCase(java.util.Locale.ROOT):task.completed?"completed":"stopped";
        UnifiedNetwork.send(task.player,new EntertainmentStatus(task.id,active,task.count(),task.visits.size(),
                task.current==null?null:task.current.biome(),phase,task.detail,++revision));
    }
    private static void message(ServerPlayer player,String key) {
        if(player.connection!=null)player.displayClientMessage(Component.translatable(key),false);
    }
}
