package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.search.*;
import com.chaosthedude.explorerscompass.unified.data.LandingMode;
import com.chaosthedude.explorerscompass.unified.client.LandingClientState;
import com.chaosthedude.explorerscompass.unified.network.*;
import com.chaosthedude.explorerscompass.util.CompassState;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 搜索→冷区块准备→真实网络传送→客户端区块/光照检查；白区块专项。 */
@Mod.EventBusSubscriber(modid="filterschecks",value=Dist.CLIENT)
public final class Client111Checks {
    private static final boolean ALEX=Boolean.getBoolean("unified.alexclient111");
    private static final List<String> TARGETS=ALEX?List.of("alexscaves:magnetic_caves","alexscaves:primordial_caves","alexscaves:toxic_caves","alexscaves:abyssal_chasm","alexscaves:forlorn_hollows","alexscaves:candy_cavity"):List.of("betterjungletemples:jungle_temple","betteroceanmonuments:ocean_monument","dungeons_plus:warped_garden");
    private static final List<String> notes=new ArrayList<>();
    private static int stage,index,ticks,afterMove,retries;
    private static long deadline=System.nanoTime()+600_000_000_000L,clicked;
    private static BlockPos target;
    private static UUID old;
    private static volatile Throwable error;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!Boolean.getBoolean("unified.client111") || event.phase!=TickEvent.Phase.END || stage==100)return;
        ticks++;var mc=Minecraft.getInstance();
        try {
            if(error!=null)throw new AssertionError("server callback",error);
            if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage+" target="+index);
            if(stage==0) {
                // 整合包可能先显示 Forge 警告页；只允许跳过无加载错误的提示。
                if(mc.screen instanceof net.minecraftforge.client.gui.LoadingErrorScreen loading) {
                    var field=loading.getClass().getDeclaredField("modLoadErrors");field.setAccessible(true);
                    var errors=(List<?>)field.get(loading);
                    if(!errors.isEmpty())throw new AssertionError("Forge loading errors: "+errors);
                    System.out.println("CLIENT111: continuing past Forge warnings");mc.setScreen(new TitleScreen());
                }
                if(ticks%100==0)System.out.println("CLIENT111: waiting for title, screen="+(mc.screen==null?"null":mc.screen.getClass().getName()));
                if(mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
                if(!(mc.screen instanceof TitleScreen))return;
                mc.createWorldOpenFlows().createFreshLevel("unified-111-client-"+System.currentTimeMillis(),
                        new LevelSettings("Unified 111 isolated",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                        new WorldOptions(5988016379855497799L,true,false),WorldPresets::createNormalWorldDimensions);stage=1;return;
            }
            if(mc.player==null || mc.getSingleplayerServer()==null)return;
            var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
            if(stage==1) {
                if(index>=TARGETS.size()){finish(mc,(ALEX?"PASS: six Alex cave client searches and survival hands\n":"PASS: real client search/teleport/chunk handoff\n")+String.join("\n",notes));return;}
                int targetIndex=index;
                old=SearchService.token(mc.player.getItemInHand(hand()));
                server.execute(()-> {
                    try {
                        var player=server.getPlayerList().getPlayer(id);
                        var world=server.overworld();
                        player.teleportTo(world,-590,87,-47,0,0);
                        var stack=new ItemStack(ExplorersCompass.explorersCompass);
                        if(ALEX){player.setGameMode(GameType.SURVIVAL);player.getAbilities().invulnerable=true;player.setNoGravity(true);}
                        player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);player.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
                        player.setItemInHand(hand(),stack);
                        TeleportService.setMode(id,LandingMode.RELAXED_ONLY);
                        SearchService.clearCache(id);
                        boolean accepted=ALEX?SearchService.startBiome(player,stack,new ResourceLocation(TARGETS.get(targetIndex))):SearchService.requestStructures(player,stack,new ResourceLocation(TARGETS.get(targetIndex)),false,false,null);
                        if(!accepted)throw new AssertionError("search rejected");
                    }catch(Throwable failure){error=failure;}
                });stage=2;return;
            }
            var stack=mc.player.getItemInHand(hand());
            if(stage==2) {
                if(stack.getItem()!=ExplorersCompass.explorersCompass || Objects.equals(old,SearchService.token(stack)) || ExplorersCompass.explorersCompass.getState(stack)!=CompassState.FOUND)return;
                if(ALEX) {
                    if(!TARGETS.get(index).equals(stack.getTag().getString(SearchService.BIOME_KEY)))return;
                }else if(!new ResourceLocation(TARGETS.get(index)).equals(ExplorersCompass.explorersCompass.getStructureKey(stack)))return;
                if(!stack.getTag().getString("ECFiltersSearchMode").equals("async"))throw new AssertionError("unexpected sync path");
                target=new BlockPos(stack.getTag().getInt("FoundX"),stack.getTag().getInt(SearchService.FOUND_Y),stack.getTag().getInt("FoundZ"));
                if(ALEX) {
                    notes.add(TARGETS.get(index)+": survival "+hand()+", at="+target);
                    int foundIndex=index;BlockPos found=target;
                    server.execute(()-> {
                        try {
                            var biome=server.overworld().getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(found.getX()>>2,found.getY()>>2,found.getZ()>>2,server.overworld().getChunkSource().randomState().sampler());
                            if(!biome.unwrapKey().orElseThrow().location().toString().equals(TARGETS.get(foundIndex)))throw new AssertionError("Alex actual source mismatch");
                        }catch(Throwable failure){error=failure;}
                    });afterMove=ticks;stage=6;return;
                }
                retries=0;stage=3;
            }
            if(stage==6) {
                if(ticks-afterMove<40)return;
                if(stack.isEmpty() || stack.getItem()!=ExplorersCompass.explorersCompass)throw new AssertionError("survival compass destroyed");
                Screenshot.grab(mc.gameDirectory,"unified111-alex-"+index+".png",mc.getMainRenderTarget(),ignored->{});
                index++;stage=1;return;
            }
            if(stage==3) {
                clicked=System.nanoTime();retries++;
                UnifiedNetwork.CHANNEL.sendToServer(new TeleportRequestPacket(SearchService.token(stack),UUID.randomUUID(),true,LandingMode.RELAXED_ONLY));stage=4;return;
            }
            if(stage==4) {
                var state=LandingClientState.state(stack);
                if(state==TeleportStatusPacket.State.SUCCEEDED) {
                    notes.add(TARGETS.get(index)+": move_ms="+((System.nanoTime()-clicked)/1e6)+", attempts="+retries+", at="+mc.player.position());
                    afterMove=ticks;stage=5;return;
                }
                if(state==TeleportStatusPacket.State.FAILED_RETRYABLE) {
                    if(retries>=3)throw new AssertionError("landing failed after three attempts "+TARGETS.get(index));stage=3;
                }
                return;
            }
            if(stage==5) {
                if(ticks-afterMove<80)return;
                var pos=mc.player.blockPosition();
                if(Math.abs(pos.getX()-target.getX())>33 || Math.abs(pos.getZ()-target.getZ())>33)throw new AssertionError("wrong teleport X/Z");
                for(int x=(pos.getX()>>4)-1;x<=(pos.getX()>>4)+1;x++)for(int z=(pos.getZ()>>4)-1;z<=(pos.getZ()>>4)+1;z++)
                    if(!mc.level.hasChunk(x,z))throw new AssertionError("client missing tracked chunk "+x+","+z);
                // 客户端通过光照包维护独立 LightEngine，不设置服务端的 isLightCorrect 标记。
                var light=mc.level.getLightEngine().getLayerListener(LightLayer.BLOCK)
                        .getDataLayerData(net.minecraft.core.SectionPos.of(pos));
                if(light==null)throw new AssertionError("client block-light packet not applied");
                if(mc.level.dimensionType().hasSkyLight() && mc.level.getBrightness(LightLayer.SKY,pos.above(3))==0)
                    throw new AssertionError("client skylight packet not applied");
                Screenshot.grab(mc.gameDirectory,"unified111-"+index+".png",mc.getMainRenderTarget(),ignored->{});
                server.execute(()-> {
                    try {if(TeleportService.ticketCount()!=0)throw new AssertionError("handoff tickets not released");}
                    catch(Throwable failure){error=failure;}
                });index++;stage=1;
            }
        }catch(Throwable failure){failure.printStackTrace();finish(mc,"FAIL: "+failure+"\n"+String.join("\n",notes));}
    }
    private static InteractionHand hand(){return ALEX && index%2==1?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;}
    private static void finish(Minecraft mc,String result) {
        stage=100;try{Files.writeString(Path.of("client111-results.txt"),result);}catch(Exception failure){throw new IllegalStateException(failure);}mc.stop();
    }
}
