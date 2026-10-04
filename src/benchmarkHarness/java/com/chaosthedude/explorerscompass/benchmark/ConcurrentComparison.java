package com.chaosthedude.explorerscompass.benchmark;

import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** 四位玩家的正常请求共享真实服务端调度；不手动推进任何搜索任务。 */
@Mod.EventBusSubscriber(modid="compassbench")
public final class ConcurrentComparison {
    private static final String[] TARGETS={"minecraft:village_plains","minecraft:mansion","minecraft:plains","minecraft:lush_caves"};
    private static MinecraftServer server;
    private static final ServerPlayer[] players=new ServerPlayer[4];
    private static final ItemStack[] stacks=new ItemStack[4];
    private static final EngineAccess[] engines=new EngineAccess[4];
    private static final long[] starts=new long[4], ends=new long[4];
    private static boolean pending;
    private static int settling=60;
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if(!Boolean.getBoolean("compassbench.concurrent"))return;
        server=event.getServer();
        try {
            for(int i=0;i<4;i++) {
                players[i]=FakePlayerFactory.get(server.overworld(),new GameProfile(UUID.randomUUID(),"CompassBench"+i));
                players[i].setGameMode(GameType.SURVIVAL);players[i].setPos(0,64,0);
                stacks[i]=new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("explorerscompass:explorerscompass")));
                players[i].setItemInHand(InteractionHand.MAIN_HAND,stacks[i]);engines[i]=new EngineAccess();engines[i].configure();
            }
            pending=true;
        }catch(Throwable error){finish(error);}
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void start(TickEvent.ServerTickEvent event) {
        if(server==null||!pending||event.phase!=TickEvent.Phase.START)return;
        if(settling>0){settling--;return;}
        pending=false;
        try {
            for(int i=0;i<4;i++) {
                starts[i]=System.nanoTime();
                if(!engines[i].start(players[i],stacks[i],new ResourceLocation(TARGETS[i]),i>=2))
                    throw new IllegalStateException("Concurrent request rejected: "+i);
            }
        }catch(Throwable error){finish(error);}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(TickEvent.ServerTickEvent event) {
        if(server==null||pending||event.phase!=TickEvent.Phase.END)return;
        try {
            boolean complete=true;
            for(int i=0;i<4;i++) {
                if(ends[i]==0&&stacks[i].getOrCreateTag().getInt("State")!=1) ends[i]=System.nanoTime();
                if(ends[i]==0)complete=false;
                if(System.nanoTime()-starts[i]>180_000_000_000L)throw new IllegalStateException("Concurrent timeout: "+i);
            }
            if(!complete)return;
            StringBuilder rows=new StringBuilder("player,target,wall_ms,main_ms,samples,verified,x,y,z\n");
            for(int i=0;i<4;i++) {
                var tag=stacks[i].getOrCreateTag();int x=tag.getInt("FoundX"),z=tag.getInt("FoundZ"),y=tag.contains("ECFiltersFoundY")?tag.getInt("ECFiltersFoundY"):tag.getInt("FoundY");
                boolean valid=tag.getInt("State")==2;
                var level=server.overworld();var id=new ResourceLocation(TARGETS[i]);
                if(i>=2)valid&=level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(x>>2,y>>2,z>>2,level.getChunkSource().randomState().sampler()).value()==level.registryAccess().registryOrThrow(Registries.BIOME).get(id);
                else {
                    var chunk=level.getChunkSource().getChunk(x>>4,z>>4,ChunkStatus.STRUCTURE_STARTS,true);
                    var start=level.structureManager().getStartForStructure(SectionPos.bottomOf(chunk),level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(id),chunk);
                    valid&=start!=null&&start.isValid()&&!start.getPieces().isEmpty();
                }
                rows.append(String.format(Locale.ROOT,"%d,%s,%.6f,%.6f,%.0f,%s,%d,%d,%d%n",i,TARGETS[i],(ends[i]-starts[i])/1e6,engines[i].mainNanos()/1e6,engines[i].metric("samples","priorSamples",false),valid,x,y,z));
                if(!valid)throw new IllegalStateException("Concurrent result unverified: "+i);
            }
            Files.writeString(Path.of("concurrent-comparison.csv"),rows);finish(null);
        }catch(Throwable error){finish(error);}
    }
    private static void finish(Throwable error) {
        try {
            for(int i=0;i<4;i++)if(engines[i]!=null&&players[i]!=null)engines[i].cancel(players[i]);
            if(error!=null)error.printStackTrace();
            Files.writeString(Path.of("concurrent-status.txt"),error==null?"COMPLETE: four independent players\n":"FAILED: "+error+"\n");
        }catch(Exception failure){failure.printStackTrace();}
        server.halt(false);server=null;
    }
}
