package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.unified.search.*;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.network.CatalogService;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.unified.data.LandingMode;
import com.mojang.authlib.GameProfile;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;

/** 只进入 smoke 工具；验收使用真实 tick，不在启动事件阻塞整轮服务器。 */
@Mod.EventBusSubscriber(modid="filterschecks")
public final class End105TickChecks {
    private static MinecraftServer server;private static ServerLevel level;private static ServerPlayer player;
    private static ItemStack compass;private static EndDungeonSearch job;private static String test;
    private static final ArrayDeque<String> CASES=new ArrayDeque<>();
    private static final StringBuilder ROWS=new StringBuilder("case,elapsed_ms,samples,candidates,checks,chunk_groups,x,y,z,expanded,mode\n");
    private static Object originalRules;private static BlockPos first;private static long started;private static int completed;
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if(!Boolean.getBoolean("unified.end105"))return;
        try {
            server=event.getServer();level=server.getLevel(Level.END);
            require(EndDungeonAdapter.special(level,EndDungeonAdapter.KEY),"optional adapter registered");
            var catalog=CatalogService.snapshot(level,UUID.randomUUID());
            require(catalog.structures().containsKey(EndDungeonAdapter.KEY),"virtual structure in directory");
            require(catalog.dimensionStructures().get(Level.END.location()).contains(EndDungeonAdapter.KEY),"end dimension filter");
            require(!catalog.dimensionStructures().get(Level.OVERWORLD.location()).contains(EndDungeonAdapter.KEY),"not overworld");
            Object rules=rules(); require((boolean)property(rules,"predictive"),"prediction available: "+property(rules,"reason"));
            require(!((java.util.List<?>)property(rules,"cross")).isEmpty() && !((java.util.List<?>)property(rules,"globe")).isEmpty(),"both evidence signatures prepared");
            require(catalog.groups().get(EndDungeonAdapter.KEY).equals(EndDungeonAdapter.KEY),"group entry works");
            player=FakePlayerFactory.get(level,new GameProfile(UUID.fromString("a930f13e-1111-1111-1111-111111111105"),"End105Check"));
            // FakePlayer 自带网络处理器不执行实际传送；保留原生 teleport，只屏蔽测试网络发送。
            player.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(server,
                    new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND),player) {
                @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
                @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener callback) {}
            };
            player.setPos(0,70,0);compass=new ItemStack(ExplorersCompass.explorersCompass);player.setItemInHand(InteractionHand.MAIN_HAND,compass);
            player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(player.getGameProfile(),4,false));
            CASES.addAll(List.of("async-natural","sync-same-position","next","service-normal","service-group","service-next","index-expanded","index-only","teleport-DEFAULT","teleport-SAFE_ONLY","teleport-RELAXED_ONLY","cancel-replace"));
        }catch(Throwable failure){finish(failure);}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(TickEvent.ServerTickEvent event) {
        if(server==null || event.phase!=TickEvent.Phase.END)return;
        try {
            if(job!=null) {
                long slice=System.nanoTime()+2_000_000L;
                do { if(!job.step())break; }while(!job.done() && System.nanoTime()<slice);
                if(!job.done()) {require(System.nanoTime()-started<120_000_000_000L,"search timed out "+test);return;}
                var hit=job.result();require(hit!=null,"natural site found "+test);
                if(test.equals("async-natural"))first=hit.position();
                if(test.equals("sync-same-position"))require(first.equals(hit.position()),"sync/async nearest XYZ match");
                if(test.equals("next"))require(!first.equals(hit.position()),"next skips anchor");
                ROWS.append(String.format(Locale.ROOT,"%s,%.3f,%d,%d,%d,%d,%d,%d,%d,%s,%s%n",test,(System.nanoTime()-started)/1e6,
                        job.samples(),job.candidates(),job.confirmations,job.chunkLoads,hit.position().getX(),hit.position().getY(),hit.position().getZ(),job.expanded(),job.mode()));
                require(EndDungeonIndex.get(level).positions().contains(hit.position()),"found persisted as index clue");
                job.cancel();job=null;
                if(originalRules!=null) {cacheRules(originalRules);originalRules=null;}
                completed++;Files.writeString(Path.of("end105-validation.csv"),ROWS);return;
            }
            if(test!=null && test.startsWith("teleport-")) {
                if(TeleportEvents.latest(player.getUUID())!=null) {
                    require(TeleportEvents.latest(player.getUUID()).target().equals(EndDungeonAdapter.KEY),"successful teleport retains feature counter ID");
                    require(player.distanceToSqr(first.getX(),first.getY(),first.getZ())<10000,"player moved to dungeon vicinity");
                    require(TeleportService.ticketCount()==0,"successful feature teleport releases tickets");
                    completed++;test=null;
                } else {
                    if(TeleportService.awaitingConfirmation(player.getUUID()) && TeleportService.mode(player.getUUID())==LandingMode.DEFAULT)
                        TeleportService.request(player,compass,SearchService.token(compass),UUID.randomUUID(),true);
                    require(System.nanoTime()-started<30_000_000_000L,"feature teleport timed out "+test);return;
                }
            }
            if(test!=null && test.startsWith("service-") && SearchService.activeCount()>0) {
                require(System.nanoTime()-started<120_000_000_000L,"service timed out "+test);return;
            }
            if(test!=null && test.startsWith("service-")) {
                require(ExplorersCompass.explorersCompass.getState(compass)==CompassState.FOUND,"service completed "+test);
                require("feature".equals(compass.getTag().getString(SearchService.KIND)),"feature NBT kind");
                require(compass.getTag().getString(SearchService.TARGET_DIMENSION).equals(Level.END.location().toString()),"real target dimension");
                TeleportService.cancel(player.getUUID());completed++;test=null;
            }
            if(CASES.isEmpty()) {require(slots("count")==0 && slots("queued")==0,"shared permits released");finish(null);return;}
            test=CASES.removeFirst();started=System.nanoTime();
            if(test.equals("async-natural") || test.equals("sync-same-position") || test.equals("next")) {
                job=new EndDungeonSearch(level,new BlockPos(0,70,0),test.equals("next")?Set.of(first.asLong()):Set.of(),6000,1_000_000,!test.equals("sync-same-position"));
            }else if(test.startsWith("service-")) {
                if(!test.equals("service-next"))SearchService.clearCache(player.getUUID());
                require(SearchService.requestStructures(player,compass,EndDungeonAdapter.KEY,test.equals("service-group"),test.equals("service-next"),null),"service request accepted "+test);
            }else if(test.equals("index-expanded")) {
                // 隔离测试夹具验证已展开形态；生产定位绝不调用地牢展开过程。
                level.getChunkAt(first);
                var original=level.getBlockState(first);
                if(original.is(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation("stalwart_dungeons:ender_globe_spawn")))) {
                    Class<?> procedure=Class.forName("net.mcreator.stalwartdungeons.procedures.EnderGlobeSpawnUpdateTickProcedure");
                    procedure.getMethod("execute",net.minecraft.world.level.LevelAccessor.class,double.class,double.class,double.class)
                            .invoke(null,level,(double)first.getX(),(double)first.getY(),(double)first.getZ());
                }
                require(evidence(first)==2,"complete globe signature");
                job=new EndDungeonSearch(level,first,Set.of(),500,10000,true);
                // 索引重启格式和真实坐标保存。
                var saved=EndDungeonIndex.get(level).save(new net.minecraft.nbt.CompoundTag());require(saved.getLongArray("anchors").length>0,"saved index contains anchors");
            }else if(test.equals("index-only")) {
                // 模拟预测入口未审核且当前允许群系为空：已有线索仍必须核对真实地牢。
                originalRules=rules();var constructor=originalRules.getClass().getDeclaredConstructors()[0];constructor.setAccessible(true);
                var fallback=constructor.newInstance(List.of(),Set.of(),property(originalRules,"cross"),property(originalRules,"globe"),false,"isolated unreviewed placement fixture");
                cacheRules(fallback);job=new EndDungeonSearch(level,first,Set.of(),500,10000,true);
                require(job.mode().equals("fallback"),"unreviewed rule honestly reports index-only mode");
            }else if(test.startsWith("teleport-")) {
                // 冷区块搜索另有用例；此处验证 Feature 目标进入已有传送、移动和计数路径。
                var center=new net.minecraft.world.level.ChunkPos(first);
                for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)level.getChunk(center.x+dx,center.z+dz);
                // 隔离夹具提供已知安全面，不宣称每个自然十字架旁必然有安全落点。
                level.setBlock(first,net.minecraft.world.level.block.Blocks.END_STONE.defaultBlockState(),3);
                level.setBlock(first.above(),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);
                level.setBlock(first.above(2),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);
                ExplorersCompass.explorersCompass.setFound(compass,EndDungeonAdapter.KEY,first.getX(),first.getZ(),1);
                var tag=compass.getOrCreateTag();tag.putString(SearchService.KIND,"feature");tag.putInt(SearchService.FOUND_Y,first.getY());
                tag.putString(SearchService.TARGET_DIMENSION,Level.END.location().toString());tag.putUUID("ECFiltersSearchToken",UUID.randomUUID());
                TeleportEvents.clear(player.getUUID());player.setPos(0,100,0);
                TeleportService.setMode(player.getUUID(),LandingMode.valueOf(test.substring(9)));
                require(TeleportService.start(player,compass,SearchService.token(compass)),"feature teleport accepted "+test);
            }else {
                SearchService.clearCache(player.getUUID());
                require(SearchService.requestStructures(player,compass,EndDungeonAdapter.KEY,false,false,null),"cancellable request");
                SearchService.cancel(player,true);
                require(SearchService.activeCount()==0 && slots("count")==0,"cancel releases tasks/tickets");
                for(LandingMode mode:LandingMode.values()) {TeleportService.setMode(player.getUUID(),mode);require(TeleportService.mode(player.getUUID())==mode,"mode retained "+mode);}
                require(!SearchService.requestStructures(player,compass,EndDungeonAdapter.KEY,false,false,new net.minecraft.resources.ResourceLocation("minecraft:plains")),"invalid biome intersection rejected");
                completed++;test=null;
            }
        }catch(Throwable failure){finish(failure);}
    }
    @SuppressWarnings("unchecked") private static void cacheRules(Object rules) throws Exception {
        var field=EndDungeonAdapter.class.getDeclaredField("CACHE");field.setAccessible(true);
        ((java.util.Map<ServerLevel,Object>)field.get(null)).put(level,rules);
    }
    private static Object rules() throws Exception {
        var method=EndDungeonAdapter.class.getDeclaredMethod("snapshot",ServerLevel.class); method.setAccessible(true); return method.invoke(null,level);
    }
    private static Object property(Object object,String name) throws Exception {var method=object.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(object);}
    private static int evidence(BlockPos anchor) throws Exception {
        Object snapshot=rules();var method=EndDungeonAdapter.class.getDeclaredMethod("evidence",snapshot.getClass(),BlockPos.class,java.util.function.Function.class);
        method.setAccessible(true);return (int)method.invoke(null,snapshot,anchor,(java.util.function.Function<BlockPos,net.minecraft.world.level.block.state.BlockState>)level::getBlockState);
    }
    private static int slots(String name) throws Exception {var method=Class.forName("com.chaosthedude.explorerscompass.unified.search.VerificationSlots").getDeclaredMethod(name);method.setAccessible(true);return (int)method.invoke(null);}
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static void finish(Throwable error) {
        try {
            if(error!=null)error.printStackTrace();
            if(job!=null)job.cancel();if(originalRules!=null){cacheRules(originalRules);originalRules=null;}if(player!=null){SearchService.cancel(player,true);TeleportService.forget(player.getUUID());server.getPlayerList().getOps().remove(player.getGameProfile());}
            Files.writeString(Path.of("smoke-end105-results.txt"),error==null?"PASS: "+completed+" real tick cases; natural cross and expanded globe; sync XYZ; ordinary/group/next; index; cancellation and biome filter\n":"FAIL: "+error+"\n");
            Files.writeString(Path.of("end105-validation.csv"),ROWS);
        }catch(Exception failure){failure.printStackTrace();}
        var stop=server;server=null;if(stop!=null)stop.halt(false);
    }
}
