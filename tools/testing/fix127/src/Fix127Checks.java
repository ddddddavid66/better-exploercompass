package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.search.*;
import com.chaosthedude.explorerscompass.unified.client.*;
import com.chaosthedude.explorerscompass.unified.data.LandingMode;
import com.chaosthedude.explorerscompass.util.CompassState;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/** 只在愚者独立副本运行：不放置地牢、不授予成就、不打入发布jar。 */
@Mod("unified127checks")
public final class Fix127Checks {
    private static int phase,index,scan;private static long since=System.nanoTime(),overall=since;
    private static boolean opened;private static volatile String finished;private static volatile boolean inspectReports,uiDone;
    private static ServerLevel aether;private static SearchJob job;private static BlockPos bronze,floor;
    private static ResourceLocation biome;private static String mode;private static TeleportService.TravelResult moved;
    private static final TicketType<UUID> SETUP=TicketType.create("unified127_fixture",Comparator.<UUID>naturalOrder());
    private static final UUID fixture=UUID.randomUUID();private static boolean ticket;private static ChunkPos fixtureCenter=new ChunkPos(0,0);
    public Fix127Checks(){MinecraftForge.EVENT_BUS.register(this);}
    @SubscribeEvent public void client(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();
        try{
            if(finished!=null){log(finished);finished=null;mc.stop();return;}
            if(!opened){
                if(mc.screen instanceof AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
                if(!(mc.screen instanceof TitleScreen))return;opened=true;mc.options.pauseOnLostFocus=false;
                mc.createWorldOpenFlows().loadLevel(new TitleScreen(),"world127");return;
            }
            if(mc.screen instanceof ConfirmScreen){for(var w:mc.screen.children())if(w instanceof Button b){b.onPress();break;}}
            if(inspectReports && !uiDone){
                var files=Files.list(FailureReports.directory()).filter(p->p.toString().endsWith(".txt")).toList();if(files.size()<2)return;
                UUID id=UUID.fromString(files.get(0).getFileName().toString().substring(24,60));
                FailureReports.open(id);check(mc.screen instanceof FailureReportScreen,"report link opens screen");
                check(!mc.screen.isPauseScreen(),"report screen nonpausing");
                check(mc.screen.children().stream().anyMatch(w->w instanceof Button b && b.getMessage().getString().contains("文件夹")),"folder button");
                mc.screen.mouseScrolled(100,100,-20);mc.screen.onClose();
                var listField=Arrays.stream(mc.gui.getChat().getClass().getDeclaredFields()).filter(f->List.class.isAssignableFrom(f.getType())).toList();
                int links=0;
                for(var f:listField){f.setAccessible(true);for(Object item:(List<?>)f.get(mc.gui.getChat()))if(item instanceof net.minecraft.client.GuiMessage m)
                    if(m.content().getString().equals("点击查看错误报告")){var style=m.content().getStyle();check(style.isUnderlined() && style.getClickEvent()!=null && style.getColor().getValue()==0xff5555,"red underline click");links++;}}
                check(links==2,"two clickable report lines");
                log("PASS report_files=2 chat_links=2 screen_scroll=true folder_button=true history_log_disabled=true");uiDone=true;
            }
        }catch(Throwable error){fail(error);}
    }
    @SubscribeEvent public void server(TickEvent.ServerTickEvent event){
        if(event.phase!=TickEvent.Phase.END || finished!=null)return;var server=ServerLifecycleHooks.getCurrentServer();
        if(server==null || server.getPlayerList().getPlayers().isEmpty())return;var p=server.getPlayerList().getPlayers().get(0);
        try{
            check(System.nanoTime()-overall<1_200_000_000_000L,"overall deadline");
            switch(phase){
                case 0->{
                    p.setNoGravity(true);p.getAbilities().invulnerable=true;
                    aether=server.getLevel(ResourceKey.create(Registries.DIMENSION,new ResourceLocation("aether:the_aether")));check(aether!=null,"actual Aether");
                    p.teleportTo(aether,8.5,100,8.5,0,0);fresh(p);
                    if(Boolean.getBoolean("fix127.resume")){SearchConfig.RADIUS.set(80000);index=0;startStructure(p,true);next(3);return;}
                    SearchConfig.RADIUS.set(0);
                    check(SearchService.startStructures(p,p.getMainHandItem(),new ResourceLocation("aether:bronze_dungeon"),List.of(new ResourceLocation("aether:bronze_dungeon"))),"accepted controlled structure failure");next(1);
                }
                case 1->{if(SearchService.activeCount()!=0)return;check(SearchService.startBiome(p,p.getMainHandItem(),aether.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().iterator().next().unwrapKey().orElseThrow().location(),0),"accepted controlled biome failure");next(2);}
                case 2->{if(SearchService.activeCount()!=0)return;SearchConfig.RADIUS.set(80000);inspectReports=true;if(!uiDone){check(elapsed()<30,"report UI deadline");return;}index=0;startStructure(p,true);next(3);}
                case 3->{
                    if(SearchService.activeCount()!=0){check(elapsed()<240,"dungeon deadline "+job.stage());return;}
                    check(ExplorersCompass.explorersCompass.getState(p.getMainHandItem())==CompassState.FOUND,"dungeon found "+index+" stage="+job.stage());
                    check(job.mode().equals("async"),"actually asynchronous "+job.mode());
                    SearchCandidate hit=job.result();check(hit!=null,"verified job result");
                    log("PASS dungeon="+hit.key()+" mode="+job.mode()+" source="+hit.chunk()+" anchor="+hit.position()+" samples="+job.samples()+" checks="+job.confirmations+" elapsed_ms="+elapsed()*1000);
                    if(index==0)bronze=hit.position();SearchService.cancel(p,true);
                    if(++index<3 && !Boolean.getBoolean("fix127.bronzeOnly")){startStructure(p,true);next(3);}else{index=0;startStructure(p,false);next(4);}
                }
                case 4->{
                    if(SearchService.activeCount()!=0){check(elapsed()<240,"bronze synchronous deadline");return;}
                    check(job.result()!=null && bronze.equals(job.result().position()),"bronze sync/async same actual anchor");
                    log("PASS bronze_sync_same="+bronze+" mode="+job.mode());SearchService.cancel(p,true);SearchConfig.ASYNC_STRUCTURES.set(true);
                    fixtureCenter=new ChunkPos(bronze);for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)aether.getChunkSource().addRegionTicket(SETUP,new ChunkPos(fixtureCenter.x+x,fixtureCenter.z+z),2,fixture);ticket=true;next(5);
                }
                case 5->{
                    if(!com.chaosthedude.explorerscompass.unified.creature.search.CreaturePreExplore.ready(aether,fixtureCenter)){check(elapsed()<65,"floor chunks");return;}
                    long until=System.nanoTime()+1_000_000;
                    while(scan<24*24*aether.getHeight() && System.nanoTime()<until){int at=scan++,cell=at/aether.getHeight(),x=fixtureCenter.getMinBlockX()-4+cell%24,z=fixtureCenter.getMinBlockZ()-4+cell/24,y=aether.getMaxBuildHeight()-1-at%aether.getHeight();
                        var point=new BlockPos(x,y,z);if(TeleportService.relaxed(p,aether,ItemStack.EMPTY,point)!=null){floor=point;break;}}
                    if(floor==null){check(scan<24*24*aether.getHeight(),"natural Aether protected floor");return;}
                    biome=aether.getBiome(floor).unwrapKey().orElseThrow().location();p.teleportTo(aether,floor.getX()+.5,floor.getY()+8,floor.getZ()+.5,0,0);fresh(p);
                    check(SearchService.startBiome(p,p.getMainHandItem(),biome),"manual biome search");next(6);
                }
                case 6->{
                    if(SearchService.activeCount()!=0){check(elapsed()<90,"manual biome locate");return;}check(ExplorersCompass.explorersCompass.getState(p.getMainHandItem())==CompassState.FOUND,"manual biome found");
                    TeleportService.setMode(p.getUUID(),LandingMode.RELAXED_ONLY);check(TeleportService.start(p,p.getMainHandItem(),SearchService.token(p.getMainHandItem())),"manual teleport accepted");next(7);
                }
                case 7->{
                    Object t=task(p);if(t==null){check(elapsed()<15,"manual task available");return;}
                    var f=t.getClass().getDeclaredField("heightProbe");f.setAccessible(true);check(f.get(t)==null,"manual has no height probe");
                    if(!handoff(t)){check(elapsed()<15,"manual moved");return;}
                    log("PASS manual_aether no_height_probe=true position="+p.position());SearchService.cancel(p,true);fresh(p);
                    check(SearchService.startTravelBiome(p,p.getMainHandItem(),biome,List.of())!=null,"travel query accepted");next(8);
                }
                case 8->{
                    if(SearchService.activeCount()!=0){check(elapsed()<90,"travel locate");return;}
                    check(ExplorersCompass.explorersCompass.getState(p.getMainHandItem())==CompassState.FOUND,"travel biome found");
                    moved=null;check(TeleportService.travel(p,p.getMainHandItem(),result->moved=result),"travel teleport");next(9);
                }
                case 9->{
                    Object t=task(p);if(moved==null){check(elapsed()<90,"travel height check");return;}
                    check(moved==TeleportService.TravelResult.MOVED,"travel moved "+moved);
                    var f=t.getClass().getDeclaredField("heightProbe");f.setAccessible(true);check(f.get(t)!=null,"travel retains height probe");
                    check(biome.equals(TeleportService.loadedBiome(p)),"travel actual target biome");
                    log("PASS travel_service height_probe=true actual="+p.position()+" biome="+biome);SearchService.cancel(p,true);
                    for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)aether.getChunkSource().removeRegionTicket(SETUP,new ChunkPos(fixtureCenter.x+x,fixtureCenter.z+z),2,fixture);ticket=false;
                    check(VerificationSlots.count()==0 && TeleportService.ticketCount()==0,"tickets released");finished="PASS targeted 1.2.7 checks completed";next(10);
                }
            }
        }catch(Throwable error){SearchService.cancel(p,true);if(ticket)for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)aether.getChunkSource().removeRegionTicket(SETUP,new ChunkPos(fixtureCenter.x+x,fixtureCenter.z+z),2,fixture);fail(error);}
    }
    private static void fresh(ServerPlayer p){SearchService.cancel(p,true);SearchService.clearCache(p.getUUID());p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ExplorersCompass.explorersCompass));}
    private static void startStructure(ServerPlayer p,boolean async)throws Exception {
        fresh(p);if(!async)SearchService.stopAll();SearchConfig.ASYNC_STRUCTURES.set(async);String key="aether:"+new String[]{"bronze_dungeon","silver_dungeon","gold_dungeon"}[index];
        check(SearchService.requestStructures(p,p.getMainHandItem(),new ResourceLocation(key),false,false,null),"structure accepted "+key);job=currentJob(p);mode=job.mode();
    }
    private static SearchJob currentJob(ServerPlayer p)throws Exception{var f=SearchService.class.getDeclaredField("sessions");f.setAccessible(true);Object session=((Map<?,?>)f.get(null)).get(p.getUUID());var j=session.getClass().getDeclaredField("job");j.setAccessible(true);return (SearchJob)j.get(session);}
    private static Object task(ServerPlayer p)throws Exception{var f=TeleportService.class.getDeclaredField("tasks");f.setAccessible(true);return ((Map<?,?>)f.get(null)).get(p.getUUID());}
    private static boolean handoff(Object task)throws Exception{var f=task.getClass().getDeclaredField("handoffUntil");f.setAccessible(true);return f.getInt(task)>0;}
    private static void next(int n){phase=n;since=System.nanoTime();log("PHASE "+n);}
    private static double elapsed(){return (System.nanoTime()-since)/1e9;}
    private static void check(boolean b,String what){if(!b)throw new AssertionError(what);}
    private static void fail(Throwable error){error.printStackTrace();finished="FAIL phase="+phase+" "+error;}
    private static synchronized void log(String text){System.out.println("FIX127 "+text);try{Files.writeString(Path.of("targeted127-results.txt"),text+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}}
}
