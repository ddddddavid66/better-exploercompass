package com.david.compass.runtime.game.search;

import com.david.compass.runtime.game.data.AtomicFiles;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

/** 公共核心是唯一的服务器事件入口，无任何产品注册或客户端依赖。 */
@Mod(RuntimeMod.ID)
@Mod.EventBusSubscriber(modid=RuntimeMod.ID)
public final class RuntimeMod {
    public static final String ID="compass_runtime";
    private static final String FILE="compass-runtime-common.toml";
    private static final ForgeConfigSpec.Builder BUILDER=new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.IntValue THREADS=BUILDER.defineInRange("searchThreads",0,0,16);
    public static final ForgeConfigSpec.IntValue TICK_MILLIS=BUILDER.defineInRange("maxSearchTimePerTick",2,1,50);
    private static final ForgeConfigSpec CONFIG=BUILDER.build();
    public RuntimeMod(){
        migrate(FMLPaths.CONFIGDIR.get());
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON,CONFIG,FILE);
        SearchPool.configure(()->{
            int count=THREADS.get();return count==0?Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()/2)):count;
        });
        ServerSearchRuntime.configure(TICK_MILLIS::get);
    }
    private static void migrate(Path directory){
        Path source=directory.resolve("explorerscompass-common.toml"),target=directory.resolve(FILE);
        if(Files.exists(target) || !Files.isRegularFile(source))return;
        try(var reader=Files.newBufferedReader(source,StandardCharsets.UTF_8)){
            var previous=new TomlParser().parse(reader);var settings=Config.inMemory();
            for(String key:new String[]{"searchThreads","maxSearchTimePerTick"}){
                Object value=previous.get("Search."+key);if(value instanceof Number)settings.set(key,value);
            }
            if(!settings.isEmpty())AtomicFiles.write(target,new TomlWriter().writeToString(settings).getBytes(StandardCharsets.UTF_8));
        }catch(Exception error){org.apache.logging.log4j.LogManager.getLogger(ID).warn("无法迁移公共搜索配置",error);}
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){
        if(event.phase==TickEvent.Phase.END)ServerSearchRuntime.tick(event.getServer());
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){
        if(event.getEntity() instanceof ServerPlayer player)ServerSearchRuntime.leave(player,true);
    }
    @SubscribeEvent public static void dimension(PlayerEvent.PlayerChangedDimensionEvent event){
        if(event.getEntity() instanceof ServerPlayer player)ServerSearchRuntime.leave(player,false);
    }
    @SubscribeEvent public static void reload(OnDatapackSyncEvent event){
        if(event.getPlayer()==null)ServerSearchRuntime.invalidate(event.getPlayerList().getServer());
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event){ServerSearchRuntime.stop();}
}
