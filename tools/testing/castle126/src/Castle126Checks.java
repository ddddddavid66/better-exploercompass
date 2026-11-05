package com.chaosthedude.explorerscompass.unified.smoke;

import com.david.compass.runtime.game.mixin.ChunkMapAccessor;
import com.david.compass.runtime.game.search.TeleportService;
import com.david.compass.runtime.game.search.VerificationSlots;
import com.chaosthedude.explorerscompass.unified.search.*;
import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.network.CatalogService;
import com.chaosthedude.explorerscompass.util.CompassState;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
@Mod("castle126checks")
public final class Castle126Checks {
 private boolean opened,closed;private long screenAt;private static volatile String done;private static volatile UUID player;
 private static final net.minecraft.server.level.TicketType<UUID> VERIFY=net.minecraft.server.level.TicketType.create("crate_check",Comparator.<UUID>naturalOrder());
 private static final UUID verifyId=UUID.randomUUID();private static BlockPos verifying;private static long verificationBegin;
 private static int phase;private static long begin,lastLog;private static ItemStack stack;private static BlockPos first;
 public Castle126Checks(){MinecraftForge.EVENT_BUS.register(this);}
 @SubscribeEvent public void client(TickEvent.ClientTickEvent e){
  if(e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();
  if(mc.screen instanceof ConfirmScreen confirm && Boolean.getBoolean("castle126.resume")){
   var buttons=confirm.children().stream().filter(value->value instanceof net.minecraft.client.gui.components.Button).map(value->(net.minecraft.client.gui.components.Button)value).toList();
   if(!buttons.isEmpty()){log("CONFIRM "+confirm.getNarrationMessage().getString());buttons.get(0).onPress();return;}
  }
  if(done!=null){if(!closed){closed=true;log(done);mc.stop();}return;}
  if(!opened){
   if(mc.screen instanceof AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
   if(!(mc.screen instanceof TitleScreen))return;opened=true;mc.options.pauseOnLostFocus=false;
   mc.options.renderDistance().set(2);mc.options.simulationDistance().set(5);mc.options.framerateLimit().set(20);
   if(Boolean.getBoolean("castle126.resume")){mc.createWorldOpenFlows().loadLevel(new TitleScreen(),System.getProperty("castle126.world","castle126"));return;}
   mc.createWorldOpenFlows().createFreshLevel(System.getProperty("castle126.world","castle126"),
    new LevelSettings("Golden Crate natural generation",GameType.CREATIVE,false,Difficulty.PEACEFUL,false,new GameRules(),WorldDataConfiguration.DEFAULT),
    new net.minecraft.world.level.levelgen.WorldOptions(789123L,true,false),net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions);return;
  }
  if(mc.player!=null)player=mc.player.getUUID();
  else if(System.nanoTime()-screenAt>30_000_000_000L){screenAt=System.nanoTime();log("SCREEN "+(mc.screen==null?"null":mc.screen.getClass().getName()));}
 }
 @SubscribeEvent public void tick(TickEvent.ServerTickEvent e){
  if(e.phase!=TickEvent.Phase.END || player==null || done!=null)return;
  var server=ServerLifecycleHooks.getCurrentServer();var p=server.getPlayerList().getPlayer(player);if(p==null)return;
  try{
   if(phase==0){
    if(Boolean.getBoolean("castle126.resume")){
     stack=p.getMainHandItem();check(stack.getItem()==ExplorersCompass.explorersCompass,"saved compass exists");
     var previous=stack.getTag().getList("PrevPos",net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
     first=new BlockPos(previous.getInt("X"),previous.getInt("Y"),previous.getInt("Z"));
     phase=2;begin=System.nanoTime();log("RESUME only failed post-result block verification");return;
    }
    if(!Boolean.getBoolean("castle126.optimized"))officialRandomParity();
    var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,GoldenCrateAdapter.DIMENSION));check(level!=null,"layer 4 exists");
    p.teleportTo(level,8.5,64,8.5,Set.of(),0,0);p.setInvulnerable(true);
    stack=new ItemStack(ExplorersCompass.explorersCompass);p.setItemInHand(InteractionHand.MAIN_HAND,stack);
    var rules=GoldenCrateAdapter.snapshot(level);log("RULES "+rules);check(rules.predictive(),"prediction enabled: "+rules.reason());
    var catalog=CatalogService.snapshot(level,UUID.randomUUID());
    check(catalog.structures().containsKey(GoldenCrateAdapter.KEY),"visible in catalog");
    check(catalog.dimensionStructures().get(GoldenCrateAdapter.DIMENSION).contains(GoldenCrateAdapter.KEY),"correct dimension");
    check(!catalog.dimensionStructures().get(Level.OVERWORLD.location()).contains(GoldenCrateAdapter.KEY),"not overworld");
    check(SearchService.requestStructures(p,stack,GoldenCrateAdapter.KEY,false,false,new ResourceLocation("infinite_abyss:fourth_layer_cavern")),"normal filtered entry accepted");
    phase=1;begin=System.nanoTime();return;
   }
   if(System.nanoTime()-lastLog>5_000_000_000L){lastLog=System.nanoTime();log("PROGRESS "+stack.getTag()+" slots="+VerificationSlots.count());}
   check(System.nanoTime()-begin<300_000_000_000L,"functional deadline 300s");
   if(SearchService.owns(p,stack))return;
   check(ExplorersCompass.explorersCompass.getState(stack)==CompassState.FOUND,"search found real crate");
   var pos=new BlockPos(ExplorersCompass.explorersCompass.getFoundStructureX(stack),stack.getTag().getInt(SearchService.FOUND_Y),ExplorersCompass.explorersCompass.getFoundStructureZ(stack));
   // 使用搜索已经完成的地物阶段区块，不同步加载其他区域。
   // 找到与最近性证明结束之间，候选可能正常卸载；验收自身也应非阻塞重新准备。
   if(verifying==null){verifying=pos;verificationBegin=System.nanoTime();TeleportService.cancel(p.getUUID());p.serverLevel().getChunkSource().chunkMap.getDistanceManager().addTicket(VERIFY,new ChunkPos(pos),net.minecraft.server.level.ChunkLevel.byStatus(net.minecraft.world.level.chunk.ChunkStatus.FEATURES),verifyId);return;}
   var holder=((com.david.compass.runtime.game.mixin.ChunkMapAccessor)p.serverLevel().getChunkSource().chunkMap).unified$getUpdatingChunkIfPresent(new ChunkPos(pos).toLong());
   check(System.nanoTime()-verificationBegin<60_000_000_000L,"test result chunk reload deadline");
   if(holder==null || holder.getTicketLevel()>net.minecraft.server.level.ChunkLevel.byStatus(net.minecraft.world.level.chunk.ChunkStatus.FEATURES))return;
   var ready=holder.getOrScheduleFuture(net.minecraft.world.level.chunk.ChunkStatus.FEATURES,p.serverLevel().getChunkSource().chunkMap).getNow(null);
   if(ready==null || ready.left().isEmpty())return;
   check(ready.left().get().getBlockState(pos).is(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(GoldenCrateAdapter.BLOCK)),"real golden crate block exists; actual="+ready.left().get().getBlockState(pos));
   log("PASS verify_reloaded=true mode="+stack.getTag().getString("ECFiltersSearchMode")+" phase="+phase+" xyz="+pos+" elapsed_ms="+(System.nanoTime()-begin)/1e6);
   p.serverLevel().getChunkSource().chunkMap.getDistanceManager().removeTicket(VERIFY,new ChunkPos(verifying),net.minecraft.server.level.ChunkLevel.byStatus(net.minecraft.world.level.chunk.ChunkStatus.FEATURES),verifyId);
   verifying=null;TeleportService.cancel(p.getUUID());
   if(phase==1){first=pos;check(SearchService.requestStructures(p,stack,GoldenCrateAdapter.KEY,false,true,null),"find-next accepted");phase=2;begin=System.nanoTime();return;}
   check(!first.equals(pos),"find-next excludes previous anchor");SearchService.cancel(p,false);check(VerificationSlots.count()==0,"all verification slots released");done="PASS natural generation and find-next; no template placement by helper";
  }catch(Throwable error){error.printStackTrace();log("FAIL "+error);done="FAIL "+error;}
 }
 static void officialRandomParity(){
  int checked=0;
  for(long seed:new long[]{789123,42,-987654321})for(int x=-12;x<12;x++)for(int z=-12;z<12;z++)for(int chance:new int[]{1,2,7}){
   var slot=new GoldenCrateAdapter.Slot(Math.floorMod(x,11),Math.floorMod(z,301));
   var rules=new GoldenCrateAdapter.Snapshot(Set.of(),List.of(slot),chance,8,128,true,"");
   var random=new net.minecraft.world.level.levelgen.WorldgenRandom(new net.minecraft.world.level.levelgen.XoroshiroRandomSource(0));
   random.setFeatureSeed(random.setDecorationSeed(seed,x*16,z*16),slot.index(),slot.step());
   var origin=new BlockPos(x*16,0,z*16);
   var rarity=net.minecraft.world.level.levelgen.placement.RarityFilter.onAverageOnceEvery(chance).getPositions(null,random,origin).findFirst();
   var actual=GoldenCrateAdapter.predict(seed,x,z,slot,rules);
   if(rarity.isEmpty()){check(actual==null,"official rarity match");continue;}
   var square=net.minecraft.world.level.levelgen.placement.InSquarePlacement.spread().getPositions(null,random,origin).findFirst().orElseThrow();
   int y=net.minecraft.world.level.levelgen.heightproviders.UniformHeight.of(net.minecraft.world.level.levelgen.VerticalAnchor.absolute(8),net.minecraft.world.level.levelgen.VerticalAnchor.absolute(128)).sample(random,null);
   check(new BlockPos(square.getX()+6,y+1,square.getZ()+6).equals(actual),"official random candidate match");checked++;
  }
  log("PASS official placement parity checked="+checked);
 }
 static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
 static void log(String s){try{Files.writeString(Path.of("castle126-results.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}System.out.println("CASTLE126 "+s);}
}
