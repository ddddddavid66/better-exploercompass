package com.david.compass.validation;
import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.network.*;
import com.chaosthedude.explorerscompass.unified.search.SearchService;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.david.compass.runtime.game.search.*;
import com.david.compass.runtime.game.data.LandingMode;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 外置验收工具；所有定位与传送通过正式网络包，不写入生产结果。 */
@Mod.EventBusSubscriber(modid=SplitProbe.ID,value=net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class Release133Probe {
 static final List<String> targets=new ArrayList<>(List.of("cataclysm:sunken_city","cataclysm:cursed_pyramid","dungeons_plus:snowy_temple","minecraft:buried_treasure"));
 static int index,round,phase,stable,lastTick;static long deadline,started,found,move;static UUID old;static BlockPos anchor,origin;static volatile String verification;static boolean done;
 static void log(String line)throws Exception{Files.writeString(Path.of("release133-results.txt"),line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
 static void next(int value){phase=value;deadline=System.nanoTime()+240_000_000_000L;stable=0;}
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
  if(e.phase!=TickEvent.Phase.END || !Boolean.getBoolean("compass.release133.probe") || done)return;
  var mc=Minecraft.getInstance();
  try{
   if(deadline==0)next(0);
   if(System.nanoTime()>deadline){log("FAIL timeout target="+index+" round="+round+" phase="+phase);advance(mc);return;}
   if(mc.screen instanceof AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
   if(mc.screen!=null && mc.screen.getClass().getName().equals("net.minecraftforge.client.gui.LoadingErrorScreen")){
    for(var child:mc.screen.children())if(child instanceof Button b && b.active && (b.getMessage().getString().contains("Proceed") || b.getMessage().getString().contains("返回主菜单") || b.getMessage().getString().equals("Continue"))){b.onPress();break;}return;
   }
   if(phase==0){if(mc.screen instanceof TitleScreen){mc.options.pauseOnLostFocus=false;mc.createWorldOpenFlows().loadLevel(mc.screen,"case-"+index);next(1);}return;}
   if(mc.player==null && mc.screen instanceof ConfirmScreen){for(var child:mc.screen.children())if(child instanceof Button b && b.active){b.onPress();break;}return;}
   var server=mc.getSingleplayerServer();var p=mc.player;if(server==null || p==null)return;
   switch(phase){
    case 1 -> {
     if(index==0 && targets.size()==4){
      var registry=server.registryAccess().registryOrThrow(Registries.STRUCTURE);
      var state=server.overworld().getChunkSource().getGeneratorState();
      var pool=new ArrayList<>(List.of("minecraft:desert_pyramid","minecraft:shipwreck","minecraft:ruined_portal","minecraft:igloo","minecraft:swamp_hut"));
      pool.removeIf(id->registry.getHolder(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE,new ResourceLocation(id))).map(h->state.getPlacementsForStructure(h).isEmpty()).orElse(true));
      Collections.shuffle(pool,new Random(133));targets.addAll(pool.subList(0,Math.min(2,pool.size())));log("targets="+targets);
     }
     UUID id=p.getUUID();server.execute(()->{var sp=server.getPlayerList().getPlayer(id);sp.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ExplorersCompass.explorersCompass));sp.connection.teleport(8,100,8,0,0);SearchService.clearCache(id);});next(2);
    }
    case 2 -> {
     if(p.getMainHandItem().getItem()!=ExplorersCompass.explorersCompass || p.blockPosition().distSqr(new BlockPos(8,100,8))>16)return;
     int now=server.getTickCount();if(now==lastTick)return;lastTick=now;
     stable=server.getAverageTickTime()<40?stable+1:0;if(stable<100)return;
     old=SearchService.token(p.getMainHandItem());origin=p.blockPosition();started=System.nanoTime();
     UnifiedNetwork.CHANNEL.sendToServer(new StructureSearchPacket(new ResourceLocation(targets.get(index)),false,false,null,p.level().dimension().location()));next(3);
    }
    case 3 -> {
     var stack=p.getMainHandItem();var token=SearchService.token(stack);if(token==null || token.equals(old))return;
     var item=ExplorersCompass.explorersCompass;
     if(item.getState(stack)==CompassState.NOT_FOUND){log("FAIL not_found "+targets.get(index));advance(mc);return;}
     if(item.getState(stack)!=CompassState.FOUND)return;
     found=System.nanoTime();anchor=new BlockPos(item.getFoundStructureX(stack),stack.getTag().getInt(SearchService.FOUND_Y),item.getFoundStructureZ(stack));
     UnifiedNetwork.CHANNEL.sendToServer(new TeleportRequestPacket(token,UUID.randomUUID(),true,LandingMode.RELAXED_ONLY));next(4);
    }
    case 4 -> {
     if(p.blockPosition().distSqr(origin)<16)return;
     move=System.nanoTime();UUID id=p.getUUID();BlockPos expected=anchor;String target=targets.get(index);
     server.execute(()->{var sp=server.getPlayerList().getPlayer(id);var at=sp.blockPosition();boolean ok=true;
      if(target.equals("minecraft:buried_treasure")){ok=at.getX()==expected.getX() && at.getZ()==expected.getZ();boolean chest=false;var c=sp.serverLevel().getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);if(c!=null)for(int y=at.getY()-1;y>=sp.serverLevel().getMinBuildHeight();y--)if(c.getBlockState(new BlockPos(at.getX(),y,at.getZ())).is(Blocks.CHEST)){chest=true;break;}ok&=chest;}
      verification=(ok?"PASS":"FAIL")+" landing="+at;
     });next(5);
    }
    case 5 -> {if(verification==null)return;log(targets.get(index)+" round="+round+" anchor="+anchor+" search_ms="+(found-started)/1e6+" click_ms="+(move-found)/1e6+" total_ms="+(move-started)/1e6+" within_1s="+(move-started<=1_000_000_000L)+" "+verification);verification=null;
     if(round==0){round=1;next(1);}else advance(mc);
    }
   }
  }catch(Throwable error){try{log("ERROR "+error);}catch(Exception ignored){}error.printStackTrace();done=true;mc.stop();}
 }
 static void advance(Minecraft mc){index++;round=0;verification=null;if(index>=6){done=true;mc.stop();return;}if(mc.level!=null)mc.level.disconnect();mc.clearLevel(new TitleScreen());next(0);}
}
