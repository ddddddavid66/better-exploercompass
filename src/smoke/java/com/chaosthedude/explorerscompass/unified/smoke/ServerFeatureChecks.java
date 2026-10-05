package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.mojang.authlib.GameProfile;
import com.chaosthedude.explorerscompass.unified.network.*;
import com.chaosthedude.explorerscompass.unified.search.*;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import static com.chaosthedude.explorerscompass.unified.smoke.SmokeChecks.check;

/** 正式服务端里的真实目录、方块碰撞和权限验证，不进入安装 jar。 */
public final class ServerFeatureChecks {
    private ServerFeatureChecks() {}
    /** 模拟快照准备耗尽本 tick 的预算，下一轮仍须正常推进，不丢任务。 */
    private static void tickBudget(ServerPlayer player,ItemStack stack) throws Exception {
        boolean asynchronous=SearchConfig.ASYNC_STRUCTURES.get();
        var pending=SearchService.class.getDeclaredField("pendingPreparationNanos");pending.setAccessible(true);
        long previous=pending.getLong(null);
        try {
            SearchConfig.ASYNC_STRUCTURES.set(false);
            var village=new ResourceLocation("minecraft:village_plains");
            check(SearchService.startStructures(player,stack,village,java.util.List.of(village)),"budget request accepted");
            var field=SearchService.class.getDeclaredField("sessions");field.setAccessible(true);
            var sessions=(java.util.Map<?,?>)field.get(null);
            var session=sessions.get(player.getUUID());
            var jobField=session.getClass().getDeclaredField("job");jobField.setAccessible(true);
            var job=(SearchJob)jobField.get(session);
            long prepared=job.mainNanos;
            pending.setLong(null,SearchConfig.TICK_MILLIS.get()*1_000_000L+1);
            SearchService.tick();
            check(job.mainNanos==prepared && SearchService.owns(player,stack),"exhausted preparation budget defers without losing request");
            check(pending.getLong(null)==0,"preparation debit consumed once");
            SearchService.tick();
            check(job.mainNanos>prepared,"next tick resumes deferred search");
        } finally {
            SearchService.cancel(player,true);SearchConfig.ASYNC_STRUCTURES.set(asynchronous);
            pending.setLong(null,previous);
        }
    }
    public static void verify(MinecraftServer server) throws Exception {
        var world = server.overworld();
        var catalog = CatalogService.snapshot(world, UUID.randomUUID());
        var nether = Level.NETHER.location(); var plains = new ResourceLocation("minecraft:plains");
        var crimson = new ResourceLocation("minecraft:crimson_forest");
        check(catalog.dimensionBiomes().size() >= 3, "actual server dimension catalog");
        check(catalog.dimensionBiomes().get(nether).contains(crimson), "nether biome membership");
        check(!catalog.dimensionBiomes().get(Level.OVERWORLD.location()).contains(crimson), "nether biome not overworld");
        check(catalog.dimensionBiomes().get(Level.OVERWORLD.location()).contains(plains), "plains membership");
        check(catalog.dimensionStructures().get(nether).contains(new ResourceLocation("minecraft:fortress")), "nether structure placement");
        check(!catalog.dimensionStructures().get(nether).contains(new ResourceLocation("minecraft:village_plains")), "dimension excludes village");
        var count = new TeleportSucceededPacket(UUID.randomUUID(), UUID.randomUUID(), TeleportSucceededPacket.Kind.BIOME, plains);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        TeleportSucceededPacket.encode(count, buffer); check(count.equals(TeleportSucceededPacket.decode(buffer)), "teleport receipt codec"); buffer.release();
        var teleport = new TeleportRequestPacket(UUID.randomUUID(),UUID.randomUUID(),true); buffer = new FriendlyByteBuf(Unpooled.buffer());
        TeleportRequestPacket.encode(teleport,buffer); check(teleport.equals(TeleportRequestPacket.decode(buffer)),"teleport token codec"); buffer.release();
        GameProfile profile = new GameProfile(UUID.randomUUID(),"FiltersSafeCheck");
        var player = new ServerPlayer(server,world,profile);
        ItemStack stack = new ItemStack(ExplorersCompass.explorersCompass);
        player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        tickBudget(player,stack);
        BlockPos point = world.getSharedSpawnPos().above(20); preload(world, point);
        var oldFloor = world.getBlockState(point.below()); var oldFeet = world.getBlockState(point); var oldHead = world.getBlockState(point.above());
        var key = world.getBiome(point).unwrapKey().orElseThrow().location();
        server.getPlayerList().getOps().add(new ServerOpListEntry(profile,4,false));
        try {
            check(SearchService.startBiome(player,stack,key),"valid biome request");
            ExplorersCompass.explorersCompass.setFound(stack,key,point.getX(),point.getZ(),1);
            stack.getOrCreateTag().putInt(SearchService.FOUND_Y,point.getY());
            SearchService.cancel(player,false);
            UUID token = SearchService.token(stack);
            check(TeleportService.eligible(player,stack,token),"operator can teleport found biome");
            check(!TeleportService.eligible(player,stack,UUID.randomUUID()),"reject stale token");
            stack.getTag().putString(SearchService.TARGET_DIMENSION,nether.toString());
            check(!TeleportService.eligible(player,stack,token),"reject wrong dimension");
            stack.getTag().remove(SearchService.TARGET_DIMENSION);
            check(!TeleportService.eligible(player,stack,token),"reject legacy missing dimension");
            stack.getTag().putString(SearchService.TARGET_DIMENSION,Level.OVERWORLD.location().toString());
            world.setBlockAndUpdate(point.below(),Blocks.STONE.defaultBlockState());
            world.setBlockAndUpdate(point,Blocks.AIR.defaultBlockState()); world.setBlockAndUpdate(point.above(),Blocks.AIR.defaultBlockState());
            check(TeleportService.safe(player,world,stack,point),"actual standable biome position");
            world.setBlockAndUpdate(point,Blocks.WATER.defaultBlockState());
            check(!TeleportService.safe(player,world,stack,point),"reject fluid");
            check(TeleportService.relaxed(player,world,stack,point)!=null,"fallback accepts shallow water with air above");
            world.setBlockAndUpdate(point.above(),Blocks.WATER.defaultBlockState());
            check(TeleportService.relaxed(player,world,stack,point)==null,"fallback rejects submerged head");
            world.setBlockAndUpdate(point.above(),Blocks.AIR.defaultBlockState());
            world.setBlockAndUpdate(point,Blocks.STONE.defaultBlockState());
            check(!TeleportService.safe(player,world,stack,point),"reject collision");
            world.setBlockAndUpdate(point,Blocks.FIRE.defaultBlockState());
            check(!TeleportService.safe(player,world,stack,point),"reject fire");
            world.setBlockAndUpdate(point,Blocks.END_GATEWAY.defaultBlockState());
            check(!TeleportService.safe(player,world,stack,point),"reject gateway teleport block");
            world.setBlockAndUpdate(point,Blocks.AIR.defaultBlockState()); world.setBlockAndUpdate(point.below(),Blocks.MAGMA_BLOCK.defaultBlockState());
            check(!TeleportService.safe(player,world,stack,point),"reject dangerous support");
            check(TeleportService.relaxed(player,world,stack,point)!=null,"fallback accepts magma support");
            world.setBlockAndUpdate(point.below(),Blocks.STONE_SLAB.defaultBlockState());
            check(TeleportService.safe(player,world,stack,point),"strict accepts bottom slab collision surface");
            world.setBlockAndUpdate(point.below(),Blocks.STONE.defaultBlockState());
            check(TeleportService.relaxed(player,world,stack,point.above(2))!=null,"fallback resolves short fall to support");
            check(TeleportService.relaxed(player,world,stack,point.above(5))==null,"fallback rejects high air");
            world.setBlockAndUpdate(point,Blocks.LAVA.defaultBlockState());
            check(TeleportService.relaxed(player,world,stack,point)==null,"fallback never chooses lava");
            world.setBlockAndUpdate(point,Blocks.AIR.defaultBlockState());
            world.setBlockAndUpdate(point.below(),Blocks.STONE.defaultBlockState());
            stack.getTag().putString(SearchService.BIOME_KEY,"minecraft:the_void");
            check(!TeleportService.safe(player,world,stack,point),"landing must be actual target biome");
            check(TeleportService.relaxed(player,world,stack,point)!=null,"fallback accepts neighboring biome");
            stack.getTag().putString(SearchService.BIOME_KEY,key.toString());
            check(!TeleportService.safe(player,server.getLevel(Level.NETHER),stack,new BlockPos(0,128,0)),"reject nether roof");
            check(!TeleportService.safe(player,world,stack,new BlockPos(30000000,point.getY(),0)),"reject world border before chunk loading");
            verifyPlatform(player, server.getLevel(Level.NETHER), stack, new BlockPos(0,60,0), "nether safe platform");
            var cave = new BiomeSearch(world, new BlockPos(0,64,0), new ResourceLocation("minecraft:lush_caves"),
                    java.util.List.of(),10000,2000000,32,64,4,false);
            while (!cave.done()) { cave.step(); SmokeChecks.pump(server); }
            check(cave.result() != null,"actual cave biome located");
            check(world.getBiome(cave.result().position()).unwrapKey().orElseThrow().location().toString().equals("minecraft:lush_caves"),"actual cave biome matches sampler");
            verifyPlatform(player,world,stack,cave.result().position(),"cave safe platform"); cave.cancel();
            TeleportService.prepare(player,stack,token); TeleportService.maintain();
            int preparationSteps=0;
            while(TeleportService.prepared(player.getUUID())==null && preparationSteps++<100) { TeleportService.step(); SmokeChecks.pump(server); }
            check(point.equals(TeleportService.prepared(player.getUUID())),"target column prepares first safe point");
            world.setBlockAndUpdate(point.below(),Blocks.WATER.defaultBlockState());
            check(TeleportService.start(player,stack,token),"prepared target accepts click"); TeleportService.step();
            check(TeleportService.prepared(player.getUUID())==null,"changed terrain invalidates cached landing before moving");
            TeleportService.cancel(player.getUUID()); world.setBlockAndUpdate(point.below(),Blocks.STONE.defaultBlockState());
            var preparations=new java.util.ArrayList<ServerPlayer>();
            try {
                for(int i=0;i<6;i++) {
                    var profile2=new GameProfile(UUID.randomUUID(),"TicketCheck"+i);
                    var other=new ServerPlayer(server,world,profile2);var held=stack.copy();
                    held.getOrCreateTag().putUUID("ECFiltersSearchToken",token);
                    other.setItemInHand(InteractionHand.MAIN_HAND,held);server.getPlayerList().getOps().add(new ServerOpListEntry(profile2,4,false));
                    preparations.add(other);TeleportService.prepare(other,held,SearchService.token(held));
                }
                TeleportService.maintain();check(TeleportService.ticketCount()==4,"global four preparation limit");
                var initialMap=TeleportService.class.getDeclaredField("tasks");initialMap.setAccessible(true);
                var identities=new java.util.HashSet<UUID>();
                for(var entry:((java.util.Map<?,?>)initialMap.get(null)).values()) {
                    var ticketIdentity=entry.getClass().getDeclaredField("ticketId");ticketIdentity.setAccessible(true);
                    check(identities.add((UUID)ticketIdentity.get(entry)),"cloned compass tokens have independent tickets");
                }
                var last=preparations.get(5);check(TeleportService.start(last,last.getMainHandItem(),SearchService.token(last.getMainHandItem())),"queued click accepted");
                TeleportService.maintain();check(TeleportService.ticketCount()==4,"priority preemption leaves no orphan fifth ticket");
                var map=TeleportService.class.getDeclaredField("tasks");map.setAccessible(true);
                var queued=((java.util.Map<?,?>)map.get(null)).get(last.getUUID());
                var ticket=queued.getClass().getDeclaredField("ticket");ticket.setAccessible(true);check(ticket.getBoolean(queued),"clicked task prepares before queued background task");
            } finally { for(var other:preparations){TeleportService.cancel(other.getUUID());server.getPlayerList().getOps().remove(other.getGameProfile());} }
            check(TeleportService.ticketCount()==0,"every custom ticket released on cancel");
            check(TeleportService.start(player,stack,token),"accepted safe landing task");
            stack.getTag().putUUID("ECFiltersSearchToken",UUID.randomUUID()); TeleportService.step();
            check(!TeleportService.active(),"stale task cancelled without teleport");
            stack.getTag().putUUID("ECFiltersSearchToken",token);
            UUID requestNonce=UUID.randomUUID();
            check(!TeleportService.request(player,stack,token,UUID.randomUUID(),true),"cannot force fallback before first failure");
            check(TeleportService.request(player,stack,token,requestNonce,false),"timeout fixture accepted");
            var taskMap = TeleportService.class.getDeclaredField("tasks"); taskMap.setAccessible(true);
            var pending = ((java.util.Map<?,?>)taskMap.get(null)).get(player.getUUID());
            var expiry = pending.getClass().getDeclaredField("expires"); expiry.setAccessible(true);
            check(expiry.getInt(pending) - server.getTickCount() == 40,"first click deadline is exactly 40 ticks");
            expiry.setInt(pending,server.getTickCount()); TeleportService.maintain();
            check(!TeleportService.active() && TeleportService.awaitingConfirmation(player.getUUID()),"first timeout preserves fallback confirmation");
            check(TeleportService.request(player,stack,token,requestNonce,false),"duplicate packet is ignored after failure");
            check(TeleportService.awaitingConfirmation(player.getUUID()) && !TeleportService.active(),"replayed first request cannot escalate or restart");
            check(!TeleportService.request(player,stack,UUID.randomUUID(),UUID.randomUUID(),true),"stale target cannot confirm fallback");
            check(TeleportService.request(player,stack,token,UUID.randomUUID(),true),"second click permits fallback");
            var pending2=((java.util.Map<?,?>)taskMap.get(null)).get(player.getUUID());
            check(expiry.getInt(pending2)-server.getTickCount()==20,"fallback deadline is 20 ticks");
            expiry.setInt(pending2,server.getTickCount());TeleportService.maintain();
            var confirmation=pending2.getClass().getDeclaredField("confirmationUntil");confirmation.setAccessible(true);
            confirmation.setLong(pending2,System.nanoTime()-1);TeleportService.maintain();
            check(!TeleportService.awaitingConfirmation(player.getUUID()),"confirmation expiry clears retry state");
            check(!TeleportService.request(player,stack,token,UUID.randomUUID(),true),"expired confirmation cannot be reused");
            TeleportService.cancel(player.getUUID());
            check(ExplorersCompass.explorersCompass.getState(stack)==com.chaosthedude.explorerscompass.util.CompassState.FOUND,"preparation expiry preserves found target");
            check(TeleportService.start(player,stack,token),"second landing task");
            SearchService.startBiome(player,stack,key); check(!TeleportService.active(),"new search cancels pending teleport");
            SearchService.cancel(player,true);
            ExplorersCompass.explorersCompass.setFound(stack,key,point.getX(),point.getZ(),1);
            stack.getTag().putInt(SearchService.FOUND_Y,250);
            var before = player.position();
            check(TeleportService.start(player,stack,SearchService.token(stack)),"no-safe-position task accepted");
            TeleportService.maintain(); int iterations = 0;
            while (TeleportService.active() && iterations++ < 10000) { TeleportService.step(); SmokeChecks.pump(server); }
            check(!TeleportService.active() && player.position().equals(before),"no safe landing leaves player unchanged");
            server.getPlayerList().getOps().remove(profile);
            ExplorersCompass.explorersCompass.setFound(stack,key,point.getX(),point.getZ(),1);
            check(!TeleportService.eligible(player,stack,SearchService.token(stack)),"permission denied");
        } finally {
            SearchService.cancel(player,true); server.getPlayerList().getOps().remove(profile);
            world.setBlockAndUpdate(point.below(),oldFloor); world.setBlockAndUpdate(point,oldFeet); world.setBlockAndUpdate(point.above(),oldHead);
        }
    }
    private static void preload(net.minecraft.server.level.ServerLevel level, BlockPos point) {
        // 测试夹具预先生成区块；被测传送路径仍仅查询 getChunkNow。
        for (int x=-1;x<=1;x++) for (int z=-1;z<=1;z++)
            level.getChunk(point.getX()/16+x,point.getZ()/16+z,net.minecraft.world.level.chunk.ChunkStatus.FULL);
    }
    private static void verifyPlatform(ServerPlayer player, net.minecraft.server.level.ServerLevel level,
            ItemStack stack, BlockPos point, String label) {
        preload(level, point); var floor = level.getBlockState(point.below()); var feet = level.getBlockState(point); var head = level.getBlockState(point.above());
        String previous = stack.getTag().getString(SearchService.BIOME_KEY);
        try {
            stack.getTag().putString(SearchService.BIOME_KEY,level.getBiome(point).unwrapKey().orElseThrow().location().toString());
            level.setBlockAndUpdate(point.below(),Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(point,Blocks.AIR.defaultBlockState()); level.setBlockAndUpdate(point.above(),Blocks.AIR.defaultBlockState());
            check(TeleportService.safe(player,level,stack,point),label);
        } finally {
            level.setBlockAndUpdate(point.below(),floor); level.setBlockAndUpdate(point,feet); level.setBlockAndUpdate(point.above(),head);
            stack.getTag().putString(SearchService.BIOME_KEY,previous);
        }
    }
}
