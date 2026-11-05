package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.unified.creature.CreatureItem;
import com.chaosthedude.explorerscompass.unified.creature.client.*;
import com.chaosthedude.explorerscompass.unified.creature.search.*;
import com.chaosthedude.explorerscompass.unified.search.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import static com.chaosthedude.explorerscompass.unified.smoke.SmokeChecks.check;

/** 仅本次功能计时，不进入发布包；不会召唤、强制刷怪或自动巡游。 */
@Mod.EventBusSubscriber(modid="filterschecks",value=net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class Fresh123Checks {
    private static final String MODE=System.getProperty("unified.fresh123","");
    private static final String[] TARGETS={"alexsmobs:flutter","alexsmobs:sunbird","minecraft:turtle","minecraft:squid","alexsmobs:gelada_monkey"};
    private static final List<String> notes=new CopyOnWriteArrayList<>();
    private static volatile Throwable failure;
    private static volatile boolean prepared,checked,scopeDone;
    private static int phase,ticks,wait,index,pauseCount;
    private static boolean retestFlutter; private static boolean second; private static int visits; private static boolean declined; private static long lastGuide;
    private static SearchJob probeJob;
    private static long started,overall=System.nanoTime(),lastWrite;
    private Fresh123Checks() {}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(MODE.isEmpty() || event.phase!=TickEvent.Phase.END || phase==100)return;
        var mc=Minecraft.getInstance();ticks++;
        try{
            if(failure!=null)throw new AssertionError("server diagnostic",failure);
            check(System.nanoTime()-overall<1_800_000_000_000L,"diagnostic deadline");if(ticks<wait)return;
            if(phase==0){
                if(mc.screen instanceof net.minecraftforge.client.gui.LoadingErrorScreen screen){check(((List<?>)field(screen,"modLoadErrors")).isEmpty(),"startup errors");mc.setScreen(new TitleScreen());}
                if(mc.screen instanceof AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());if(!(mc.screen instanceof TitleScreen))return;
                mc.options.pauseOnLostFocus=false;mc.options.framerateLimit().set(60);mc.options.renderDistance().set(3);mc.options.simulationDistance().set(5);mc.options.guiScale().set(2);mc.options.languageCode="zh_cn";mc.resizeDisplay();
                if(MODE.equals("guide124verify")){mc.createWorldOpenFlows().loadLevel(new TitleScreen(),"fresh-creature123-guide124fix");next(1,120);return;}
                if(MODE.contains("turtle")){index=2;mc.createWorldOpenFlows().loadLevel(new TitleScreen(),"fresh-creature123-guide124fix");next(1,120);return;}
                if(MODE.equals("probe")){mc.createWorldOpenFlows().loadLevel(new TitleScreen(),"fresh-creature123-compare-preexplore");next(1,120);return;}
                mc.createWorldOpenFlows().createFreshLevel("fresh-creature123-"+MODE+(second?"-preexplore":""),new LevelSettings("Fresh 1.2.3 "+MODE,GameType.SURVIVAL,false,Difficulty.NORMAL,false,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(-3744940466531364533L,true,false),WorldPresets::createNormalWorldDimensions);next(1,120);return;
            }
            if(mc.player==null || mc.getSingleplayerServer()==null)return;
            var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();if(MODE.startsWith("guide124") && !mc.player.isAlive()){finish(mc,"FAILED player died during observation");return;}
            if(System.nanoTime()-lastWrite>2_000_000_000L){lastWrite=System.nanoTime();write("RUNNING phase="+phase+" target="+TARGETS[Math.min(index,4)]+" state="+CreatureClient.state+" source="+CreatureClient.source+" chunks="+CreatureClient.chunks+" anchor="+CreatureClient.anchorSamples+" mode="+CreatureClient.anchorMode+" cap="+CreatureClient.spawnPopulation+"/"+CreatureClient.spawnCapacity+" player="+mc.player.position()+" pre="+CreaturePreExploreClient.state+" "+CreaturePreExploreClient.prepared+"/"+CreaturePreExploreClient.total+"\n"+String.join("\n",notes),"progress");}
            switch(phase){
                case 1->{server.execute(()->guard(()->{var p=server.getPlayerList().getPlayer(uuid);p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CreatureItem.INSTANCE));if(!MODE.contains("turtle") && !MODE.equals("guide124verify"))p.teleportTo(-5.5,87,-9.5);
                    notes.add("WORLD preexplored="+second+" seed="+p.serverLevel().getSeed()+" origin="+p.position()+" mods="+net.minecraftforge.fml.ModList.get().size()+" loaded_chunks="+p.serverLevel().getChunkSource().getLoadedChunksCount());if(MODE.contains("turtle") || MODE.equals("guide124verify"))scopeDone=true;else scopeCheck(p.serverLevel());prepared=true;}));next(2,5);}
                case 2->{if(!prepared || !scopeDone)return;com.chaosthedude.explorerscompass.unified.creature.CreatureConfig.CLIENT.creatureDimension.set("minecraft:overworld");com.chaosthedude.explorerscompass.unified.creature.CreatureConfig.CLIENT.creatureBiome.set("");com.chaosthedude.explorerscompass.unified.creature.CreatureConfig.CLIENT.creatureRounds.set(4);CreatureClient.open();next(MODE.equals("probe")?33:MODE.equals("preexplore") || second?20:3,10);}
                case 3->{if(CreatureClient.entries.isEmpty())return;checked=false;int current=index;server.execute(()->guard(()->{
                    var p=server.getPlayerList().getPlayer(uuid);var type=new ResourceLocation(TARGETS[current]);var loaded=new ArrayList<String>();
                    for(var e:p.serverLevel().getAllEntities())if(type.equals(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType())))loaded.add(e.getUUID()+" xyz="+e.position()+" alive="+e.isAlive()+" ready="+CreatureIndex.ready(p.serverLevel(),e.blockPosition()));
                    notes.add("REQUEST type="+type+" origin="+p.position()+" loaded="+loaded);checked=true;}));next(8,1);}
                case 8->{if(!checked)return;checked=false;var screen=(CreatureScreen)mc.screen;var target=new ResourceLocation(TARGETS[index]);
                    ((EditBox)field(screen,"input")).setValue(target.toString());screen.tick();var rows=(List<?>)field(screen,"matches");check(rows.size()==1,"unique card "+target);
                    int left=(int)method(screen,"sidebar")+16;screen.mouseClicked(left+36,93,0);((Button)field(screen,"search")).onPress();started=System.nanoTime();next(4,1);}
                case 4->{long elapsed=System.nanoTime()-started;
                    if(MODE.startsWith("guide124") && CreatureClient.habitatReady && System.nanoTime()-lastGuide>1_000_000_000L){
                        if(!(mc.screen instanceof CreatureScreen))CreatureClient.open();
                        notes.add("HABITAT type="+TARGETS[index]+" elapsed_ms="+elapsed/1e6+" state="+CreatureClient.state+" confirmation="+CreatureClient.confirmation+" player="+mc.player.position());
                        check(CreatureClient.points.isEmpty(),"habitat not fake entity");Screenshot.grab(mc.gameDirectory,"guide124-habitat-"+index+".png",mc.getMainRenderTarget(),m->{});((Button)field(mc.screen,"teleport")).onPress();
                        check(mc.screen instanceof ConfirmScreen,"explicit visit consent UI");
                        var buttons=mc.screen.children().stream().filter(c->c instanceof Button).map(c->(Button)c).toList();
                        check(buttons.size()==2,"yes/no confirmation");buttons.get(declined?0:1).onPress();
                        if(!declined){declined=true;check(CreatureClient.habitatReady,"decline keeps waiting");}
                        else visits++;
                        lastGuide=System.nanoTime();return;
                    }
                    long max=MODE.startsWith("guide124")?360_000_000_000L:60_000_000_000L;
                    if(!CreatureClient.finished && elapsed<max)return;
                    boolean limited=!CreatureClient.finished;String state=limited?"OBSERVATION_LIMIT":CreatureClient.state;var points=List.copyOf(CreatureClient.points);int current=index;
                    String info="RESULT visits="+visits+" preexplored="+second+" type="+TARGETS[current]+" state="+state+" elapsed_ms="+elapsed/1e6+" source="+CreatureClient.source+" samples="+CreatureClient.anchorSamples+" mode="+CreatureClient.anchorMode+" stored="+CreatureClient.storedScanned+"/"+CreatureClient.storedTotal+" chunks="+CreatureClient.chunks+" points="+points.size()+" anchor_ms="+CreatureClient.anchorMillis+" storage_ms="+CreatureClient.storageMillis+" chunk_wait_ms="+CreatureClient.chunkWaitMillis;
                    if(state.equals("found")){server.execute(()->guard(()->{var p=server.getPlayerList().getPlayer(uuid);var real=CreatureIndex.point(p.serverLevel(),p.serverLevel().getEntity(points.get(0).uuid()));check(real!=null && real.type().toString().equals(TARGETS[current]),"real UUID target");notes.add(info+" uuid="+real.uuid()+" xyz="+real.x()+","+real.y()+","+real.z()+" biome="+real.biome());checked=true;}));}
                    else{notes.add(info);checked=true;}CreatureClient.stop();next(5,10);}
                case 5->{if(!checked)return;checked=false;visits=0;index++;if(MODE.contains("turtle") && MODE.contains("flutter")){if(retestFlutter)index=5;else if(index==3){index=0;retestFlutter=true;}}if(index<(MODE.contains("turtle")?3:TARGETS.length)){CreatureClient.open();next(3,10);}else next(MODE.equals("preexplore") || second?30:6,20);}
                case 6->{if(MODE.equals("guide124verify"))check(notes.stream().filter(n->n.startsWith("RESULT") && n.contains("state=found ")).count()==5,"all five real creature results");server.execute(()->guard(()->{check(CreatureSearch.tickets()==0 && CreaturePreExplore.tickets()==0,"all tickets released");notes.add("CLEANUP tickets=0 reads="+SavedCreatureIndex.inFlight());checked=true;}));next(7,5);}
                case 7->{if(!checked)return;if(MODE.equals("compare") && !second){second=true;checked=prepared=scopeDone=false;index=0;mc.level.disconnect();mc.clearLevel(new TitleScreen());next(0,40);}else finish(mc,"MEASURED");}
                case 20->{if(CreatureClient.entries.isEmpty())return;((Button)field(mc.screen,"exploreButton")).onPress();next(21,10);}
                case 21->{check(mc.screen instanceof CreaturePreExploreScreen,"settings UI");check(((EditBox)field(mc.screen,"radius")).getValue().equals("512"),"default radius512");((Button)field(mc.screen,"start")).onPress();started=System.nanoTime();next(22,1);}
                case 22->{if(CreaturePreExploreClient.finished)return;check(System.nanoTime()-started<600_000_000_000L,"preexplore limit");
                    if(CreaturePreExploreClient.prepared>0){((Button)field(mc.screen,"pause")).onPress();next(23,10);} }
                case 23->{if(!CreaturePreExploreClient.paused || !CreaturePreExploreClient.state.equals("paused"))return;
                    check(CreaturePreExplore.tickets()==0,"paused in-flight drained");pauseCount=CreaturePreExploreClient.prepared;
                    Screenshot.grab(mc.gameDirectory,"fresh123-preexplore-paused.png",mc.getMainRenderTarget(),m->{});next(24,30);}
                case 24->{check(CreaturePreExploreClient.prepared==pauseCount,"no new work while paused");notes.add("PAUSE stable_prepared="+pauseCount+" tickets=0");((Button)field(mc.screen,"pause")).onPress();next(25,1);}
                case 25->{check(System.nanoTime()-started<600_000_000_000L,"preexplore total deadline");if(!CreaturePreExploreClient.finished)return;check(CreaturePreExploreClient.state.equals("complete"),"preexplore completes: "+CreaturePreExploreClient.state);
                    check(CreaturePreExploreClient.prepared==CreaturePreExploreClient.total,"complete coverage");notes.add("PREEXPLORE radius="+CreaturePreExploreClient.radius+" elapsed_ms="+CreaturePreExploreClient.elapsedMillis+" prepared="+CreaturePreExploreClient.prepared+" dependencies="+CreaturePreExploreClient.dependencies+" registered="+CreaturePreExploreClient.entities);
                    Screenshot.grab(mc.gameDirectory,"fresh123-preexplore-complete.png",mc.getMainRenderTarget(),m->{});mc.screen.onClose();next(3,10);}
                case 30->{CreaturePreExploreClient.command("start",1024);next(31,1);}
                case 31->{if(CreaturePreExploreClient.pending() || CreaturePreExploreClient.radius!=1024 || CreaturePreExplore.tickets()==0)return;check(!CreaturePreExploreClient.finished,"stop test still active");CreaturePreExploreClient.command("stop",0);next(32,10);}
                case 32->{check(CreaturePreExploreClient.finished && CreaturePreExplore.tickets()==0,"stop releases task ticket");notes.add("STOP tickets=0");next(6,10);}
                case 33->{checked=false;server.execute(()->guard(()->{var p=server.getPlayerList().getPlayer(uuid);var l=p.serverLevel();var at=p.blockPosition();var id=l.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(at.getX()>>2,at.getY()>>2,at.getZ()>>2,l.getChunkSource().randomState().sampler()).unwrapKey().orElseThrow().location();
                    probeJob=BiomeQueryFactory.habitats(l,at,Set.of(id),Set.of(),512,10000,true,com.chaosthedude.explorerscompass.unified.creature.CreatureConfig.GENERAL.snapshot());probeJob.prime();}));next(34,5);}
                case 34->{if(checked){next(30,5);return;}server.execute(()->guard(()->{if(checked || probeJob==null)return;probeJob.step();if(probeJob.nextCandidate()!=null){check(probeJob.mode().equals("async"),"scoped normal habitat entry");notes.add("HABITAT_PROBE mode=async found=true samples="+probeJob.samples());probeJob.cancel();checked=true;}}));}
            }
        }catch(Throwable e){e.printStackTrace();finish(mc,"FAILED phase="+phase+" "+e);}
    }
    private static void scopeCheck(net.minecraft.server.level.ServerLevel level)throws Exception{
        BiomeQueryFactory.prepare(level,level.getSharedSpawnPos(),new ResourceLocation("minecraft:plains"),com.chaosthedude.explorerscompass.unified.creature.CreatureConfig.GENERAL.snapshot());
        var source=level.getChunkSource().getGenerator().getBiomeSource();var sampler=level.getChunkSource().randomState().sampler();
        source.getNoiseBiome(0,20,0,sampler); // 正常官方查询先建立真实种子基线，不能拿未初始化的42作断言。
        var owner=Class.forName("com.github.alexmodguy.alexscaves.server.level.biome.ACBiomeRarity");var f=owner.getDeclaredField("VORONOI_GENERATOR");f.setAccessible(true);var original=f.get(null);var seed=original.getClass().getMethod("getSeed");long before=((Number)seed.invoke(original)).longValue();
        var prepare=AlexBiomeScope.class.getDeclaredMethod("prepare",long.class);prepare.setAccessible(true);var scope=(AlexBiomeScope.Context)prepare.invoke(null,level.getSeed());
        if(scope==null){scopeDone=true;return;}
        var rarity=Class.forName("com.github.alexmodguy.alexscaves.server.level.biome.ACBiomeRarity").getMethod("getRareBiomeInfoForQuad",long.class,int.class,int.class);
        var expected=new ArrayList<List<Object>>();
        for(int i=0;i<768;i++){int at=i,x=i*137-48000,z=24000-i*311,y=(i%80)-16;
            Object rare=scope.with(()->{try{return rarity.invoke(null,42L+at%7,x,z);}catch(Exception e){throw new IllegalStateException(e);}});
            expected.add(Arrays.asList(rare,source.getNoiseBiome(x,y,z,sampler)));}
        var pool=Executors.newFixedThreadPool(4);var jobs=new ArrayList<CompletableFuture<Void>>();
        for(int worker=0;worker<4;worker++){int lane=worker;jobs.add(CompletableFuture.runAsync(()->{
            for(int i=lane;i<768;i+=4){int at=i,x=i*137-48000,z=24000-i*311,y=(i%80)-16;
                var actual=scope.with(()->{try{return Arrays.asList(rarity.invoke(null,42L+at%7,x,z),source.getNoiseBiome(x,y,z,sampler));}catch(Exception e){throw new IllegalStateException(e);}});
                check(expected.get(i).equals(actual),"serial/parallel biome mismatch "+i);
            }
        },pool));}
        CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).whenComplete((v,e)->{pool.shutdown();if(e!=null)failure=e;else{try{check(((Number)seed.invoke(original)).longValue()==before,"probe must not change original seed");notes.add("ALEX_SCOPE serial_parallel=768 mismatches=0 workers=4 original_seed_unchanged="+before);scopeDone=true;}catch(Throwable failureInProbe){failure=failureInProbe;}}});
    }
    private static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static Object method(Object o,String name)throws Exception{var m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(o);}
    private static void next(int p,int delay){phase=p;wait=ticks+delay;}
    private interface Checked{void run()throws Exception;}
    private static void guard(Checked action){try{action.run();}catch(Throwable e){failure=e;}}
    private static void write(String text,String kind)throws Exception{Files.writeString(Path.of("fresh123-"+MODE+"-"+kind+".txt"),text+"\n");}
    private static void finish(Minecraft mc,String state){phase=100;try{write(state+"\n"+String.join("\n",notes),"results");}catch(Exception e){throw new IllegalStateException(e);}mc.stop();}
}
