package com.chaosthedude.explorerscompass.unified.smoke;
import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.search.*;
import com.chaosthedude.explorerscompass.unified.mixin.ChunkMapAccessor;
import com.google.gson.JsonObject;
import java.util.*;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/** 独立验收工具，不放置结构，不修改生产算法。 */
@Mod("unified128checks")
public final class Perf128Checks {
 private boolean opened;private volatile boolean finished;private int phase,ticks,run;
 private long since,searchEnd,verifySince;private SearchJob job;private boolean replaced,verifyTicket;
 private final UUID verifyOwner=UUID.randomUUID();
 private static final TicketType<UUID> VERIFY=TicketType.create("unified128_fixture_verify",Comparator.<UUID>naturalOrder());
 private void releaseVerify(ServerPlayer p){if(verifyTicket){p.serverLevel().getChunkSource().chunkMap.getDistanceManager().removeTicket(VERIFY,job.result().chunk(),ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS),verifyOwner);verifyTicket=false;}VerificationSlots.release(verifyOwner);}
 private final String key=System.getProperty("perf128.target","aether:bronze_dungeon");
 public Perf128Checks(){MinecraftForge.EVENT_BUS.register(this);}
 @SubscribeEvent public void client(TickEvent.ClientTickEvent e){
  if(e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();
  try{
   if(finished){mc.stop();return;}
   if(!opened){if(mc.screen instanceof AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
    if(mc.screen instanceof TitleScreen){opened=true;mc.options.pauseOnLostFocus=false;mc.createWorldOpenFlows().loadLevel(new TitleScreen(),"world128");}}
   // 只通过提供明确继续按钮的Forge警告页，加载错误页没有此按钮，不会被跳过。
   if(mc.screen!=null && mc.screen.getClass().getName().equals("net.minecraftforge.client.gui.LoadingErrorScreen")) {
    for(var widget:mc.screen.children())if(widget instanceof Button b && b.active) {
     var content=b.getMessage().getContents();String key=content instanceof net.minecraft.network.chat.contents.TranslatableContents t?t.getKey():"";
     if(key.equals("fml.button.continue") || b.getMessage().getString().equals("继续返回主菜单") || b.getMessage().getString().equals("Continue")){b.onPress();break;}
    }
   }
   if(mc.screen instanceof ConfirmScreen){for(var widget:mc.screen.children())if(widget instanceof Button b){b.onPress();break;}}
  }catch(Throwable error){fail(error);}
 }
 @SubscribeEvent public void server(TickEvent.ServerTickEvent e){
  if(e.phase!=TickEvent.Phase.END || finished)return;var server=ServerLifecycleHooks.getCurrentServer();
  if(server==null || server.getPlayerList().getPlayers().isEmpty())return;var p=server.getPlayerList().getPlayers().get(0);
  try{
   if(phase==0){
    if(server.overworld().getSeed()!=789123)throw new AssertionError("wrong seed");
    var dimension=key.startsWith("dungeons_plus:")?net.minecraft.world.level.Level.OVERWORLD:ResourceKey.create(Registries.DIMENSION,new ResourceLocation("aether:the_aether"));
    var level=server.getLevel(dimension);if(level==null)throw new AssertionError("dimension absent");
    p.setNoGravity(true);p.getAbilities().invulnerable=true;p.teleportTo(level,8,100,8,0,0);
    SearchConfig.RADIUS.set(80000);SearchConfig.STRUCTURE_SAMPLES.set(100000000L);SearchConfig.BIOME_SAMPLES.set(32000000L);
    SearchConfig.ASYNC_BIOMES.set(true);SearchConfig.ASYNC_STRUCTURES.set(true);phase=1;ticks=0;
   }else if(phase==1){if(++ticks<60)return;start(p);}
   else if(phase==2){
    if(SearchService.activeCount()!=0){
     SearchJob current=current(p);if(current!=job){replaced=true;job=current;}
     if(System.nanoTime()-since<240_000_000_000L)return;
     record(p,"timeout",false);SearchService.cancel(p,true);TeleportService.cancel(p.getUUID());phase=3;ticks=0;return;
    }
    searchEnd=System.nanoTime();TeleportService.cancel(p.getUUID());
    if(job.result()!=null && !job.biome()) {
     if(!VerificationSlots.acquire(verifyOwner))throw new AssertionError("fixture verification slot unavailable");
     p.serverLevel().getChunkSource().chunkMap.getDistanceManager().addTicket(VERIFY,job.result().chunk(),ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS),verifyOwner);
     verifyTicket=true;verifySince=System.nanoTime();phase=4;
    }else{record(p,job.stage(),verify(p));phase=3;ticks=0;}
   }else if(phase==4){
    var holder=((ChunkMapAccessor)p.serverLevel().getChunkSource().chunkMap).unified$getUpdatingChunkIfPresent(job.result().chunk().toLong());
    var ready=holder==null || holder.getTicketLevel()>ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS)?null:holder.getOrScheduleFuture(ChunkStatus.STRUCTURE_STARTS,p.serverLevel().getChunkSource().chunkMap).getNow(null);
    if(ready==null && System.nanoTime()-verifySince<60_000_000_000L)return;
    record(p,job.stage(),ready!=null && ready.left().isPresent() && verify(p));releaseVerify(p);phase=3;ticks=0;
   }else if(phase==3){
    if(++ticks<20)return;
    if(Boolean.getBoolean("perf128.probe") && run==0 && job.result()!=null && !job.biome())probe(p);
    JsonObject x=new JsonObject();x.addProperty("event","release");x.addProperty("target",key);x.addProperty("run",run);
    x.addProperty("slots",VerificationSlots.count());x.addProperty("queued",VerificationSlots.queued());x.addProperty("structure_tickets",StructureVerification.ticketCount());x.addProperty("teleport_tickets",TeleportService.ticketCount());write(x);
    if(VerificationSlots.count()!=0 || VerificationSlots.queued()!=0 || StructureVerification.ticketCount()!=0 || TeleportService.ticketCount()!=0)throw new AssertionError("ticket leak");
    if(++run==Integer.getInteger("perf128.runs",2)){finished=true;return;}phase=1;ticks=0;
   }
  }catch(Throwable error){releaseVerify(p);SearchService.cancel(p,true);TeleportService.cancel(p.getUUID());fail(error);}
 }
 private void start(ServerPlayer p)throws Exception{
  var dimension=key.startsWith("dungeons_plus:")?net.minecraft.world.level.Level.OVERWORLD:ResourceKey.create(Registries.DIMENSION,new ResourceLocation("aether:the_aether"));
  var targetLevel=p.getServer().getLevel(dimension);
  p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);p.setNoGravity(true);
  p.teleportTo(targetLevel,8,100,8,0,0);
  if(!p.serverLevel().dimension().equals(dimension))throw new AssertionError("fixture dimension mismatch");
  SearchService.clearCache(p.getUUID());p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ExplorersCompass.explorersCompass));
  p.teleportTo(p.serverLevel(),8,100,8,0,0);since=System.nanoTime();searchEnd=0;replaced=false;
  boolean ok=key.startsWith("ancient_aether:")?SearchService.startBiome(p,p.getMainHandItem(),new ResourceLocation(key)):
   SearchService.requestStructures(p,p.getMainHandItem(),new ResourceLocation(key),false,false,null);
  if(!ok)throw new AssertionError("request rejected "+key);job=current(p);phase=2;
 }
 private SearchJob current(ServerPlayer p)throws Exception{
  var field=SearchService.class.getDeclaredField("sessions");field.setAccessible(true);Object session=((Map<?,?>)field.get(null)).get(p.getUUID());
  var j=session.getClass().getDeclaredField("job");j.setAccessible(true);return (SearchJob)j.get(session);
 }
 private boolean verify(ServerPlayer p){
  var hit=job.result();if(hit==null)return false;var level=p.serverLevel();
  if(job.biome()){var pos=hit.position();return hit.key().equals(level.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(pos.getX()>>2,pos.getY()>>2,pos.getZ()>>2,level.getChunkSource().randomState().sampler()).unwrapKey().orElseThrow().location());}
  var holder=((ChunkMapAccessor)level.getChunkSource().chunkMap).unified$getUpdatingChunkIfPresent(hit.chunk().toLong());
  if(holder==null)return false;var ready=holder.getFutureIfPresentUnchecked(ChunkStatus.STRUCTURE_STARTS).getNow(null);
  if(ready==null || ready.left().isEmpty())return false;var chunk=ready.left().get();
  var actual=level.structureManager().getStartForStructure(SectionPos.bottomOf(chunk),hit.structure(),chunk);
  return actual!=null && actual.isValid() && !actual.getPieces().isEmpty();
 }
 private void probe(ServerPlayer p)throws Exception {
  var original=job.result();var chunk=new net.minecraft.world.level.ChunkPos(original.chunk().x+10000,original.chunk().z+10000);
  var candidate=new SearchCandidate(new net.minecraft.core.BlockPos(chunk.getMinBlockX(),100,chunk.getMinBlockZ()),original.key(),original.structure(),original.placement(),chunk,0);
  var first=new StructureVerification(p.serverLevel(),p.blockPosition());var second=new StructureVerification(p.serverLevel(),p.blockPosition());
  try {
   if(first.verify(candidate,false)!=ScanCoordinator.Confirmation.WAIT || second.verify(candidate,true)!=ScanCoordinator.Confirmation.WAIT)throw new AssertionError("probe requires unloaded source");
   var field=StructureVerification.class.getDeclaredField("flight");field.setAccessible(true);
   if(field.get(first)!=field.get(second) || StructureVerification.ticketCount()!=1 || VerificationSlots.count()!=1)throw new AssertionError("shared lease not reused");
   first.cancel();if(StructureVerification.ticketCount()!=1 || VerificationSlots.count()!=1)throw new AssertionError("released before last reference");
   second.cancel();if(StructureVerification.ticketCount()!=0 || VerificationSlots.count()!=0)throw new AssertionError("last reference leaked");
   JsonObject x=new JsonObject();x.addProperty("event","shared_lease_probe");x.addProperty("passed",true);x.addProperty("same_flight",true);write(x);
  }finally{first.cancel();second.cancel();}
 }
 private void record(ServerPlayer p,String state,boolean verified){
  JsonObject x=new JsonObject();x.addProperty("event","result");x.addProperty("build",System.getProperty("perf128.build"));
  x.addProperty("dimension",p.serverLevel().dimension().location().toString());x.addProperty("origin", "8,100,8");x.addProperty("target",key);x.addProperty("scenario",run==0?"cold":"hot");x.addProperty("state",state);x.addProperty("verified",verified);
  x.addProperty("elapsed_ms",((searchEnd==0?System.nanoTime():searchEnd)-since)/1e6);x.addProperty("main_ms",job.mainNanos/1e6);x.addProperty("worker_ms",job.workerNanos()/1e6);
  x.addProperty("prepare_ms",job.preparationNanos()/1e6);x.addProperty("confirm_ms",job.confirmationNanos()/1e6);x.addProperty("wait_ms",job.waitingNanos()/1e6);
  x.addProperty("samples",job.samples());x.addProperty("candidates",job.candidates());x.addProperty("checks",job.confirmations);x.addProperty("chunks",job.chunkLoads);
  x.addProperty("mode",job.mode());x.addProperty("replaced_job",replaced);x.addProperty("diagnostics",job.diagnostics());
  if(job.result()!=null){var hit=job.result();x.addProperty("result_id",hit.key().toString());x.addProperty("x",hit.position().getX());x.addProperty("y",hit.position().getY());x.addProperty("z",hit.position().getZ());}write(x);
 }
 private void fail(Throwable error){error.printStackTrace();JsonObject x=new JsonObject();x.addProperty("event","failure");x.addProperty("target",key);x.addProperty("phase",phase);x.addProperty("reason",error.toString());write(x);finished=true;}
 private static synchronized void write(JsonObject x){System.out.println("PERF128 "+x);try{Files.writeString(Path.of("comparison128.jsonl"),x+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
}
