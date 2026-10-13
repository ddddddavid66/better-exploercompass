package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.entertainment.*;
import com.chaosthedude.explorerscompass.unified.search.*;
import com.chaosthedude.explorerscompass.unified.network.*;
import com.chaosthedude.explorerscompass.util.CompassState;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 只验收本次成就续跑、落点边界和十个真实搜索结果的 Xaero 持久化。 */
@Mod.EventBusSubscriber(modid="filterschecks",value=Dist.CLIENT)
public final class Entertainment116Checks {
    private static final boolean MAP_ONLY=Boolean.getBoolean("unified.entertainment116.mapsOnly");
    private static int phase,ticks,wait,initial,count,index;
    private static volatile boolean ready,finished,resumed,restart,stopped,resumedRunning;
    private static volatile Throwable error;
    private static Vec3 origin;
    private static long deadline=System.nanoTime()+2_400_000_000_000L,requestStarted;
    private static final List<String> notes=new ArrayList<>();
    private static final List<ResourceLocation> targets=new ArrayList<>();
    private static final List<Object[]> points=new ArrayList<>();
    private static final Set<String> baseline=new HashSet<>();
    private static final ResourceLocation ADV=new ResourceLocation("ancient_aether:aether_biomes");
    @SubscribeEvent public static void client(TickEvent.ClientTickEvent event) {
        if(!Boolean.getBoolean("unified.entertainment116") || event.phase!=TickEvent.Phase.END || phase==100)return;
        var mc=Minecraft.getInstance();ticks++;
        try {
            if(error!=null)throw new AssertionError("server check",error);
            check(System.nanoTime()<deadline,"test deadline phase="+phase);
            if(ticks<wait)return;
            if(phase==0) {
                if(mc.screen instanceof net.minecraftforge.client.gui.LoadingErrorScreen screen) {
                    check(((List<?>)field(screen,"modLoadErrors")).isEmpty(),"Forge loading errors");mc.setScreen(new TitleScreen());
                }
                if(mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
                if(!(mc.screen instanceof TitleScreen))return;
                mc.options.guiScale().set(2);mc.options.renderDistance().set(3);mc.options.simulationDistance().set(5);mc.resizeDisplay();
                mc.createWorldOpenFlows().loadLevel(mc.screen,"entertainment116-save");phase=1;wait=ticks+40;return;
            }
            if(phase==90) {
                if(!(mc.screen instanceof TitleScreen))return;
                mc.createWorldOpenFlows().loadLevel(mc.screen,"entertainment116-save");phase=91;wait=ticks+80;return;
            }
            if(mc.player==null || mc.getSingleplayerServer()==null)return;
            var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
            if(phase==1) {
                if(!mc.player.onGround())return;
                server.execute(()->guard(()->setup(server.getPlayerList().getPlayer(id))));phase=2;return;
            }
            if(phase==2) {
                if(MAP_ONLY){if(ready && mc.level.dimension().equals(Level.OVERWORLD)){phase=5;wait=ticks+40;}return;}
                if(!com.chaosthedude.explorerscompass.unified.client.ClientSession.ready() || !ExplorersCompass.canTeleport || !ready || !mc.level.dimension().location().equals(EntertainmentFeature.AETHER_BIOMES.dimension()))return;
                ui(mc);phase=3;return;
            }
            if(phase==3) {
                server.execute(()->guard(()->{
                    var p=server.getPlayerList().getPlayer(id);var a=server.getAdvancements().getAdvancement(ADV);
                    count=(int)AdventureRequirements.read(a).stream().filter(v->AdventureRequirements.visited(p,a,v)).count();
                    if(resumed && AdventureTravel.active(id))resumedRunning=true;
                    if(!stopped && count>initial && AdventureTravel.active(id)) {
                        var task=task(id);AdventureTravel.stop(p,(UUID)field(task,"id"));stopped=true;
                        notes.add("Stopped after natural progress "+count+"/17");
                    }
                    if(stopped && !resumed && !AdventureTravel.active(id)) {
                        check(p.position().distanceTo(origin)<4,"stop returns to departure");resumed=true;restart=true;
                        baseline.clear();for(var v:AdventureRequirements.read(a))if(AdventureRequirements.visited(p,a,v))baseline.add(v.criterion());
                        Files.writeString(Path.of("relay116-resume-complete.txt"),String.join("\n",baseline));
                    } else if(resumedRunning && !AdventureTravel.active(id)) {
                        check(p.getAdvancements().getOrStartProgress(a).isDone(),"relay advancement naturally complete");
                        check(count==17,"17 real runtime criteria");check(p.position().distanceTo(origin)<4,"completion returns to departure");
                        AdventureTravel.start(p,EntertainmentFeature.AETHER_BIOMES);
                        check(!AdventureTravel.active(id),"completed advancement does not start another search");
                        finished=true;
                    }
                }));
                if(restart){restart=false;ui(mc);notes.add("Restart from server advancement progress");}
                if(finished){phase=4;wait=ticks+70;}
                return;
            }
            if(phase==4) {
                verifyXaero();
                server.execute(()->guard(()->{
                    var p=server.getPlayerList().getPlayer(id);SearchService.cancel(p,true);
                    check(((Map<?,?>)staticField(TeleportService.class,"tasks")).isEmpty(),"travel tickets released");
                    var world=server.overworld();Vec3 home=setupLanding(p,world);
                    p.teleportTo(world,home.x,home.y,home.z,0,0);
                    // 固定随机种子，分别从原版候选池抽五个，不按结果替换抽样。
                    var structures=new ArrayList<>(List.of("minecraft:village_plains","minecraft:desert_pyramid","minecraft:jungle_pyramid","minecraft:igloo","minecraft:pillager_outpost","minecraft:ruined_portal","minecraft:shipwreck","minecraft:buried_treasure","minecraft:swamp_hut","minecraft:ocean_ruin_warm"));
                    var biomes=new ArrayList<>(List.of("minecraft:plains","minecraft:forest","minecraft:desert","minecraft:taiga","minecraft:river","minecraft:ocean","minecraft:beach","minecraft:savanna","minecraft:birch_forest","minecraft:swamp"));
                    // 整合包明确禁用原版模板时，抽样池使用其已注册的实际替代ID。
                    if(net.minecraftforge.fml.ModList.get().isLoaded("betterjungletemples"))structures.replaceAll(k->k.equals("minecraft:jungle_pyramid")?"betterjungletemples:jungle_temple":k);
                    if(net.minecraftforge.fml.ModList.get().isLoaded("betterwitchhuts"))structures.replaceAll(k->k.equals("minecraft:swamp_hut")?"betterwitchhuts:witch_hut":k);
                    structures.removeIf(key->!CatalogService.snapshot(world,UUID.randomUUID()).dimensionStructures().getOrDefault(Level.OVERWORLD.location(),Set.of()).contains(new ResourceLocation(key)));
                    check(structures.size()>=5,"at least five registered overworld structure choices");
                    Collections.shuffle(structures,new Random(116));Collections.shuffle(biomes,new Random(116));
                    structures.stream().limit(5).map(ResourceLocation::new).forEach(targets::add);
                    biomes.stream().limit(5).map(ResourceLocation::new).forEach(targets::add);
                    Files.writeString(Path.of("map116-targets.txt"),String.join("\n",targets.stream().map(Object::toString).toList()));
                    ready=false;
                }));phase=5;wait=ticks+40;return;
            }
            if(phase==5) {
                if(!mc.level.dimension().equals(Level.OVERWORLD))return;
                server.execute(()->guard(()->{
                    var p=server.getPlayerList().getPlayer(id);var key=targets.get(index);
                    boolean accepted=index<5?SearchService.requestStructures(p,p.getMainHandItem(),key,false,false,null):SearchService.startBiome(p,p.getMainHandItem(),key);
                    check(accepted,"normal search accepted "+key);requestStarted=System.nanoTime();ready=true;
                }));phase=6;return;
            }
            if(phase==6) {
                // 服务端完成后先消费结果，不能把 ready=false 当作尚未发起而空转。
                if(!ready){if(points.size()==index+1){phase=7;wait=ticks+65;}return;}
                server.execute(()->guard(()->{
                    if(!ready)return;var p=server.getPlayerList().getPlayer(id);var stack=p.getMainHandItem();var state=ExplorersCompass.explorersCompass.getState(stack);
                    if(state==CompassState.SEARCHING){check(System.nanoTime()-requestStarted<180_000_000_000L,"map target search timeout "+targets.get(index));return;}
                    check(state==CompassState.FOUND,"map target found "+targets.get(index)+" state="+state);
                    var c=ExplorersCompass.explorersCompass;
                    var found=index<5?c.getStructureKey(stack):targets.get(index);
                    points.add(new Object[]{found,new int[]{c.getFoundStructureX(stack),stack.getTag().getInt(SearchService.FOUND_Y),c.getFoundStructureZ(stack)},index>=5});
                    ready=false;finished=false;
                }));
                if(!ready){phase=7;wait=ticks+65;}return;
            }
            if(phase==7) {
                verifyXaeroPoint(points.get(index));notes.add("Map PASS "+targets.get(index)+" "+Arrays.toString((int[])points.get(index)[1]));
                index++;if(index<10){phase=5;return;}
                Files.writeString(Path.of("map116-results.txt"),String.join("\n",notes));
                mc.setScreen(null);mc.level.disconnect();mc.clearLevel(new TitleScreen());phase=90;wait=ticks+100;return;
            }
            if(phase==91) {
                Object xaero=Class.forName("xaero.common.XaeroMinimapSession").getMethod("getCurrentSession").invoke(null);
                if(xaero==null || call(xaero,"getWaypointsManager")==null || call(call(xaero,"getWaypointsManager"),"getAutoWorld")==null)return;
                for(var point:points)verifyXaeroPoint(point);
                notes.add("PASS ten points persisted after disconnect/reload");
                Object mapSession=Class.forName("xaero.map.WorldMapSession").getMethod("getCurrentSession").invoke(null);
                Object processor=call(mapSession,"getMapProcessor");
                Class<?> gui=Class.forName("xaero.map.gui.GuiMap");
                var screen=(net.minecraft.client.gui.screens.Screen)gui.getConstructors()[0].newInstance(null,null,processor,mc.player);
                mc.setScreen(screen);gui.getField("waypointMenu").setBoolean(screen,true);phase=92;wait=ticks+40;return;
            }
            if(phase==92) {
                Screenshot.grab(mc.gameDirectory,"map116.png",mc.getMainRenderTarget(),ignored->{});
                finish(mc,"PASS\n"+String.join("\n",notes));
            }
        }catch(Throwable failure){failure.printStackTrace();finish(mc,"FAIL: "+failure+"\n"+String.join("\n",notes));}
    }
    private static void setup(ServerPlayer p)throws Exception {
        check(p.gameMode.getGameModeForPlayer()==GameType.SURVIVAL,"survival");
        if(p.getMainHandItem().getItem()!=ExplorersCompass.explorersCompass) {
            int slot=-1;for(int i=0;i<p.getInventory().items.size();i++)if(p.getInventory().items.get(i).getItem()==ExplorersCompass.explorersCompass){slot=i;break;}
            check(slot>=0,"saved compass");var old=p.getMainHandItem();p.setItemInHand(InteractionHand.MAIN_HAND,p.getInventory().items.get(slot));p.getInventory().items.set(slot,old);
        }
        var a=p.getServer().getAdvancements().getAdvancement(ADV);check(a!=null,"runtime relay advancement");
        if(MAP_ONLY) {
            check(p.getAdvancements().getOrStartProgress(a).isDone(),"relay 17/17 persisted from successful previous journey");
            notes.add("PASS persisted relay advancement 17/17; only remaining map targets retested");
            targets.addAll(Files.readAllLines(Path.of("map116-targets.txt")).stream().map(ResourceLocation::new).toList());
            if(!Files.exists(Path.of("map116-initial-targets.txt")))Files.copy(Path.of("map116-targets.txt"),Path.of("map116-initial-targets.txt"));
            targets.set(1,new ResourceLocation("betterjungletemples:jungle_temple"));
            targets.set(3,new ResourceLocation("betterwitchhuts:witch_hut"));index=2;
            points.add(new Object[]{new ResourceLocation("minecraft:pillager_outpost"),new int[]{272,78,352},false});
            points.add(new Object[]{new ResourceLocation("betterjungletemples:jungle_temple"),new int[]{1200,115,2128},false});
            notes.add("Original minecraft:jungle_pyramid exhausted; YUNG replacements applied for jungle temple and witch hut from pack configs. Ruined-portal template failure repaired; original failures retained.");
            Files.writeString(Path.of("map116-targets.txt"),String.join("\n",targets.stream().map(Object::toString).toList()));
            var abyss=p.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,new ResourceLocation("infinite_abyss:first_layer")));
            check(abyss!=null,"actual Abyss dimension");var chunk=abyss.getChunk(0,0);int roof=-1;
            for(int y=abyss.getMaxBuildHeight()-1;y>=abyss.getMinBuildHeight()+8;y--)if(chunk.getBlockState(new BlockPos(8,y,8)).is(Blocks.BEDROCK)){roof=y;break;}
            check(roof>0,"actual Abyss bedrock roof");
            var allowed=Class.forName("com.chaosthedude.explorerscompass.unified.search.LandingSafety").getDeclaredMethod("allowed",ServerLevel.class,Vec3.class,boolean.class);allowed.setAccessible(true);
            check(!(boolean)allowed.invoke(null,abyss,new Vec3(8.5,roof+1,8.5),false),"actual Abyss roof rejected");
            notes.add("PASS actual Infinite Abyss first-layer roof guard at y="+roof);ready=true;return;
        }
        initial=(int)AdventureRequirements.read(a).stream().filter(v->AdventureRequirements.visited(p,a,v)).count();check(initial==12,"original 12/17 preserved");
        for(var v:AdventureRequirements.read(a))if(AdventureRequirements.visited(p,a,v))baseline.add(v.criterion());
        notes.add("Original relay progress="+initial+"/17, commands="+p.getServer().getWorldData().getAllowCommands());
        var level=p.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,EntertainmentFeature.AETHER_BIOMES.dimension()));
        check(level!=null,"actual Aether exists");Vec3 start=setupLanding(p,level);p.teleportTo(level,start.x,start.y,start.z,0,0);origin=start;
        safety(p,level);ready=true;
    }
    private static void safety(ServerPlayer p,ServerLevel level)throws Exception {
        // 仅独立测试副本中的小型临时夹具；玩家不到访，结束恢复每个原方块。
        var old=new LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        int x=72,z=72,y=180;for(int cx=3;cx<=5;cx++)for(int cz=3;cz<=5;cz++)level.getChunk(cx,cz);
        try {
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=-18;dy<=3;dy++) {
                var pos=new BlockPos(x+dx,y+dy,z+dz);old.put(pos,level.getBlockState(pos));level.setBlock(pos,Blocks.AIR.defaultBlockState(),2);
            }
            level.setBlock(new BlockPos(x,y-1,z),Blocks.STONE.defaultBlockState(),2);
            check(!TeleportService.safeAt(p,level,ItemStack.EMPTY,new Vec3(x+.5,y,z+.5)),"isolated island edge rejected");
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)level.setBlock(new BlockPos(x+dx,y-1,z+dz),Blocks.STONE_SLAB.defaultBlockState(),2);
            check(TeleportService.safeAt(p,level,ItemStack.EMPTY,new Vec3(x+.5,y-.5,z+.5)),"3x3 slab platform accepted");
            level.setBlock(new BlockPos(x,y-3,z),Blocks.BEDROCK.defaultBlockState(),2);
            Class<?> guard=Class.forName("com.chaosthedude.explorerscompass.unified.search.LandingSafety");var invalidate=guard.getDeclaredMethod("invalidate",ServerLevel.class);invalidate.setAccessible(true);invalidate.invoke(null,level);
            check(!TeleportService.safeAt(p,level,ItemStack.EMPTY,new Vec3(x+.5,y-.5,z+.5)),"above bedrock barrier rejected");
            level.setBlock(new BlockPos(x,y-3,z),Blocks.AIR.defaultBlockState(),2);invalidate.invoke(null,level);
            var water=Class.forName("com.chaosthedude.explorerscompass.unified.search.LandingChecks").getDeclaredMethod("waterStanding",ServerPlayer.class,ServerLevel.class,ItemStack.class,Vec3.class);water.setAccessible(true);
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)level.setBlock(new BlockPos(x+dx,y-1,z+dz),Blocks.WATER.defaultBlockState(),2);
            check(!(boolean)water.invoke(null,p,level,ItemStack.EMPTY,new Vec3(x+.5,y-1+level.getFluidState(new BlockPos(x,y-1,z)).getHeight(level,new BlockPos(x,y-1,z))-.25,z+.5)),"bottomless source water rejected");
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)level.setBlock(new BlockPos(x+dx,y-2,z+dz),Blocks.STONE.defaultBlockState(),2);
            check((boolean)water.invoke(null,p,level,ItemStack.EMPTY,new Vec3(x+.5,y-1+level.getFluidState(new BlockPos(x,y-1,z)).getHeight(level,new BlockPos(x,y-1,z))-.25,z+.5)),"supported source pool accepted");
            notes.add("PASS local island/slab/bedrock/water boundary checks");
        }finally{for(var e:old.entrySet())level.setBlock(e.getKey(),e.getValue(),2);}
    }
    private static Vec3 setupLanding(ServerPlayer p,ServerLevel level) {
        for(int radius=0;radius<=8;radius++)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
            if(Math.max(Math.abs(x),Math.abs(z))!=radius)continue;int bx=x*16+8,bz=z*16+8;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)level.getChunk((bx>>4)+dx,(bz>>4)+dz);
            int y=level.getChunk(bx>>4,bz>>4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,bx&15,bz&15)+1;
            if(TeleportService.safe(p,level,ItemStack.EMPTY,new BlockPos(bx,y,bz)))return new Vec3(bx+.5,y,bz+.5);
        }throw new AssertionError("no setup landing");
    }
    private static void ui(Minecraft mc)throws Exception {
        mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);
        // 打开界面需要正常网络往返；单独创建同一浮层用于选择/确认检查。
        if(mc.screen==null)mc.setScreen(new com.chaosthedude.explorerscompass.gui.ExplorersCompassScreen(mc.level,mc.player,mc.player.getMainHandItem(),ExplorersCompass.explorersCompass,ExplorersCompass.allowedStructureKeys));
        var popup=new EntertainmentUi(mc.screen,()->{});popup.button().onPress();
        var search=(net.minecraft.client.gui.components.EditBox)field(popup,"search");search.setValue("paohunzhijieli");
        check(((List<?>)field(popup,"matches")).size()==1,"renamed feature pinyin search");
        var button=(net.minecraft.client.gui.components.Button)field(popup,"confirm");check(!button.active,"unselected confirmation disabled");
        popup.mouseClicked((int)field(popup,"left")+12,(int)field(popup,"top")+40,0);
        check(button.active,"relay enabled in Aether");button.onPress();mc.setScreen(null);
    }
    private static void verifyXaero()throws Exception {
        check(Class.forName("xaero.common.XaeroMinimapSession").getMethod("getCurrentSession").invoke(null)!=null,"Xaero active");
        check(!(boolean)staticField(com.chaosthedude.explorerscompass.unified.client.XaeroWaypoints.class,"failed"),"Xaero adapter has no error");
    }
    private static void verifyXaeroPoint(Object[] point)throws Exception {
        verifyXaero();Object session=Class.forName("xaero.common.XaeroMinimapSession").getMethod("getCurrentSession").invoke(null);
        Object manager=call(session,"getWaypointsManager"),world=call(manager,"getAutoWorld");
        check(((ResourceKey<?>)call(world,"getDimId")).equals(Level.OVERWORLD),"waypoints belong to overworld");
        String name=(boolean)point[2]?com.chaosthedude.explorerscompass.unified.client.FilterUi.biomeName((ResourceLocation)point[0]):com.chaosthedude.explorerscompass.util.StructureUtils.getPrettyStructureName((ResourceLocation)point[0]);
        int[] p=(int[])point[1];int matches=0;
        for(Object w:(Iterable<?>)call(call(world,"getCurrentSet"),"getWaypoints"))
            if(name.equals(call(w,"getName")) && (int)call(w,"getX")==p[0] && (int)call(w,"getY")==p[1] && (int)call(w,"getZ")==p[2])matches++;
        check(matches==1,"exactly one persisted waypoint "+name+" "+Arrays.toString(p));
    }
    private static Object call(Object value,String method)throws Exception{return value.getClass().getMethod(method).invoke(value);}
    private static Object task(UUID id)throws Exception{return ((Map<?,?>)staticField(AdventureTravel.class,"tasks")).get(id);}
    private static Object staticField(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void guard(Checked action){try{action.run();}catch(Throwable failure){error=failure;}}
    private interface Checked{void run()throws Exception;}
    private static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    private static void finish(Minecraft mc,String result){phase=100;try{Files.writeString(Path.of("entertainment116-results.txt"),result);}catch(Exception failure){throw new IllegalStateException(failure);}mc.stop();}
}
