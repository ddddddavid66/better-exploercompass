package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.unified.creature.*;
import com.chaosthedude.explorerscompass.unified.creature.catalog.*;
import com.chaosthedude.explorerscompass.unified.creature.client.*;
import com.chaosthedude.explorerscompass.unified.creature.search.*;
import com.chaosthedude.explorerscompass.unified.search.*;
import com.chaosthedude.explorerscompass.unified.client.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 仅本次生物功能验收；不召唤生物，不伪造成功结果，原存档不参与运行。 */
@Mod.EventBusSubscriber(modid="filterschecks",value=Dist.CLIENT)
public final class Creature120Checks {
    private static int phase,ticks,wait;
    private static volatile Throwable error;
    private static volatile String chosen;
    private static volatile boolean prepared,verified,moved;
    private static long deadline=System.nanoTime()+1_200_000_000_000L;
    private static final List<String> notes=new ArrayList<>();
    private static ResourceLocation fallbackType,fallbackBiome;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(!Boolean.getBoolean("unified.creature120") || event.phase!=TickEvent.Phase.END || phase==100)return;
        ticks++;var mc=Minecraft.getInstance();
        try {
            if(error!=null)throw new AssertionError("server",error);check(System.nanoTime()<deadline,"test deadline phase="+phase);if(ticks<wait)return;
            if(phase==0){
                if(mc.screen instanceof net.minecraftforge.client.gui.LoadingErrorScreen screen){check(((List<?>)field(screen,"modLoadErrors")).isEmpty(),"Forge startup errors");mc.setScreen(new TitleScreen());}
                if(mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
                if(!(mc.screen instanceof TitleScreen))return;
                mc.options.guiScale().set(2);mc.options.renderDistance().set(3);mc.options.simulationDistance().set(5);mc.options.languageCode="zh_cn";mc.resizeDisplay();
                mc.createWorldOpenFlows().loadLevel(mc.screen,"creature120-save");phase=1;wait=ticks+100;return;
            }
            if(mc.player==null || mc.getSingleplayerServer()==null)return;
            var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
            if(phase==1){
                server.execute(()->guard(()->{
                    var p=server.getPlayerList().getPlayer(id);check(p.serverLevel().dimension().equals(Level.OVERWORLD),"original saved overworld");
                    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(CreatureItem.INSTANCE));
                    notes.add("Seed="+p.serverLevel().getSeed()+" origin="+p.blockPosition());notes.add("Catalog="+CreatureCatalog.all(server).size());
                    Files.writeString(Path.of("creature120-pending.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(CreatureCatalog.pending(server).entrySet().stream().collect(java.util.stream.Collectors.toMap(e->e.getKey().toString(),Map.Entry::getValue))));
                    if(Boolean.getBoolean("unified.creature120.neighborOnly")){prepared=true;return;}
                    var candidates=new ArrayList<Entity>();for(var e:p.serverLevel().getAllEntities()){
                        var entry=CreatureCatalog.get(server,net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()));
                        if(e instanceof LivingEntity && !(e instanceof net.minecraft.world.entity.player.Player) && entry!=null && entry.dimensions().contains(Level.OVERWORLD.location()) && CreatureIndex.point(p.serverLevel(),e)!=null)candidates.add(e);
                    }
                    candidates.sort(Comparator.comparingDouble(e->e.distanceToSqr(p)));check(!candidates.isEmpty(),"real loaded book creature exists");
                    var e=candidates.get(0);chosen=net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()).toString();notes.add("Initial real target="+chosen+" uuid="+e.getUUID()+" xyz="+e.position());prepared=true;
                }));phase=2;return;
            }
            if(phase==2){if(!prepared)return;CreatureClient.open();phase=3;wait=ticks+40;return;}
            if(phase==3){
                if(CreatureClient.entries.isEmpty() || !ClientSession.ready())return;
                if(Boolean.getBoolean("unified.creature120.neighborOnly")){moved=true;phase=9;return;}
                var screen=(CreatureScreen)mc.screen;var entry=CreatureClient.entries.stream().filter(e->e.entity().toString().equals(chosen)).findFirst().orElseThrow();
                ((EditBox)field(screen,"input")).setValue(entry.name());screen.tick();
                check(!((List<?>)field(screen,"matches")).isEmpty(),"Chinese card search");
                var selected=CreatureScreen.class.getDeclaredField("selected");selected.setAccessible(true);selected.set(screen,entry.entity());screen.tick();
                check(CreatureClient.query==null,"selecting card does not search");((Button)field(screen,"search")).onPress();
                phase=4;wait=ticks+5;return;
            }
            if(phase==4){
                if(!CreatureClient.finished)return;check(CreatureClient.state.equals("found"),"loaded search found: "+CreatureClient.state);check(!CreatureClient.points.isEmpty(),"real result points");
                Screenshot.grab(mc.gameDirectory,"creature120-ui.png",mc.getMainRenderTarget(),c->{});
                server.execute(()->guard(()->{var p=server.getPlayerList().getPlayer(id);for(var point:CreatureClient.points){var real=CreatureIndex.point(p.serverLevel(),p.serverLevel().getEntity(point.uuid()));check(real!=null && real.type().equals(point.type()) && real.dimension().equals(point.dimension()) && real.biome().equals(point.biome()),"UUID/type/dimension/biome verified");notes.add("Real result="+real);}verified=true;}));phase=5;return;
            }
            if(phase==5){if(!verified)return;mc.setScreen(null);phase=6;wait=ticks+50;return;}
            if(phase==6){
                long rendered=(long)staticField(Class.forName("com.chaosthedude.explorerscompass.unified.creature.map.MinimapAdapter"),"rendered");
                check(rendered>0,"actual minimap blue renderer invoked");notes.add("Minimap actual blue render calls="+rendered);
                Screenshot.grab(mc.gameDirectory,"creature120-minimap.png",mc.getMainRenderTarget(),c->{});
                Object session=Class.forName("xaero.map.WorldMapSession").getMethod("getCurrentSession").invoke(null);Object processor=session.getClass().getMethod("getMapProcessor").invoke(session);
                var gui=Class.forName("xaero.map.gui.GuiMap");mc.setScreen((net.minecraft.client.gui.screens.Screen)gui.getConstructors()[0].newInstance(null,null,processor,mc.player));phase=7;wait=ticks+40;return;
            }
            if(phase==7){
                long calls=(long)staticField(Class.forName("com.chaosthedude.explorerscompass.unified.creature.map.WorldMapAdapter"),"rendered");check(calls>0,"world map real blue renderer invoked");
                var toggle=mc.screen.children().stream().filter(w->w instanceof Button && ((Button)w).getMessage().getString().equals("生")).map(w->(Button)w).findFirst().orElseThrow();
                boolean before=CreatureClient.dots();toggle.onPress();check(CreatureClient.dots()!=before,"map toggle changes shared switch");toggle.onPress();check(CreatureClient.dots()==before,"toggle restored");
                notes.add("World map calls="+calls+" shared toggle PASS");Screenshot.grab(mc.gameDirectory,"creature120-map.png",mc.getMainRenderTarget(),c->{});
                mc.setScreen(null);CreatureClient.teleport();phase=8;wait=ticks+10;return;
            }
            if(phase==8){
                if(CreatureClient.confirmation){CreatureClient.teleport();wait=ticks+20;return;}
                if(!CreatureClient.state.equals("teleport_moved"))return;
                var target=CreatureClient.selected();server.execute(()->guard(()->{var p=server.getPlayerList().getPlayer(id);var e=p.serverLevel().getEntity(target.uuid());check(e!=null && e.distanceToSqr(p)<225,"player moved beside real entity");notes.add("Teleport beside UUID="+e.getUUID()+" distance="+e.distanceTo(p));moved=true;}));phase=9;wait=ticks+50;return;
            }
            if(phase==9){if(!moved)return;
                // 选择有明确群系、当前已加载区域没有实例的自然生物，真实执行邻区兜底。
                server.execute(()->guard(()->{
                    var p=server.getPlayerList().getPlayer(id);var choices=CreatureCatalog.all(server).stream().filter(e->e.dimensions().contains(Level.OVERWORLD.location()) && !e.biomes().isEmpty() && e.entity().getNamespace().equals("alexsmobs"))
                        .filter(e->List.of("grizzly_bear","raccoon","bison","roadrunner","gazelle","capuchin_monkey").contains(e.entity().getPath()))
                        .filter(e->CreatureIndex.entities(p.serverLevel(),e.entity()).isEmpty()).sorted(Comparator.comparingInt(e->e.entity().getPath().equals("grizzly_bear")?0:1)).toList();
                    check(!choices.isEmpty(),"an absent loaded target for neighbor path");var e=choices.get(0);fallbackType=e.entity();fallbackBiome=e.biomes().get(0);prepared=false;
                    if(e.biomes().contains(new ResourceLocation("minecraft:forest")))fallbackBiome=new ResourceLocation("minecraft:forest");
                    notes.add("Neighbor real search="+fallbackType+" biome="+fallbackBiome);
                }));phase=10;wait=ticks+10;return;
            }
            if(phase==10){if(fallbackType==null)return;CreatureClient.search(fallbackType,Level.OVERWORLD.location(),fallbackBiome);phase=11;return;}
            if(phase==11){
                if(!CreatureClient.finished)return;check(CreatureClient.round<=4 && CreatureClient.chunks<=256,"default fallback bound");notes.add("Neighbor terminal="+CreatureClient.state+" rounds="+CreatureClient.round+" chunks="+CreatureClient.chunks+" count="+CreatureClient.points.size());
                if(CreatureClient.state.equals("found")){verified=false;server.execute(()->guard(()->{var p=server.getPlayerList().getPlayer(id);for(var point:CreatureClient.points){var e=p.serverLevel().getEntity(point.uuid());check(e!=null,"neighbor actual UUID exists");var actual=CreatureIndex.point(p.serverLevel(),e);check(actual!=null && actual.biome().equals(fallbackBiome),"neighbor actual selected biome");}verified=true;}));}
                else {check(CreatureClient.state.equals("not_found"),"neighbor path completed without technical failure");verified=true;notes.add("No natural individual found; no positive-neighbor claim.");}
                phase=12;wait=ticks+10;return;
            }
            if(phase==12){if(!verified)return;CreatureClient.stop();server.execute(()->guard(()->{var p=server.getPlayerList().getPlayer(id);CreatureSearch.cancel(id);TeleportService.cancel(id);check(CreatureSearch.tickets()==0,"creature tickets released");prepared=true;}));phase=13;wait=ticks+10;return;}
            if(phase==13){if(!prepared)return;finish(mc,(notes.stream().anyMatch(s->s.startsWith("No natural"))?"PARTIAL":"PASS")+"\n"+String.join("\n",notes));}
        }catch(Throwable failure){failure.printStackTrace();finish(mc,"FAIL phase="+phase+": "+failure+"\n"+String.join("\n",notes));}
    }
    private static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static Object staticField(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static void guard(Checked c){try{c.run();}catch(Throwable t){error=t;t.printStackTrace();}}
    private interface Checked{void run()throws Exception;}
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private static void finish(Minecraft mc,String text){phase=100;try{Files.writeString(Path.of("creature120-results.txt"),text);}catch(Exception e){throw new IllegalStateException(e);}mc.stop();}
}
