package com.chaosthedude.explorerscompass.unified.smoke;

import com.david.compass.runtime.game.search.ColumnClimate;
import com.david.compass.runtime.game.search.SearchJob;
import com.david.compass.runtime.game.search.TeleportService;
import com.david.compass.runtime.game.search.VerificationSlots;

import com.chaosthedude.explorerscompass.unified.search.*;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.david.compass.runtime.game.data.LandingMode;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.mojang.authlib.GameProfile;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/** 正常服务器 tick 和正常请求入口；只在显式验证参数下启用，不进入发布包。 */
@Mod.EventBusSubscriber(modid="filterschecks")
public final class Optional111Checks {
    private static final java.util.List<String> CAVES=java.util.List.of("magnetic_caves","primordial_caves","toxic_caves","abyssal_chasm","forlorn_hollows","candy_cavity");
    private record Case(ResourceLocation id,boolean biome,boolean async,int repeat) {}
    private static final ArrayDeque<Case> cases=new ArrayDeque<>();
    private static final Map<ResourceLocation,BlockPos> expected=new HashMap<>();
    private static final List<String> failures=new ArrayList<>();
    private static final StringBuilder rows=new StringBuilder("target,async,repeat,elapsed_ms,worker_ms,waiting_ms,main_ms,samples,candidates,checks,chunks,mode,result,x,y,z\n");
    private static MinecraftServer server;
    private static ServerPlayer player;
    private static ItemStack stack;
    private static Case current;
    private static Object session;
    private static long started;
    private static boolean finished;
    private static boolean currentVersion;
    private static int initialTicks;
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if(!Boolean.getBoolean("unified.optional111") || !event.getServer().isDedicatedServer())return;
        server=event.getServer();
        currentVersion=ModList.get().getModContainerById("explorerscompass").orElseThrow().getModInfo().getVersion().toString().equals("1.1.1");
        try {
            player=FakePlayerFactory.get(server.overworld(),new GameProfile(UUID.randomUUID(),"Optional111Check"));
            player.setGameMode(GameType.CREATIVE);player.setPos(-590,87,-47);
            stack=new ItemStack(ExplorersCompass.explorersCompass);player.setItemInHand(InteractionHand.MAIN_HAND,stack);
            if(ModList.get().isLoaded("alexscaves")) {
                alexArithmetic();
                for(String path:CAVES) {
                    var id=new ResourceLocation("alexscaves",path);
                    cases.add(new Case(id,true,true,0));
                }
                cases.add(new Case(new ResourceLocation("alexscaves:primordial_caves"),true,false,0));
            } else {
                if(currentVersion){columnClimate();templateSnapshots();}
                for(String value:List.of("betterjungletemples:jungle_temple","betteroceanmonuments:ocean_monument",
                        "aquamirae:ship","aquamirae:outpost","aquamirae:shelter","aquamirae:ice_maze/common",
                        "aquamirae:ice_maze/arch","aquamirae:ice_maze/spiral")) {
                    for(int repeat=0;repeat<3;repeat++)cases.add(new Case(new ResourceLocation(value),false,true,repeat));
                    // 兼容开关核对紧接相同目标，避免另一个变体淘汰有界缓存导致测试超时。
                    if(currentVersion && (value.equals("betterjungletemples:jungle_temple") || value.equals("aquamirae:ice_maze/arch")))
                        cases.add(new Case(new ResourceLocation(value),false,false,0));
                }
                if(Boolean.getBoolean("unified.group111")) {
                    cases.clear();
                    for(String path:List.of("ice_maze/common","ice_maze/arch","ice_maze/spiral","ice_maze"))
                        cases.add(new Case(new ResourceLocation("aquamirae",path),false,true,0));
                }
            }
        }catch(Throwable error){failures.add("init: "+error);error.printStackTrace();finish();}
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) public static void tick(TickEvent.ServerTickEvent event) {
        if(server==null || finished || event.phase!=TickEvent.Phase.END)return;
        // Forge ConfigValue.set 写入自动保存文件时可能触发重载取消；测试开关使用内存快照。
        if(initialTicks++<40)return;
        if(initialTicks==41) {
            try {
                var spec=com.chaosthedude.explorerscompass.config.ConfigHandler.GENERAL_SPEC;
                var values=(com.electronwill.nightconfig.core.UnmodifiableConfig)field(spec,"childConfig");
                spec.setConfig(memory(values));
            }catch(Exception failure){failures.add("config snapshot: "+failure);finish();return;}
        }
        try {
            if(current!=null) {
                if(SearchService.owns(player,stack) && System.nanoTime()-started<120_000_000_000L)return;
                SearchJob job=(SearchJob)field(session,"job");
                boolean found=ExplorersCompass.explorersCompass.getState(stack)==CompassState.FOUND;
                BlockPos pos=found?new BlockPos(stack.getTag().getInt("FoundX"),stack.getTag().getInt(SearchService.FOUND_Y),stack.getTag().getInt("FoundZ")):BlockPos.ZERO;
                rows.append(String.format(Locale.ROOT,"%s,%s,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%s,%s,%d,%d,%d%n",
                        current.id,current.async,current.repeat,(System.nanoTime()-started)/1e6,job.workerNanos()/1e6,
                        job.waitingNanos()/1e6,job.mainNanos/1e6,job.samples(),job.candidates(),job.confirmations,job.chunkLoads,job.mode(),found?"found":"FAILED",pos.getX(),pos.getY(),pos.getZ()));
                if(!found)failures.add(current+" not found: "+job.stage()+", removed="+player.isRemoved()+", held="+(player.getMainHandItem()==stack)+", token="+SearchService.token(stack)+", session="+((Map<?,?>)staticField(SearchService.class,"sessions")).containsKey(player.getUUID()));
                if(currentVersion && current.async && !job.mode().equals("async"))failures.add(current+" unexpected mode "+job.mode());
                if(currentVersion && !current.async && current.id.toString().equals("aquamirae:ice_maze/arch")
                        && !job.diagnostics().contains("skipped_height=0"))failures.add("warm compatibility did not reuse negative prediction cache");
                if(found) {
                    BlockPos old=expected.putIfAbsent(current.id,pos);
                    if(old!=null && !old.equals(pos))failures.add(current+" result changed: "+old+" -> "+pos);
                    if(current.id.toString().equals("aquamirae:ice_maze")) {
                        long best=expected.entrySet().stream().filter(entry->entry.getKey().getPath().startsWith("ice_maze/"))
                                .mapToLong(entry->(long)entry.getValue().distToLowCornerSqr(player.getX(),entry.getValue().getY(),player.getZ())).min().orElseThrow();
                        long actual=(long)pos.distToLowCornerSqr(player.getX(),pos.getY(),player.getZ());
                        if(actual!=best)failures.add("group nearest differs from single variants");
                        var hit=ExplorersCompass.explorersCompass.getStructureKey(stack);
                        if(!hit.getPath().startsWith("ice_maze/"))failures.add("group lost actual target ID "+hit);
                    }
                }
                SearchService.cancel(player,true);TeleportService.cancel(player.getUUID());current=null;
                if(slots()!=0)failures.add("verification ticket leak");
                Files.writeString(Path.of("optional111.csv"),rows);
                return;
            }
            if(cases.isEmpty()){if(currentVersion)landing();finish();return;}
            current=cases.removeFirst();SearchConfig.ASYNC_STRUCTURES.set(current.async);SearchConfig.ASYNC_BIOMES.set(current.async);
            SearchService.clearCache(player.getUUID());stack.getOrCreateTag().remove("PrevPos");
            boolean accepted=current.biome?SearchService.startBiome(player,stack,current.id):SearchService.requestStructures(player,stack,current.id,current.id.getPath().equals("ice_maze"),false,null);
            if(!accepted)throw new AssertionError("request rejected: "+current);
            var sessions=(Map<?,?>)staticField(SearchService.class,"sessions");session=sessions.get(player.getUUID());started=((Number)field(session,"startedNanos")).longValue();
        }catch(Throwable error){failures.add(String.valueOf(error));error.printStackTrace();finish();}
    }
    @SubscribeEvent public static void sync(net.minecraftforge.event.OnDatapackSyncEvent event) {
        if(server!=null && Boolean.getBoolean("unified.optional111"))System.out.println("OPTIONAL111_SYNC all="+(event.getPlayer()==null)+" case="+current);
    }
    private static com.electronwill.nightconfig.core.CommentedConfig memory(com.electronwill.nightconfig.core.UnmodifiableConfig source) {
        var copy=com.electronwill.nightconfig.core.CommentedConfig.inMemory();
        source.valueMap().forEach((key,value)->copy.set(List.of(key),value instanceof com.electronwill.nightconfig.core.UnmodifiableConfig nested?memory(nested):value));
        return copy;
    }
    @Mod.EventBusSubscriber(modid="filterschecks",bus=Mod.EventBusSubscriber.Bus.MOD)
    public static class ConfigChanges {
        @SubscribeEvent public static void reload(net.minecraftforge.fml.event.config.ModConfigEvent.Reloading event) {
            if(Boolean.getBoolean("unified.optional111") && event.getConfig().getModId().equals("explorerscompass"))
                System.out.println("OPTIONAL111_CONFIG_RELOAD case="+current);
        }
    }
    private static void alexArithmetic() throws Exception {
        var level=server.overworld();var key=new ResourceLocation("alexscaves:magnetic_caves");
        Class<?> adapterType=Class.forName("com.david.compass.runtime.game.search.AlexCavesAdapter");
        var prepare=adapterType.getDeclaredMethod("prepare",net.minecraft.server.level.ServerLevel.class,ResourceLocation.class);prepare.setAccessible(true);
        var adapter=prepare.invoke(null,level,key);
        var sample=adapterType.getDeclaredMethod("center",int.class,int.class);sample.setAccessible(true);if(adapter==null)throw new AssertionError("Alex adapter unavailable");
        var type=Class.forName("com.github.alexmodguy.alexscaves.server.level.biome.ACBiomeRarity");
        var rare=type.getMethod("getRareBiomeInfoForQuad",long.class,int.class,int.class);
        var kind=type.getMethod("getRareBiomeOffsetId",rare.getReturnType());
        var center=type.getMethod("getRareBiomeCenter",rare.getReturnType());
        for(int x=-3000;x<=3000;x+=37)for(int z=-1700;z<=1700;z+=79) {
            Object info=rare.invoke(null,level.getSeed(),x,z);var actual=sample.invoke(adapter,x,z);
            boolean match=info!=null && ((Number)kind.invoke(null,info)).intValue()==0 && ((x<<2)*(x<<2)+(z<<2)*(z<<2)>=400*400);
            if(match!=(actual!=null) || actual!=null && !actual.equals(center.invoke(null,info)))throw new AssertionError("Voronoi mismatch "+x+","+z);
        }
        player.setGameMode(GameType.SURVIVAL);
        var hook=Class.forName("com.github.alexmodguy.alexscaves.server.event.CommonEvents").getDeclaredMethod("checkAndDestroyExploitItem",net.minecraft.world.entity.player.Player.class,net.minecraft.world.entity.EquipmentSlot.class);hook.setAccessible(true);
        for(var hand:InteractionHand.values())for(String path:CAVES) {
            var compass=new ItemStack(ExplorersCompass.explorersCompass);compass.getOrCreateTag().putString(SearchService.KIND,"biome");
            compass.getTag().putString(SearchService.BIOME_KEY,"alexscaves:"+path);compass.getTag().putString("StructureKey","alexscaves:"+path);
            player.setItemInHand(hand,compass);hook.invoke(null,player,hand==InteractionHand.MAIN_HAND?net.minecraft.world.entity.EquipmentSlot.MAINHAND:net.minecraft.world.entity.EquipmentSlot.OFFHAND);
            if(compass.isEmpty())throw new AssertionError("Alex consumed unified cave compass "+hand+path);
        }
        player.setGameMode(GameType.CREATIVE);player.setItemInHand(InteractionHand.MAIN_HAND,stack);player.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
    }
    private static void columnClimate() throws Exception {
        var level=server.overworld();var sampler=level.getChunkSource().randomState().sampler();
        Class<?> type=Class.forName("com.david.compass.runtime.game.search.ColumnClimate");
        var prepare=type.getDeclaredMethod("prepare",net.minecraft.server.level.ServerLevel.class,net.minecraft.world.level.biome.Climate.Sampler.class);prepare.setAccessible(true);
        var columns=prepare.invoke(null,level,sampler);if(columns==null)throw new AssertionError("default climate density proof unavailable");
        if(field(columns,"fixedDepth")==null) {
            throw new AssertionError("default depth column proof unavailable");
        }
        var with=type.getDeclaredMethod("with",int.class,int.class,java.util.function.Supplier.class);with.setAccessible(true);
        for(int x=-1400;x<1400;x+=137)for(int z=-900;z<900;z+=271) {
            var expectedPoints=new ArrayList<net.minecraft.world.level.biome.Climate.TargetPoint>();
            for(int y=-16;y<=80;y++)expectedPoints.add(sampler.sample(x,y,z));
            int qx=x,qz=z;
            var actual=with.invoke(columns,x,z,(java.util.function.Supplier<Object>)()-> {
                var points=new ArrayList<net.minecraft.world.level.biome.Climate.TargetPoint>();
                for(int y=-16;y<=80;y++)points.add(sampler.sample(qx,y,qz));return points;
            });
            if(!expectedPoints.equals(actual))throw new AssertionError("column climate mismatch "+x+","+z);
        }
    }
    private static void templateSnapshots() throws Exception {
        Class<?> snapshotType=Class.forName("com.chaosthedude.explorerscompass.unified.search.TemplateAnchors");
        var constructor=snapshotType.getDeclaredConstructor(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.class);constructor.setAccessible(true);
        for(String name:List.of("betterjungletemples:start/start_0","betterjungletemples:start/start_1","betterjungletemples:start/start_2","betteroceanmonuments:start")) {
            var original=server.getStructureManager().get(new ResourceLocation(name)).orElseThrow();
            var snapshot=(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate)constructor.newInstance(original);
            for(var rotation:net.minecraft.world.level.block.Rotation.values())for(var mirror:net.minecraft.world.level.block.Mirror.values())for(int seed=0;seed<7;seed++) {
                var pos=new BlockPos(-137,51,101);
                var a=new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings().setRotation(rotation).setMirror(mirror).setRandom(net.minecraft.util.RandomSource.create(seed));
                var b=new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings().setRotation(rotation).setMirror(mirror).setRandom(net.minecraft.util.RandomSource.create(seed));
                if(!original.filterBlocks(pos,a,Blocks.JIGSAW).equals(snapshot.filterBlocks(pos,b,Blocks.JIGSAW)))throw new AssertionError("anchor snapshot mismatch "+name+rotation+mirror+seed);
                if(!original.getBoundingBox(a,pos).equals(snapshot.getBoundingBox(b,pos)))throw new AssertionError("geometry mismatch "+name);
            }
        }
    }
    private static int slots() throws Exception {
        var m=Class.forName("com.david.compass.runtime.game.search.VerificationSlots").getDeclaredMethod("count");m.setAccessible(true);return (int)m.invoke(null);
    }
    private static void landing() throws Exception {
        // 深水无固体支撑：眼睛/头部空气。缓存复核必须接受同一种海面候选。
        var level=server.overworld();BlockPos pos=level.getSharedSpawnPos().above(60);
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.getChunk((pos.getX()>>4)+x,(pos.getZ()>>4)+z);
        for(int y=-4;y<=3;y++)level.setBlockAndUpdate(pos.offset(0,y,0),y<=0?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState());
        var type=Class.forName("com.david.compass.runtime.game.search.LandingChecks");
        var surface=type.getDeclaredMethod("waterSurface",ServerPlayer.class,net.minecraft.server.level.ServerLevel.class,BlockPos.class);surface.setAccessible(true);
        var recheck=type.getDeclaredMethod("waterStanding",ServerPlayer.class,net.minecraft.server.level.ServerLevel.class,net.minecraft.world.phys.Vec3.class);recheck.setAccessible(true);
        var water=surface.invoke(null,player,level,pos.above());
        if(water==null || !(boolean)recheck.invoke(null,player,level,water))throw new AssertionError("water surface prepare/recheck");
        level.setBlockAndUpdate(pos.above(),Blocks.STONE.defaultBlockState());
        if((boolean)recheck.invoke(null,player,level,water))throw new AssertionError("water landing must reject obstructed head");
        TeleportService.setMode(player.getUUID(),LandingMode.RELAXED_ONLY);
        if(TeleportService.ticketCount()!=0 || slots()!=0)throw new AssertionError("tickets after checks");
    }
    private static Object field(Object owner,String name) throws Exception {var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static Object staticField(Class<?> owner,String name) throws Exception {var f=owner.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static void finish() {
        if(finished)return;finished=true;
        if(player!=null){SearchService.cancel(player,true);TeleportService.forget(player.getUUID());}
        String result=(failures.isEmpty()?"PASS":"FAIL")+": optional 1.1.1 real tick validation\n"+String.join("\n",failures);
        try{Files.writeString(Path.of("optional111.csv"),rows);Files.writeString(Path.of("optional111-results.txt"),result);}catch(Exception error){error.printStackTrace();}
        System.out.println(result);server.halt(false);
    }
}
