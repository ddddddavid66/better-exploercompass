package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.unified.client.ClientSession;
import com.chaosthedude.explorerscompass.unified.creature.CreatureItem;
import com.chaosthedude.explorerscompass.unified.creature.client.*;
import com.chaosthedude.explorerscompass.unified.creature.search.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 仅五种真实生物验收。没有预载坐标、召唤或修改刷怪条件。 */
@Mod.EventBusSubscriber(modid="filterschecks",value=Dist.CLIENT)
public final class Creature122Checks {
    private static final String[] TARGETS={"alexsmobs:flutter","alexsmobs:sunbird","minecraft:turtle","minecraft:squid","alexsmobs:gelada_monkey"};
    private static final List<String> notes=new CopyOnWriteArrayList<>();
    private static volatile Throwable failure;
    private static volatile boolean prepared,verified,released;
    private static int phase,ticks,waitUntil,index,savedHits;private static long started;
    private static long deadline=System.nanoTime()+1_500_000_000_000L;
    private static boolean overlaysDone;
    private Creature122Checks(){}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(!Boolean.getBoolean("unified.creature122") || event.phase!=TickEvent.Phase.END || phase==100)return;
        var mc=Minecraft.getInstance();ticks++;
        try{
            if(failure!=null)throw new AssertionError("server check",failure);
            check(System.nanoTime()<deadline,"overall test deadline");if(ticks<waitUntil)return;
            if(phase==0){start(mc);return;}
            if(mc.player==null || mc.getSingleplayerServer()==null)return;
            var server=mc.getSingleplayerServer();UUID player=mc.player.getUUID();
            switch(phase){
                case 1->{server.execute(()->guard(()->{
                    var p=server.getPlayerList().getPlayer(player);check(p!=null && p.serverLevel().dimension()==Level.OVERWORLD,"saved overworld player");
                    p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CreatureItem.INSTANCE));
                    notes.add("Seed="+p.serverLevel().getSeed()+" origin="+p.position()+" mods="+net.minecraftforge.fml.ModList.get().size());prepared=true;
                }));next(2,5);}
                case 2->{if(!prepared)return;com.chaosthedude.explorerscompass.unified.creature.CreatureConfig.CLIENT.creatureDimension.set("minecraft:overworld");com.chaosthedude.explorerscompass.unified.creature.CreatureConfig.CLIENT.creatureBiome.set("");com.chaosthedude.explorerscompass.unified.creature.CreatureConfig.CLIENT.creatureRounds.set(4);CreatureClient.open();next(3,10);}
                case 3->{
                    if(CreatureClient.entries.isEmpty() || !ClientSession.ready())return;
                    if(!overlaysDone){var screen=(CreatureScreen)mc.screen;((Button)field(screen,"dimensionButton")).onPress();((EditBox)field(screen,"popupInput")).setValue("主世界");next(20,5);return;}
                    selectAndSearch(mc);next(4,1);
                }
                case 4->{
                    check(System.nanoTime()-started<240_000_000_000L,"search timeout "+TARGETS[index]+" state="+CreatureClient.state+" saved="+CreatureClient.storedScanned+"/"+CreatureClient.storedTotal);
                    if(!CreatureClient.finished)return;
                    check(CreatureClient.state.equals("found") && !CreatureClient.points.isEmpty(),"found "+TARGETS[index]+" state="+CreatureClient.state);
                    var snapshot=List.copyOf(CreatureClient.points);var target=new ResourceLocation(TARGETS[index]);String source=CreatureClient.source;
                    long elapsed=System.nanoTime()-started;int verifiedCount=CreatureClient.storedVerified;long scanned=CreatureClient.storedScanned,total=CreatureClient.storedTotal;
                    server.execute(()->guard(()->{
                        var p=server.getPlayerList().getPlayer(player);var point=snapshot.get(0);var actual=CreatureIndex.point(p.serverLevel(),p.serverLevel().getEntity(point.uuid()));
                        check(actual!=null && actual.type().equals(target) && actual.uuid().equals(point.uuid()) && actual.dimension().equals(Level.OVERWORLD.location()),"real loaded UUID/type/dimension");
                        check(Math.abs(actual.x()-point.x())<32 && Math.abs(actual.z()-point.z())<32,"reported real position");
                        if(target.toString().equals("alexsmobs:flutter"))check(actual.biome().toString().equals("minecraft:lush_caves"),"actual selected lush caves biome");
                        notes.add("PASS target="+target+" uuid="+actual.uuid()+" xyz="+actual.x()+","+actual.y()+","+actual.z()+" biome="+actual.biome()+" source="+source+" elapsed_ms="+elapsed/1e6+" stored="+scanned+"/"+total+" verified="+verifiedCount);
                        if(source.equals("saved") && verifiedCount>0)savedHits++;verified=true;
                    }));next(5,5);
                }
                case 5->{
                    if(!verified)return;verified=false;write("RUNNING\n"+String.join("\n",notes));index++;
                    if(index<TARGETS.length){com.chaosthedude.explorerscompass.unified.creature.CreatureConfig.CLIENT.creatureBiome.set("");CreatureClient.open();next(3,10);}
                    else{CreatureClient.stop();next(6,20);}
                }
                case 6->{
                    server.execute(()->guard(()->{check(CreatureSearch.tickets()==0,"cancel/replacement releases creature tickets");check(savedHits>0,"at least one unloaded saved entity verified");check(SavedCreatureIndex.peakReads()<=8,"saved reads stay within eight in-flight slots");released=true;}));next(7,5);
                }
                case 7->{if(!released || SavedCreatureIndex.inFlight()!=0)return;notes.add("All five real targets passed; saved pipeline hits="+savedHits+"; tickets=0; inFlight=0; peakReads="+SavedCreatureIndex.peakReads()+"; no summons/no preloaded target positions.");finish(mc,"PASS\n"+String.join("\n",notes));}
                case 20->{
                    var screen=(CreatureScreen)mc.screen;shot(mc,"dimension.png");check(!((List<?>)field(screen,"popupMatches")).isEmpty(),"dimension search");
                    int x=(int)method(screen,"sidebar")+18;screen.mouseClicked(x+24,126,0);check(new ResourceLocation("minecraft:overworld").equals(field(screen,"dimension")),"dimension picker selection");
                    ((Button)field(screen,"biomeButton")).onPress();((EditBox)field(screen,"popupInput")).setValue("fanmaodongxue");next(21,5);
                }
                case 21->{
                    var screen=(CreatureScreen)mc.screen;shot(mc,"biome.png");check(((List<?>)field(screen,"popupMatches")).contains(new ResourceLocation("minecraft:lush_caves")),"pinyin biome picker");
                    int x=(int)method(screen,"sidebar")+18;screen.mouseClicked(x+20,117,0);check(field(screen,"popup").equals("biome"),"blank popup row does not select or click through");
                    screen.mouseScrolled(x+20,140,-1);screen.mouseScrolled(x+20,140,1);screen.keyPressed(256,0,0);check(field(screen,"popup").equals(""),"Esc closes only picker");
                    mc.options.guiScale().set(3);mc.resizeDisplay();((Button)field(mc.screen,"biomeButton")).onPress();next(22,5);
                }
                case 22->{
                    shot(mc,"small-popup.png");var screen=(CreatureScreen)mc.screen;screen.keyPressed(256,0,0);mc.options.guiScale().set(2);mc.resizeDisplay();
                    ((Button)field(mc.screen,"biomeButton")).onPress();((EditBox)field(mc.screen,"popupInput")).setValue("fanmaodongxue");next(23,5);
                }
                case 23->{var screen=(CreatureScreen)mc.screen;int x=(int)method(screen,"sidebar")+18;screen.mouseClicked(x+24,126,0);check(new ResourceLocation("minecraft:lush_caves").equals(field(screen,"biome")),"specific biome selected");overlaysDone=true;notes.add("UI dimension/biome popups, pinyin, input isolation, scroll, Esc, small window exercised.");selectAndSearch(mc);next(4,1);}
                default->throw new AssertionError("phase="+phase);
            }
        }catch(Throwable e){e.printStackTrace();finish(mc,"FAIL phase="+phase+" target="+(index<TARGETS.length?TARGETS[index]:"end")+" "+e+"\n"+String.join("\n",notes));}
    }
    private static void start(Minecraft mc)throws Exception{
        if(mc.screen instanceof net.minecraftforge.client.gui.LoadingErrorScreen screen){check(((List<?>)field(screen,"modLoadErrors")).isEmpty(),"Forge load errors");mc.setScreen(new TitleScreen());}
        if(mc.screen instanceof AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());if(!(mc.screen instanceof TitleScreen))return;
        mc.options.pauseOnLostFocus=false;mc.options.framerateLimit().set(60);mc.options.guiScale().set(2);mc.options.renderDistance().set(3);mc.options.simulationDistance().set(5);mc.options.languageCode="zh_cn";mc.resizeDisplay();
        index=Integer.getInteger("unified.creature122.resume",0);mc.createWorldOpenFlows().loadLevel(mc.screen,"creature122-save");next(1,100);
    }
    private static void selectAndSearch(Minecraft mc)throws Exception{
        var target=new ResourceLocation(TARGETS[index]);var entry=CreatureClient.entries.stream().filter(e->e.entity().equals(target)).findFirst().orElseThrow();
        var screen=(CreatureScreen)mc.screen;((EditBox)field(screen,"input")).setValue(entry.name());screen.tick();
        var matches=(List<?>)field(screen,"matches");int at=matches.indexOf(entry);check(at>=0,"target listed "+target);
        int left=(int)method(screen,"sidebar")+16,cols=Math.max(1,(screen.width-left-20)/78);screen.mouseClicked(left+at%cols*78+36,57+at/cols*78+36,0);
        check(target.equals(field(screen,"selected")),"target card selected");started=System.nanoTime();((Button)field(screen,"search")).onPress();
        notes.add("Started normal UI request="+CreatureClient.query+" target="+target);
    }
    private static void shot(Minecraft mc,String name)throws Exception{Files.createDirectories(mc.gameDirectory.toPath().resolve("screenshots/screenshots122"));Screenshot.grab(mc.gameDirectory,"screenshots122/"+name,mc.getMainRenderTarget(),c->{});}
    private static Object field(Object obj,String name)throws Exception{var f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(obj);}
    private static Object method(Object obj,String name)throws Exception{var m=obj.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(obj);}
    private static void next(int p,int delay){phase=p;waitUntil=ticks+delay;}
    private interface Checked{void run()throws Exception;}
    private static void guard(Checked task){try{task.run();}catch(Throwable e){failure=e;e.printStackTrace();}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void write(String result){try{Files.writeString(Path.of("creature122-results.txt"),result);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void finish(Minecraft mc,String result){phase=100;write(result);mc.stop();}
}
