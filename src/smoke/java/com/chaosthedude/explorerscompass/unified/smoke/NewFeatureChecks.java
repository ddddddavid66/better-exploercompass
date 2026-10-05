package com.chaosthedude.explorerscompass.unified.smoke;

import static com.chaosthedude.explorerscompass.unified.smoke.SmokeChecks.check;
import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.search.*;
import com.chaosthedude.explorerscompass.unified.data.LandingMode;
import com.mojang.authlib.GameProfile;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

final class NewFeatureChecks {
    static void verify(MinecraftServer server) throws Exception {
        var world=server.overworld();var profile=new GameProfile(UUID.randomUUID(),"Unified102Check");
        var player=new ServerPlayer(server,world,profile);var stack=new ItemStack(ExplorersCompass.explorersCompass);
        player.setItemInHand(InteractionHand.MAIN_HAND,stack);server.getPlayerList().getOps().add(new ServerOpListEntry(profile,4,false));
        try {
            BlockPos point=world.getSharedSpawnPos().above(40);
            for(int x=(point.getX()>>4)-1;x<=(point.getX()>>4)+1;x++)for(int z=(point.getZ()>>4)-1;z<=(point.getZ()>>4)+1;z++)world.getChunk(x,z);
            world.setBlockAndUpdate(point.below(),Blocks.MAGMA_BLOCK.defaultBlockState());
            world.setBlockAndUpdate(point,Blocks.AIR.defaultBlockState());world.setBlockAndUpdate(point.above(),Blocks.AIR.defaultBlockState());
            target(stack,new ResourceLocation("minecraft:village_plains"),point);
            TeleportService.setMode(player.getUUID(),LandingMode.RELAXED_ONLY);
            check(TeleportService.start(player,stack,SearchService.token(stack)),"direct relaxed needs no failed first request");
            Object task=task(player);check(bool(task,"fallback"),"direct relaxed stage selected immediately");
            TeleportService.maintain();TeleportService.step();
            check(!TeleportService.awaitingConfirmation(player.getUUID()),"relaxed does not require confirmation");
            TeleportService.cancel(player.getUUID());
            TeleportService.setMode(player.getUUID(),LandingMode.SAFE_ONLY);
            check(TeleportService.start(player,stack,SearchService.token(stack)),"strict only first click");
            task=task(player);var expires=task.getClass().getDeclaredField("expires");expires.setAccessible(true);expires.setInt(task,server.getTickCount());
            TeleportService.maintain();
            check(TeleportService.request(player,stack,SearchService.token(stack),UUID.randomUUID(),true),"strict-only retries valid target");
            check(!bool(task(player),"fallback"),"strict-only cannot enter relaxed phase");
            TeleportService.setMode(player.getUUID(),LandingMode.DEFAULT);
            check(!bool(task(player),"waiting"),"mode change clears confirmation");TeleportService.cancel(player.getUUID());
            var key=new ResourceLocation("minecraft:buried_treasure");
            for(int origin:new int[]{0,-4096,4096}) {
                var search=new StructureSearch(world,new BlockPos(origin,70,origin),List.of(key),Set.of(),2500,300000,true);
                long deadline=System.nanoTime()+30_000_000_000L;
                while(!search.done() && System.nanoTime()<deadline){search.step();SmokeChecks.pump(server);Thread.sleep(1);}
                check(search.done() && search.result()!=null,"actual buried treasure generation "+origin);
                var found=search.result().position();search.cancel();
                check((found.getX()&15)==9 && (found.getZ()&15)==9,"treasure XZ comes from real piece");
                for(int x=(found.getX()>>4)-1;x<=(found.getX()>>4)+1;x++)for(int z=(found.getZ()>>4)-1;z<=(found.getZ()>>4)+1;z++)world.getChunk(x,z);
                // 在真实宝藏列准备可用地表，验证三种策略都不会横移。
                int top=world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,found.getX(),found.getZ());
                BlockPos surface=new BlockPos(found.getX(),top+3,found.getZ());
                world.setBlockAndUpdate(surface.below(),Blocks.STONE.defaultBlockState());world.setBlockAndUpdate(surface,Blocks.AIR.defaultBlockState());world.setBlockAndUpdate(surface.above(),Blocks.AIR.defaultBlockState());
                for(LandingMode mode:LandingMode.values()) {
                    target(stack,key,found);TeleportService.setMode(player.getUUID(),mode);TeleportService.prepare(player,stack,SearchService.token(stack));
                    TeleportService.maintain();TeleportService.step();var landing=TeleportService.prepared(player.getUUID());
                    check(landing!=null && landing.getX()==found.getX() && landing.getZ()==found.getZ(),"treasure column exact "+origin+" "+mode);
                    TeleportService.cancel(player.getUUID());
                }
            }
            check(TeleportService.ticketCount()==0,"102 tickets released");
            System.out.println("UNIFIED102 server modes, actual positive/negative treasure columns ALL PASS");
        } finally {SearchService.cancel(player,true);TeleportService.forget(player.getUUID());server.getPlayerList().getOps().remove(profile);}
    }
    private static void target(ItemStack stack,ResourceLocation key,BlockPos point) {
        ExplorersCompass.explorersCompass.setFound(stack,key,point.getX(),point.getZ(),1);
        var tag=stack.getOrCreateTag();tag.remove(SearchService.KIND);tag.putInt(SearchService.FOUND_Y,point.getY());
        tag.putString(SearchService.TARGET_DIMENSION,Level.OVERWORLD.location().toString());tag.putUUID("ECFiltersSearchToken",UUID.randomUUID());
    }
    private static Object task(ServerPlayer player) throws Exception {
        var f=TeleportService.class.getDeclaredField("tasks");f.setAccessible(true);return ((Map<?,?>)f.get(null)).get(player.getUUID());
    }
    private static boolean bool(Object task,String name) throws Exception {var f=task.getClass().getDeclaredField(name);f.setAccessible(true);return f.getBoolean(task);}
}
