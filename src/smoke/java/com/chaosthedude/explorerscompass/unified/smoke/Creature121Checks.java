package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.unified.client.ClientSession;
import com.chaosthedude.explorerscompass.unified.creature.CreatureItem;
import com.chaosthedude.explorerscompass.unified.creature.catalog.CreatureCatalog;
import com.chaosthedude.explorerscompass.unified.creature.catalog.CreatureEntry;
import com.chaosthedude.explorerscompass.unified.creature.client.CreatureClient;
import com.chaosthedude.explorerscompass.unified.creature.client.CreatureScreen;
import com.chaosthedude.explorerscompass.unified.creature.search.CreatureIndex;
import com.chaosthedude.explorerscompass.unified.creature.search.CreatureSearch;
import com.chaosthedude.explorerscompass.unified.creature.search.MobPoint;
import com.chaosthedude.explorerscompass.unified.search.TeleportService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

/** 仅验收本轮真实生物 UI、地图与传送回执；不召唤实体、不扫描邻区、不伪造搜索结果。 */
@Mod.EventBusSubscriber(modid="filterschecks",value=Dist.CLIENT)
public final class Creature121Checks {
    private static final List<String> notes=new CopyOnWriteArrayList<>();
    private static volatile Throwable failure;
    private static volatile ResourceLocation chosen;
    private static volatile boolean prepared,verified,moved,released;
    private static int phase,ticks,waitUntil,oldScale,windowWidth,windowHeight;
    private static long deadline=System.nanoTime()+1_200_000_000_000L;
    private static long stageStarted=System.nanoTime();
    private static MobPoint centered,teleportTarget;
    private static Screen teleportScreen;
    private static boolean fallback;

