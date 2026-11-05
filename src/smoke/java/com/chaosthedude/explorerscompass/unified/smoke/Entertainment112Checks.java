package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.gui.ExplorersCompassScreen;
import com.chaosthedude.explorerscompass.unified.entertainment.*;
import com.david.compass.runtime.game.search.TeleportService;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 只验收一次娱乐功能；真实 UI 请求、自然进度、最终返回，不授予任何条件。 */
@Mod.EventBusSubscriber(modid="filterschecks",value=Dist.CLIENT)
public final class Entertainment112Checks {
    private static int stage,ticks,until,lastCount=-1;
    private static long deadline=System.nanoTime()+1_800_000_000_000L;
    private static volatile Throwable error;
    private static volatile boolean checked;
    private static volatile Vec3 origin;
    private static String lastPhase="";
    private static EntertainmentUi popup;
    private static final List<String> notes=new ArrayList<>();
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!Boolean.getBoolean("unified.entertainment112") || event.phase!=TickEvent.Phase.END || stage==100)return;
        var mc=Minecraft.getInstance();ticks++;
        try {
            if(error!=null)throw new AssertionError("server validation",error);
            if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage);
            if(ticks<until)return;
            if(stage==0) {
                if(mc.screen instanceof net.minecraftforge.client.gui.LoadingErrorScreen loading) {
                    var errors=(List<?>)field(loading,"modLoadErrors");
                    check(errors.isEmpty(),"Forge loading errors: "+errors);mc.setScreen(new TitleScreen());
                }
                if(mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
                if(!(mc.screen instanceof TitleScreen))return;
                mc.options.guiScale().set(2);mc.options.renderDistance().set(3);mc.options.simulationDistance().set(5);mc.resizeDisplay();
                mc.createWorldOpenFlows().createFreshLevel("entertainment-112-"+System.currentTimeMillis(),
                    new LevelSettings("Entertainment 112 isolated",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                    new WorldOptions(789123L,true,false),WorldPresets::createNormalWorldDimensions);
                stage=1;until=ticks+40;return;
            }
            if(mc.player==null || mc.getSingleplayerServer()==null)return;
            var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
            if(stage==1) {
                if(!mc.player.onGround())return;
                server.execute(()->{try {
                    var player=server.getPlayerList().getPlayer(id);origin=player.position();
                    var adv=AdventureRequirements.advancement(player);
                    check(!player.getAdvancements().getOrStartProgress(adv).isDone(),"advancement initially unfinished");
                    check(AdventureRequirements.read(adv).size()==53,"runtime vanilla requirements=53");
                    player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ExplorersCompass.explorersCompass));
                }catch(Throwable failure){error=failure;}});
                stage=2;until=ticks+20;return;
            }
            if(stage==2) {
                if(mc.player.getMainHandItem().getItem()!=ExplorersCompass.explorersCompass)return;
                mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);stage=3;until=ticks+10;return;
            }
            if(stage==3) {
                if(!(mc.screen instanceof ExplorersCompassScreen screen))return;
                popup=(EntertainmentUi)field(screen,"entertainment");
                screen.mouseScrolled(30,100,-30);
                var button=popup.button();check(button.visible,"entertainment button visible after sidebar scroll");
                screen.mouseClicked(button.getX()+5,button.getY()+5,0);check(popup.isOpen(),"open entertainment popup");
                var confirm=(Button)field(popup,"confirm");check(!confirm.active,"unselected confirm disabled");
                screen.mouseClicked(confirm.getX()+5,confirm.getY()+5,0);
                check(!EntertainmentClient.active(),"unselected click does not start");
                var search=(EditBox)field(popup,"search");search.setValue("tansuodeshiguang");
                check((boolean)field(popup,"matches"),"full pinyin search");search.setValue("tsdsg");
                check((boolean)field(popup,"matches"),"initial pinyin search");search.setValue("探索");
                check((boolean)field(popup,"matches"),"Chinese search");
                int x=(int)field(popup,"left"),y=(int)field(popup,"top");
                screen.mouseClicked(x+12,y+40,0);check(confirm.active,"selection enables confirm");
                stage=4;until=ticks+5;return;
            }
            if(stage==4) {
                check(!EntertainmentClient.active(),"selection alone does not start");
                Screenshot.grab(mc.gameDirectory,"entertainment112-ui.png",mc.getMainRenderTarget(),ignored->{});
                var confirm=(Button)field(popup,"confirm");
                mc.screen.mouseClicked(confirm.getX()+4,confirm.getY()+4,0);stage=5;until=ticks+5;return;
            }
            var state=EntertainmentClient.state();
            if(stage==5) {
                if(state==null || !state.active())return;
                notes.add("PASS UI: sidebar, selection/confirmation separation, Chinese/full/initial pinyin");
                mc.setScreen(null);stage=6;
            }
            if(stage==6) {
                if(state==null)return;
                if(lastCount!=state.completed() || !lastPhase.equals(state.phase())) {
                    lastCount=state.completed();lastPhase=state.phase();
                    String line="visited="+state.completed()+"/"+state.total()+" phase="+state.phase()+" biome="+state.biome()+" detail="+state.detail();
                    System.out.println("ENTERTAINMENT112: "+line);
                    Files.writeString(Path.of("entertainment112-progress.txt"),line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                }
                if(state.active())return;
                check(state.phase().equals("completed"),"journey ended early: "+state);
                server.execute(()->{try {
                    var player=server.getPlayerList().getPlayer(id);var adv=AdventureRequirements.advancement(player);
                    check(player.getAdvancements().getOrStartProgress(adv).isDone(),"vanilla advancement really completed");
                    for(var visit:AdventureRequirements.read(adv))check(AdventureRequirements.visited(player,adv,visit),"unvisited "+visit.biome());
                    check(player.position().distanceTo(origin)<.1,"returned to exact departure point: "+player.position()+" vs "+origin);
                    checked=true;
                }catch(Throwable failure){error=failure;}});
                stage=7;until=ticks+60;return;
            }
            if(stage==7 && checked) {
                server.execute(()->{try{check(TeleportService.ticketCount()==0,"landing tickets released");}
                    catch(Throwable failure){error=failure;}});
                notes.add("PASS: 53 runtime biome criteria naturally completed; exact departure point restored");
                stage=8;until=ticks+10;return;
            }
            if(stage==8)finish(mc,"PASS: one entertainment end-to-end acceptance\n"+String.join("\n",notes));
        }catch(Throwable failure){failure.printStackTrace();finish(mc,"FAIL: "+failure+"\n"+String.join("\n",notes));}
    }
    private static Object field(Object object,String name)throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void finish(Minecraft mc,String message) {
        stage=100;try{Files.writeString(Path.of("entertainment112-results.txt"),message);}catch(Exception failure){throw new IllegalStateException(failure);}mc.stop();
    }
}
