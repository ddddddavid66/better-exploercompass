package com.david.compass.runtime.game.search;

import com.david.compass.runtime.BudgetedSearchScheduler;
import com.david.compass.runtime.game.data.WorldDirectory;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.IntSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 单一服务器循环；参与者由各产品注册，核心不加载任何产品类。 */
public final class ServerSearchRuntime {
    public interface Participant {
        default long takePreparationTime(){return 0;}
        default void maintain(MinecraftServer server,long deadline){}
        default BudgetedSearchScheduler.Lane lane(long preparation,long budget){return null;}
        default boolean background(long deadline){return false;}
        default void afterTick(){}
        default void leave(ServerPlayer player,boolean logout){}
        default void clear(boolean stopping){}
        default void directoryInvalidated(MinecraftServer server){}
    }
    private static final Map<String,Participant> PRODUCTS=new ConcurrentSkipListMap<>();
    private static final BudgetedSearchScheduler SCHEDULER=new BudgetedSearchScheduler(System::nanoTime);
    private static volatile IntSupplier tickMillis=()->2;
    private ServerSearchRuntime(){}
    public static void configure(IntSupplier millis){tickMillis=Objects.requireNonNull(millis);}
    public static void register(String id,Participant participant){
        if(PRODUCTS.putIfAbsent(id,participant)!=null)throw new IllegalStateException("Duplicate runtime participant: "+id);
    }
    public static void tick(MinecraftServer server){
        long budget=tickMillis.getAsInt()*1_000_000L;
        long preparation=0;
        for(var product:PRODUCTS.values())preparation+=product.takePreparationTime();
        long deadline=SCHEDULER.deadline(budget,preparation);
        TeleportService.maintain();
        var lanes=new ArrayList<BudgetedSearchScheduler.Lane>(PRODUCTS.size()+1);
        lanes.add(new BudgetedSearchScheduler.Lane(TeleportService.foregroundCount(),d->TeleportService.step(d,true)));
        for(var product:PRODUCTS.values()) {
            product.maintain(server,deadline);
            var lane=product.lane(preparation,budget);
            if(lane!=null)lanes.add(lane);
        }
        SCHEDULER.run(deadline,lanes,ServerSearchRuntime::background);
        PRODUCTS.values().forEach(Participant::afterTick);
    }
    private static boolean background(long deadline){
        if(System.nanoTime()<deadline && VerificationSlots.queued()==0 && TeleportService.step(deadline,false))return true;
        for(var product:PRODUCTS.values())if(System.nanoTime()<deadline && product.background(deadline))return true;
        return false;
    }
    public static void leave(ServerPlayer player,boolean logout){
        for(var product:PRODUCTS.values())product.leave(player,logout);
        if(logout)TeleportService.forget(player.getUUID());else TeleportService.cancel(player.getUUID());
    }
    public static void invalidate(MinecraftServer server){
        clear(false);
        for(var product:PRODUCTS.values())product.directoryInvalidated(server);
    }
    public static void stop(){clear(true);}
    private static void clear(boolean stopping){
        for(var product:PRODUCTS.values())product.clear(stopping);
        if(stopping)TeleportService.shutdown();else TeleportService.clear();
        BiomeGenerationContext.clearSharedState();WorldDirectory.clear();
        if(stopping){SearchPool.shutdown();VerificationSlots.reset();}
    }
}