    private Creature121Checks() {}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(!Boolean.getBoolean("unified.creature121") || event.phase!=TickEvent.Phase.END || phase==100)return;
        var mc=Minecraft.getInstance();ticks++;
        try {
            if(failure!=null)throw new AssertionError("server verification",failure);
            check(System.nanoTime()<deadline,"test deadline phase="+phase);
            if(ticks<waitUntil)return;
            if(phase==0){start(mc);return;}
            if(mc.player==null || mc.getSingleplayerServer()==null)return;
            check(phase<2 || System.nanoTime()-stageStarted<90_000_000_000L,"phase has no successful completion within 90 seconds: "+phase+" state="+CreatureClient.state);
            var server=mc.getSingleplayerServer();UUID id=mc.player.getUUID();
            switch(phase){
                case 1->{
                    server.execute(()->guard(()->{
                        var p=server.getPlayerList().getPlayer(id);check(p.serverLevel().dimension().equals(Level.OVERWORLD),"saved overworld start");
                        p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CreatureItem.INSTANCE));
                        List<Entity> candidates=new ArrayList<>();
                        for(var e:p.serverLevel().getAllEntities()){
                            var type=ForgeRegistries.ENTITY_TYPES.getKey(e.getType());var entry=type==null?null:CreatureCatalog.get(server,type);
                            if(e instanceof LivingEntity && !(e instanceof Player) && entry!=null && entry.matches(Level.OVERWORLD.location(),null)
                                    && CreatureIndex.point(p.serverLevel(),e)!=null)candidates.add(e);
                        }
                        candidates.sort(Comparator.<Entity>comparingInt(e->e.getType()==ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("crittersandcompanions:ferret"))?0:1)
                            .thenComparingDouble(e->e.distanceToSqr(p)));
                        check(!candidates.isEmpty(),"a natural loaded catalog creature exists");var e=candidates.get(0);chosen=ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
                        notes.add("Seed="+p.serverLevel().getSeed()+" origin="+p.blockPosition()+" natural target="+chosen+" uuid="+e.getUUID()+" xyz="+e.position());prepared=true;
                    }));next(2,2);
                }
                case 2->{if(!prepared)return;CreatureClient.open();next(3,15);}
                case 3->{
                    if(CreatureClient.entries.isEmpty() || !ClientSession.ready())return;
                    var screen=(CreatureScreen)mc.screen;var entry=entry();((EditBox)field(screen,"input")).setValue(entry.name());screen.tick();
                    check(!((List<?>)field(screen,"matches")).isEmpty(),"Chinese creature card filter");clickCard(screen,entry);check(CreatureClient.query==null,"selection alone does not search");
                    ((Button)field(screen,"search")).onPress();notes.add("Normal CreatureClient search request="+CreatureClient.query);next(4,1);
                }
                case 4->{
                    if(!CreatureClient.finished)return;
                    check(CreatureClient.state.equals("found") && !CreatureClient.points.isEmpty(),"normal loaded search found: "+CreatureClient.state);
                    check(CreatureClient.chunks==0 && CreatureClient.round==0,"loaded query performs no neighbor scan");
                    check(CreatureClient.captureTotal>0 && CreatureClient.captured==CreatureClient.captureTotal,"server capture progress fields received");
                    var screen=(CreatureScreen)mc.screen;check((int)method(screen,"cardsBottom")<screen.height-87,"cards do not cover fixed progress panel");
                    notes.add("Server progress captured="+CreatureClient.captured+"/"+CreatureClient.captureTotal+" chunks="+CreatureClient.chunks+"/"+CreatureClient.chunksTotal+" anchor="+CreatureClient.anchorSamples+"/"+CreatureClient.anchorLimit);
                    server.execute(()->guard(()->{var p=server.getPlayerList().getPlayer(id);for(var point:CreatureClient.points){
                        var actual=CreatureIndex.point(p.serverLevel(),p.serverLevel().getEntity(point.uuid()));
                        check(actual!=null && actual.type().equals(point.type()) && actual.dimension().equals(point.dimension()) && actual.biome().equals(point.biome()),"real UUID/type/dimension/biome");
                        notes.add("Real loaded result="+actual);
                    }verified=true;}));hoverCard(mc,screen);next(5,10);
                }
                case 5->{
                    if(!verified)return;var screen=(CreatureScreen)mc.screen;hoverCard(mc,screen);
                    check(field(screen,"hoveredEntry")!=null && !((List<?>)field(screen,"tooltipLines")).isEmpty(),"actual hover produces introduction after render");
                    notes.add("Tooltip paired="+entry().paired()+" appearances="+entry().appearances().size()+" lines="+((List<?>)field(screen,"tooltipLines")).size());shot(mc,"hover-progress.png");
                    windowWidth=mc.getWindow().getScreenWidth();windowHeight=mc.getWindow().getScreenHeight();oldScale=mc.options.guiScale().get();
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),854,480);mc.options.guiScale().set(3);mc.resizeDisplay();GLFW.glfwSetCursorPos(mc.getWindow().getWindow(),0,0);next(6,20);
                }
                case 6->{
                    var screen=(CreatureScreen)mc.screen;check((int)method(screen,"cardsBottom")<screen.height-87,"small UI preserves progress separation");
                    notes.add("Small UI="+screen.width+"x"+screen.height+" cardsBottom="+method(screen,"cardsBottom")+" progressTop="+(screen.height-87));shot(mc,"small-ui.png");
                    GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),windowWidth,windowHeight);mc.options.guiScale().set(oldScale);mc.resizeDisplay();mc.setScreen(null);next(7,30);
                }
                case 7->{
                    if(!CreatureClient.dots())CreatureClient.toggleDots();
                    Object session=Class.forName("xaero.map.WorldMapSession").getMethod("getCurrentSession").invoke(null);check(session!=null,"WorldMap session");
                    Object processor=session.getClass().getMethod("getMapProcessor").invoke(session);var gui=Class.forName("xaero.map.gui.GuiMap");
                    mc.setScreen((Screen)gui.getConstructors()[0].newInstance(null,null,processor,mc.player));next(8,30);
                }
                case 8->{
                    long calls=(long)staticField(Class.forName("com.chaosthedude.explorerscompass.unified.creature.map.WorldMapAdapter"),"rendered");check(calls>0,"real world map renderer calls");
                    int[] halo=(int[])staticField(Class.forName("com.chaosthedude.explorerscompass.unified.creature.map.CreatureMaps"),"HALO_WIDTHS");check(halo.length==25 && halo[12]==12,"world-map halo radius data 12 GUI pixels");
                    Button toggle=mapButton(mc.screen);check(toggle.getMessage().getString().equals("隐藏生物定位蓝点"),"full hide action text");toggle.onPress();
                    check(!CreatureClient.dots() && toggle.getMessage().getString().equals("显示生物定位蓝点"),"full show text after disabling");
                    centered=nearest(mc);toggle.onPress();check(CreatureClient.dots() && toggle.getMessage().getString().equals("隐藏生物定位蓝点"),"shared toggle restored");
                    notes.add("World map renderer calls="+calls+" halo=12px current query="+CreatureClient.query+" center UUID="+centered.uuid()+" targetXZ="+centered.x()+","+centered.z());next(9,50);
                }
                case 9->{
                    double x=(double)field(mc.screen,"cameraX"),z=(double)field(mc.screen,"cameraZ");
                    check(Math.abs(x-Math.floor(centered.x()))<3 && Math.abs(z-Math.floor(centered.z()))<3,"native camera centered on current nearest result: "+x+","+z);
                    notes.add("Native cameraXZ="+x+","+z+" cave layer preserved");shot(mc,"world-map.png");
                    CreatureClient.open();next(10,10);
                }
                case 10->{
                    teleportScreen=mc.screen;teleportTarget=CreatureClient.selected();check(teleportTarget!=null,"real teleport target remains selected");
                    var button=(Button)field(teleportScreen,"teleport");check(button.active,"normal creature teleport enabled");button.onPress();
                    check(CreatureClient.teleportPending() && mc.screen==teleportScreen,"click waits for matching server acknowledgement before close");next(11,3);
                }
                case 11->{
                    if(CreatureClient.teleportPending())return;check(mc.screen!=teleportScreen,"accepted teleport automatically closed its CreatureScreen");
                    check(!(mc.screen instanceof CreatureScreen),"no replacement creature screen opened");notes.add("Accepted teleport closed UI; server state="+CreatureClient.state);next(12,3);
                }
                case 12->{
                    if(CreatureClient.confirmation && !fallback){fallback=true;CreatureClient.open();next(13,10);return;}
                    if(!CreatureClient.state.equals("teleport_moved"))return;
                    server.execute(()->guard(()->{var p=server.getPlayerList().getPlayer(id);var e=p.serverLevel().getEntity(teleportTarget.uuid());
                        check(e!=null && e.isAlive() && e.distanceToSqr(p)<225,"player is beside the same real UUID");notes.add("Teleport UUID="+e.getUUID()+" actual distance="+e.distanceTo(p));moved=true;
                    }));next(14,5);
                }
                case 13->{teleportScreen=mc.screen;((Button)field(mc.screen,"teleport")).onPress();next(11,3);}
                case 14->{if(!moved)return;CreatureClient.stop();next(15,15);}
                case 15->{server.execute(()->guard(()->{check(CreatureSearch.tickets()==0 && TeleportService.ticketCount()==0,"normal cancellation released search and landing tickets");released=true;}));next(16,5);}
                case 16->{
                    if(!released)return;for(String image:List.of("hover-progress.png","small-ui.png","world-map.png"))check(Files.isRegularFile(mc.gameDirectory.toPath().resolve("screenshots/screenshots121/"+image)),"native screenshot saved: "+image);
                    notes.add("Creature/teleport tickets=0; no neighbor scan, no summoned entities, no advancement grant.");finish(mc,"PASS\n"+String.join("\n",notes));
                }
                default->throw new AssertionError("unknown phase "+phase);
            }
        }catch(Throwable error){error.printStackTrace();finish(mc,"FAIL phase="+phase+": "+error+"\n"+String.join("\n",notes));}
    }

    private static void start(Minecraft mc)throws Exception{
        if(mc.screen instanceof net.minecraftforge.client.gui.LoadingErrorScreen screen){check(((List<?>)field(screen,"modLoadErrors")).isEmpty(),"Forge startup errors");notes.add("Forge warnings only; continuing");mc.setScreen(new TitleScreen());}
        if(mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
        if(!(mc.screen instanceof TitleScreen))return;
        mc.options.guiScale().set(2);mc.options.renderDistance().set(3);mc.options.simulationDistance().set(5);mc.options.languageCode="zh_cn";mc.resizeDisplay();
        mc.createWorldOpenFlows().loadLevel(mc.screen,"creature121-save");next(1,100);
    }
    private static CreatureEntry entry(){return CreatureClient.entries.stream().filter(e->e.entity().equals(chosen)).findFirst().orElseThrow();}
    private static void clickCard(CreatureScreen screen,CreatureEntry entry)throws Exception{
        var matches=(List<?>)field(screen,"matches");int index=matches.indexOf(entry);check(index>=0,"exact target card listed");int left=(int)method(screen,"sidebar")+16,cols=Math.max(1,(screen.width-left-20)/78);
        check(screen.mouseClicked(left+index%cols*78+36,57+index/cols*78+36,0),"card click accepted");check(entry.entity().equals(field(screen,"selected")),"card selected correct entity type");
    }
    private static void hoverCard(Minecraft mc,CreatureScreen screen)throws Exception{
        int x=(int)method(screen,"sidebar")+16+36,y=57+36;
        GLFW.glfwSetCursorPos(mc.getWindow().getWindow(),x*(double)mc.getWindow().getScreenWidth()/screen.width,y*(double)mc.getWindow().getScreenHeight()/screen.height);
    }
    private static Button mapButton(Screen screen){return screen.children().stream().filter(w->w instanceof Button b && b.getMessage().getString().contains("生物定位蓝点")).map(w->(Button)w).findFirst().orElseThrow();}
    private static MobPoint nearest(Minecraft mc){return CreatureClient.points.stream().filter(MobPoint::live).min(Comparator.<MobPoint>comparingDouble(p->p.distance(mc.player.getX(),mc.player.getZ())).thenComparing(p->p.uuid().toString())).orElseThrow();}
    private static void shot(Minecraft mc,String filename)throws Exception{Files.createDirectories(mc.gameDirectory.toPath().resolve("screenshots/screenshots121"));Screenshot.grab(mc.gameDirectory,"screenshots121/"+filename,mc.getMainRenderTarget(),c->{});notes.add("Screenshot=screenshots/screenshots121/"+filename);}
    private static Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static Object staticField(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static Object method(Object object,String name)throws Exception{var m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(object);}
    private static void next(int value,int delay){phase=value;waitUntil=ticks+delay;stageStarted=System.nanoTime();}
    private interface Checked{void run()throws Exception;}
    private static void guard(Checked task){try{task.run();}catch(Throwable error){failure=error;error.printStackTrace();}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void finish(Minecraft mc,String text){phase=100;try{Files.writeString(Path.of("creature121-results.txt"),text,StandardCharsets.UTF_8);}catch(Exception error){throw new IllegalStateException(error);}mc.stop();}
}
