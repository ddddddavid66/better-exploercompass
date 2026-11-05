package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.unified.creature.search.CreatureHabitats;
import com.chaosthedude.explorerscompass.unified.creature.search.CreatureLanding;
import com.david.compass.runtime.game.search.LocatorTarget;
import com.david.compass.runtime.game.search.SearchJob;
import com.david.compass.runtime.game.search.TeleportService;
import com.david.compass.runtime.game.search.VerificationSlots;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.unified.creature.CreatureItem;
import com.chaosthedude.explorerscompass.unified.creature.client.*;
import com.chaosthedude.explorerscompass.unified.creature.search.*;
import com.david.compass.runtime.game.data.LandingMode;
import com.chaosthedude.explorerscompass.unified.search.*;
import com.chaosthedude.explorerscompass.util.CompassState;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/** 仅愚者副本的四项定向检查；不召唤实体、不修改第三方生成规则。 */
@Mod("unified125checks")
public final class Fix125Checks {
    private record View(String target,String state,boolean done,List<MobPoint> points,boolean pending){}
    private static volatile View view=new View("","",false,List.of(),false);
    private static volatile String command="",target="",finished;
    private static volatile UUID playerId;
    private static int phase,ticks,column,terrainHint,fixtureAttempts;private static long started,overall=System.nanoTime(),lastLog;
    private static boolean partial;
    private static boolean opened,launched;private static String natural="minecraft:squid";
    private static Vec3 departure;private static ServerLevel overworld,aether;
    private static BlockPos aetherFloor;private static ResourceLocation aetherBiome;
    private static UUID first,second;private static BlockPos asyncCity;
    private static final Object[] blockers={new Object(),new Object(),new Object(),new Object()};
    private static final TicketType<UUID> SETUP=TicketType.create("unified125_fixture",Comparator.<UUID>naturalOrder());
    private static final UUID fixture=UUID.randomUUID();private static ServerLevel fixtureLevel;private static ChunkPos fixtureCenter;
    private static String asyncMode="",syncMode="";private static SearchJob currentJob;private static int scans;
    public Fix125Checks(){MinecraftForge.EVENT_BUS.register(this);}
    @SubscribeEvent public void client(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();
        try{
            if(finished!=null){if(!launched){launched=true;log(finished);mc.stop();}return;}
            if(System.nanoTime()-overall>1_200_000_000_000L){finished="FAIL overall deadline";return;}
            if(!opened){
                if(mc.screen instanceof AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
                if(!(mc.screen instanceof TitleScreen))return;opened=true;
                mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(3);mc.options.simulationDistance().set(5);mc.options.framerateLimit().set(30);
                if(Boolean.getBoolean("unified125.onlyFresh")){
                    next(15);mc.createWorldOpenFlows().createFreshLevel(System.getProperty("unified125.freshName","world125-fresh-verify"),new LevelSettings("Yuzhe 125 fresh",GameType.SURVIVAL,false,net.minecraft.world.Difficulty.NORMAL,false,new GameRules(),WorldDataConfiguration.DEFAULT),
                        new net.minecraft.world.level.levelgen.WorldOptions(-3744940466531364533L,true,false),net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions);return;
                }
                mc.createWorldOpenFlows().loadLevel(new TitleScreen(),"world125");return;
            }
            if(command.equals("fresh")){
                command="";target="";mc.level.disconnect();mc.clearLevel(new TitleScreen());
                mc.createWorldOpenFlows().createFreshLevel("world125-fresh",new LevelSettings("Yuzhe 125 fresh",GameType.SURVIVAL,false,net.minecraft.world.Difficulty.NORMAL,false,new GameRules(),WorldDataConfiguration.DEFAULT),
                    new net.minecraft.world.level.levelgen.WorldOptions(-3744940466531364533L,true,false),net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions);return;
            }
            if(mc.player==null || mc.getSingleplayerServer()==null)return;playerId=mc.player.getUUID();
            String cmd=command;command="";
            if(cmd.startsWith("search:")){target=cmd.substring(7);com.chaosthedude.explorerscompass.unified.creature.CreatureConfig.CLIENT.creatureRounds.set(4);CreatureClient.open();CreatureClient.search(new ResourceLocation(target),mc.level.dimension().location(),null);}
            else if(cmd.equals("tp0") || cmd.equals("tp1")){CreatureClient.selection=cmd.equals("tp0")?0:1;CreatureClient.teleport();}
            view=new View(target,CreatureClient.state,CreatureClient.finished,List.copyOf(CreatureClient.points),CreatureClient.teleportPending());
        }catch(Throwable error){error.printStackTrace();finished="FAIL client "+error;}
    }
    @SubscribeEvent public void server(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END || playerId==null || finished!=null)return;
        var server=ServerLifecycleHooks.getCurrentServer();var p=server==null?null:server.getPlayerList().getPlayer(playerId);if(p==null)return;ticks++;
        try{
            if(System.nanoTime()-lastLog>5_000_000_000L){lastLog=System.nanoTime();log("PROGRESS phase="+phase+" state="+view.state()+" points="+view.points().size()+" slots="+VerificationSlots.count()+" tickets="+CreatureSearch.tickets()+"/"+TeleportService.ticketCount());}
            if(!p.isAlive())throw new AssertionError("player died during phase "+phase);
            switch(phase){
                case 0->{
                    if(!p.serverLevel().dimension().equals(Level.OVERWORLD)){overworld=server.overworld();prepare(overworld,new ChunkPos(overworld.getSharedSpawnPos()));next(19);return;}
                    overworld=p.serverLevel();departure=p.position();
                    p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(20);check(ConfigHandler.GENERAL.allowTeleport.get(),"actual allowTeleport enabled");
                    p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CreatureItem.INSTANCE));
                    var rule=CreatureHabitats.get(overworld,new ResourceLocation("iceandfire:hydra"));
                    check(rule.feature() && !rule.natural() && !rule.biomes().isEmpty(),"hydra uses actual Feature rules");
                    log("ENV mods="+net.minecraftforge.fml.ModList.get().size()+" seed="+overworld.getSeed()+" origin="+departure+" hydra_biomes="+rule.biomes());
                    if(Boolean.getBoolean("unified125.skipCreatures")){
                        p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ExplorersCompass.explorersCompass));
                        aether=server.getLevel(ResourceKey.create(Registries.DIMENSION,new ResourceLocation("aether:the_aether")));check(aether!=null,"actual Aether installed");next(18);
                    }else if(Boolean.getBoolean("unified125.skipHydra")){natural=chooseNatural(overworld);command="search:"+natural;next(3);}else next(1);
                }
                case 1->{if(ticks<20)return;command="search:iceandfire:hydra";next(2);}
                case 2->{
                    if(!view.target().equals("iceandfire:hydra"))return;
                    if(!view.done()){check(elapsed()<240,"hydra observation deadline");return;}
                    check(view.state().equals("found") && !view.points().isEmpty(),"hydra actual result: "+view.state());verify(p,view.points().get(0));log("PASS hydra elapsed_ms="+elapsed()*1000+" "+view.points().get(0));
                    CreatureSearch.cancel(p.getUUID());natural=chooseNatural(overworld);command="search:"+natural;next(3);
                }
                case 3->{
                    if(!view.target().equals(natural))return;
                    if(!view.done()){check(elapsed()<180,"natural target observation deadline");return;}
                    check(view.state().equals("found") && !view.points().isEmpty(),"natural real result: "+view.state());verify(p,view.points().get(0));
                    log("PASS natural type="+natural+" elapsed_ms="+elapsed()*1000+" "+view.points().get(0));
                    if(view.points().size()<2){command="search:"+chooseNatural(overworld);natural=command.substring(7);next(4);}else next(5);
                }
                case 4->{if(!view.target().equals(natural) || !view.done()){check(elapsed()<120,"two real instances available");return;}check(view.points().size()>=2,"need two existing instances for target-switch test");next(5);}
                case 5->{
                    first=view.points().get(0).uuid();second=view.points().get(1).uuid();for(Object owner:blockers)VerificationSlots.acquire(owner);
                    if(VerificationSlots.count()!=4)return;
                    TeleportService.setMode(p.getUUID(),LandingMode.RELAXED_ONLY);command="tp0";next(6);
                }
                case 6->{
                    if(!first.equals(externalIdentity(p)) || view.pending()){check(elapsed()<10,"first request accepted");return;}
                    command="tp1";next(7);
                }
                case 7->{
                    if(!second.equals(externalIdentity(p))){check(elapsed()<10,"second UUID replaces first task");return;}
                    for(Object owner:blockers)VerificationSlots.release(owner);next(8);
                }
                case 8->{
                    if(!view.state().equals("teleport_moved")){check(elapsed()<65,"creature quick teleport");return;}
                    var e=overworld.getEntity(second);check(e!=null && p.distanceToSqr(e)<400,"player arrives beside selected second entity");
                    var point=new Vec3(e.getX()+e.getBbWidth()/2+1,e.getY()-1,e.getZ());
                    var m=CreatureLanding.class.getDeclaredMethod("allowed",ServerPlayer.class,ServerLevel.class,Vec3.class);m.setAccessible(true);
                    check((Boolean)m.invoke(null,p,overworld,point),"relaxed near position allows body collision or immersion");
                    check(!TeleportService.safeAt(p,overworld,ItemStack.EMPTY,point),"strict policy still rejects same position");
                    log("PASS teleport switched_uuid="+second+" xyz="+p.position()+" target="+e.position()+" strict_rejects=true relaxed_accepts=true");
                    CreatureSearch.cancel(p.getUUID());aether=server.getLevel(ResourceKey.create(Registries.DIMENSION,new ResourceLocation("aether:the_aether")));check(aether!=null,"actual Aether installed");
                    prepare(aether,new ChunkPos(0,0));next(9);
                }
                case 9->{
                    if(!CreaturePreExplore.ready(aether,fixtureCenter)){check(elapsed()<65,"aether fixture chunks");return;}
                    long deadline=System.nanoTime()+1_000_000L;
                    int height=aether.getHeight();
                    while(column<40*40*height && System.nanoTime()<deadline){int at=column++,cell=at/height,x=fixtureCenter.getMinBlockX()-12+cell%40,z=fixtureCenter.getMinBlockZ()-12+cell/40,y=aether.getMaxBuildHeight()-1-at%height;
                        var support=new BlockPos(x,y-1,z);var state=aether.getBlockState(support);
                        if(state.isAir() || state.getCollisionShape(aether,support).isEmpty())continue;
                        var pos=TeleportService.relaxed(p,aether,ItemStack.EMPTY,new BlockPos(x,y,z));if(pos==null)continue;aetherFloor=BlockPos.containing(pos);break;}
                    if(aetherFloor==null){
                        if(column>=40*40*height){log("FIXTURE empty actual window center="+fixtureCenter);check(++fixtureAttempts<12,"no protected Aether fixture after twelve predicted ground windows");releaseFixture();column=0;next(18);}return;
                    }
                    aetherBiome=aether.getBiome(aetherFloor).unwrapKey().orElseThrow().location();
                    p.teleportTo(aether,aetherFloor.getX()+.5,aetherFloor.getY()+12,aetherFloor.getZ()+.5,0,0);p.fallDistance=0;
                    p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ExplorersCompass.explorersCompass));
                    check(SearchService.startBiome(p,p.getMainHandItem(),aetherBiome),"normal manual Aether search accepted");next(10);
                }
                case 10->{
                    if(ExplorersCompass.explorersCompass.getState(p.getMainHandItem())!=CompassState.FOUND){check(elapsed()<120,"Aether search");return;}
                    int y=p.getMainHandItem().getTag().getInt(SearchService.FOUND_Y);
                    log("AETHER search_y="+y+" fixture_floor_y="+aetherFloor.getY()+" biome="+aetherBiome);
                    TeleportService.setMode(p.getUUID(),LandingMode.RELAXED_ONLY);
                    check(TeleportService.request(p,p.getMainHandItem(),SearchService.token(p.getMainHandItem()),UUID.randomUUID(),false),"manual Aether teleport request");next(11);
                }
                case 11->{
                    if(!handoff(p)){check(elapsed()<150,"Aether manual height progress");return;}
                    check(aether.getBiome(p.blockPosition()).unwrapKey().orElseThrow().location().equals(aetherBiome),"actual Aether arrival biome");
                    check(TeleportService.safeAt(p,aether,p.getMainHandItem(),p.position()) || TeleportService.relaxed(p,aether,p.getMainHandItem(),p.blockPosition())!=null,"Aether landing protected");
                    log("PASS aether manual_height actual="+p.position()+" biome="+aetherBiome);
                    SearchService.cancel(p,true);prepare(overworld,new ChunkPos(BlockPos.containing(departure)));next(12);
                }
                case 12->{
                    if(!CreaturePreExplore.ready(overworld,fixtureCenter)){check(elapsed()<65,"return fixture chunks");return;}
                    p.teleportTo(overworld,departure.x,departure.y,departure.z,0,0);p.fallDistance=0;p.setHealth(p.getMaxHealth());releaseFixture();
                    SearchService.clearCache(p.getUUID());SearchConfig.ASYNC_STRUCTURES.set(true);
                    var key=new ResourceLocation("cataclysm:sunken_city");check(SearchService.requestStructures(p,p.getMainHandItem(),key,false,false,null),"normal city asynchronous request");currentJob=job(p);asyncMode=currentJob.mode();check(asyncMode.equals("async"),"city really async: "+asyncMode);next(13);
                }
                case 13->{
                    if(ExplorersCompass.explorersCompass.getState(p.getMainHandItem())!=CompassState.FOUND){check(elapsed()<180,"city asynchronous deadline: "+currentJob.stage());return;}
                    asyncCity=result(p);log("PASS city_async mode="+asyncMode+" result="+asyncCity+" elapsed_ms="+elapsed()*1000+" samples="+currentJob.samples()+" checks="+currentJob.confirmations);
                    SearchService.cancel(p,true);SearchService.clearCache(p.getUUID());SearchConfig.ASYNC_STRUCTURES.set(false);
                    check(SearchService.requestStructures(p,p.getMainHandItem(),new ResourceLocation("cataclysm:sunken_city"),false,false,null),"normal city synchronous request");currentJob=job(p);syncMode=currentJob.mode();next(14);
                }
                case 14->{
                    if(ExplorersCompass.explorersCompass.getState(p.getMainHandItem())!=CompassState.FOUND){check(elapsed()<180,"city synchronous deadline: "+currentJob.stage());return;}
                    check(asyncCity.equals(result(p)),"same verified city coordinates");log("PASS city_sync mode="+syncMode+" result="+result(p)+" elapsed_ms="+elapsed()*1000);
                    cleanup(p);check(VerificationSlots.count()==0 && CreatureSearch.tickets()==0 && TeleportService.ticketCount()==0,"all task tickets and slots released");log("PASS cleanup slots=0 tickets=0");command="fresh";next(15);
                }
                case 15->{
                    if(!server.getWorldData().getLevelName().equals("Yuzhe 125 fresh"))return;
                    p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CreatureItem.INSTANCE));p.setHealth(p.getMaxHealth());
                    log("FRESH normal entry seed="+p.serverLevel().getSeed()+" origin="+p.position());
                    target="";command="search:iceandfire:hydra";next(16);
                }
                case 16->{
                    if(!view.target().equals("iceandfire:hydra"))return;
                    if(!view.done()){check(elapsed()<240,"fresh Feature search observation limit");return;}
                    check(view.state().equals("found") && !view.points().isEmpty(),"fresh real Hydra: "+view.state());verify(p,view.points().get(0));
                    log("PASS fresh_hydra elapsed_ms="+elapsed()*1000+" source="+CreatureClient.source+" chunks="+CreatureClient.chunks+" "+view.points().get(0));
                    cleanup(p);finished=(partial?"PARTIAL ":"PASS ")+"all targeted features and fresh Feature path in full Yuzhe copy; see individual results";
                }
                case 18->{
                    check(terrainHint<1024,"no Aether ground hint in bounded fixture grid");int at=terrainHint++,x=(at%32-16)*32+8,z=(at/32-16)*32+8;
                    int height=aether.getChunkSource().getGenerator().getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,aether,aether.getChunkSource().randomState());
                    if(height<aether.getMinBuildHeight()+8)return;
                    log("FIXTURE terrain_hint x="+x+" z="+z+" y="+height);column=0;prepare(aether,new ChunkPos(x>>4,z>>4));next(9);
                }
                case 19->{
                    if(!CreaturePreExplore.ready(overworld,fixtureCenter)){check(elapsed()<65,"normal overworld spawn fixture");return;}
                    var spawn=overworld.getSharedSpawnPos();p.teleportTo(overworld,spawn.getX()+.5,spawn.getY()+1,spawn.getZ()+.5,0,0);p.fallDistance=0;releaseFixture();next(0);
                }
            }
        }catch(Throwable error){
            error.printStackTrace();log("FAIL phase="+phase+" "+error);partial=true;
            if(phase==9 || phase==10 || phase==11 || phase==18){SearchService.cancel(p,true);prepare(overworld,new ChunkPos(BlockPos.containing(departure)));next(12);}
            else if(phase==13 || phase==14){cleanup(p);command="fresh";next(15);}
            else {cleanup(p);finished="FAIL phase="+phase+" "+error;}
        }
    }
    private static String chooseNatural(ServerLevel level){
        var groups=new HashMap<String,Integer>();for(var e:level.getAllEntities()){String id=net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()).toString();if(Set.of("minecraft:squid","minecraft:pig","minecraft:cow","minecraft:sheep","minecraft:chicken").contains(id) && e.isAlive() && CreatureIndex.ready(level,e.blockPosition()))groups.merge(id,1,Integer::sum);}
        return groups.entrySet().stream().filter(e->e.getValue()>=2).max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("minecraft:squid");
    }
    private static void verify(ServerPlayer p,MobPoint point){var e=p.serverLevel().getEntity(point.uuid());var actual=CreatureIndex.point(p.serverLevel(),e);log("VERIFY reported="+point+" actual="+actual+" entity="+(e==null?"null":e.getUUID()+" alive="+e.isAlive()+" ready="+CreatureIndex.ready(p.serverLevel(),e.blockPosition())));check(actual!=null && actual.type().equals(point.type()),"real UUID/type verified");}
    private static BlockPos result(ServerPlayer p){var item=ExplorersCompass.explorersCompass;return new BlockPos(item.getFoundStructureX(p.getMainHandItem()),p.getMainHandItem().getTag().getInt(SearchService.FOUND_Y),item.getFoundStructureZ(p.getMainHandItem()));}
    private static Object field(Object owner,String name)throws Exception{var f=owner instanceof Class<?> c?c.getDeclaredField(name):owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner instanceof Class<?>?null:owner);}
    private static Object task(ServerPlayer p)throws Exception{return ((Map<?,?>)field(TeleportService.class,"tasks")).get(p.getUUID());}
    private static Object externalIdentity(ServerPlayer p)throws Exception{var t=task(p);return t==null?null:((LocatorTarget)field(t,"external")).identity();}
    private static boolean handoff(ServerPlayer p)throws Exception{var t=task(p);return t!=null && (int)field(t,"handoffUntil")>0;}
    private static SearchJob job(ServerPlayer p)throws Exception{return (SearchJob)field(((Map<?,?>)field(SearchService.class,"sessions")).get(p.getUUID()),"job");}
    private static void prepare(ServerLevel level,ChunkPos center){releaseFixture();fixtureLevel=level;fixtureCenter=center;level.getChunkSource().addRegionTicket(SETUP,center,1,fixture);}
    private static void releaseFixture(){if(fixtureLevel!=null){fixtureLevel.getChunkSource().removeRegionTicket(SETUP,fixtureCenter,1,fixture);fixtureLevel=null;}}
    private static void cleanup(ServerPlayer p){for(Object owner:blockers)VerificationSlots.release(owner);CreatureSearch.cancel(p.getUUID());SearchService.cancel(p,true);TeleportService.cancel(p.getUUID());releaseFixture();SearchConfig.ASYNC_STRUCTURES.set(true);}
    private static void next(int step){phase=step;started=System.nanoTime();}
    private static double elapsed(){return (System.nanoTime()-started)/1e9;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void log(String line){try{Files.writeString(Path.of("targeted125-results.txt"),line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception error){throw new IllegalStateException(error);}}
}
