package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.gui.ExplorersCompassScreen;
import com.chaosthedude.explorerscompass.unified.entertainment.*;
import com.chaosthedude.explorerscompass.unified.search.TeleportService;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.resources.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.*;
import net.minecraft.world.level.levelgen.Heightmap;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.unified.network.*;
import com.chaosthedude.explorerscompass.unified.data.LandingMode;
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
public final class Entertainment113Checks {
    private static int stage,ticks,until,lastCount=-1;
    private static long deadline=System.nanoTime()+1_800_000_000_000L;
    private static volatile Throwable error;
    private static volatile boolean checked;
    private static volatile Vec3 origin;
    private static String lastPhase="";
    private static final boolean AETHER=Boolean.getBoolean("unified.entertainment113.aether");
    private static boolean ancientAether() {return net.minecraftforge.fml.ModList.get().isLoaded("ancient_aether");}
    private static final EntertainmentFeature FEATURE=AETHER?EntertainmentFeature.AETHER_BIOMES:EntertainmentFeature.ADVENTURING_TIME;
    private static final Set<ResourceLocation> actualVisits=new HashSet<>();
    private static Set<ResourceLocation> required=Set.of();
    private static boolean modeChecked;
    private static volatile boolean setupDone;
    private static EntertainmentUi popup;
    private static final List<String> notes=new ArrayList<>();
    private static WorldDataConfiguration worldConfiguration(Minecraft mc) throws Exception {
        if(!AETHER)return WorldDataConfiguration.DEFAULT;
        // 与整合包实际启用的内置包一致；DEFAULT 会遗漏附属模组必需的噪声覆盖。
        var repository=net.minecraft.server.packs.repository.ServerPacksSource.createPackRepository(mc.gameDirectory.toPath().resolve("test-datapacks"));
        repository.reload();
        var selected=new ArrayList<String>();selected.add("vanilla");
        // 模组基础资源必须先于覆盖包；否则 Forge 会追加基础包并覆盖刚选中的噪声配置。
        selected.addAll(net.minecraftforge.resource.ResourcePackLoader.getPackNames());
        for(String id:List.of("aether_curios_override","aether_ruined_portal","aether_temporary_freezing",
                "ancient_aether_worldgen_overrides","compat_recipes/aether_lost_content_compat",
                "compat_recipes/aether_redux_compat","compat_recipes/ancient_aether_compat",
                "compatibility/aether_redux","compatibility/deep_aether","compatibility/lost_aether_content",
                "data/ancient_aether_data","data/cloudbed","data/deep_aether_data","data/dungeon_stone_recipes",
                "data/dungeon_upgrades/bronze","data/gravitite_ingot","data/lost_content_data",
                "overrides/golden_swet_ball/DAGoldenSwetBallAetherReduxFixData"))
            if(repository.getAvailableIds().contains("builtin/"+id))selected.add("builtin/"+id);
        if(ancientAether())check(selected.contains("builtin/ancient_aether_worldgen_overrides"),"worldgen override pack available");
        Files.writeString(Path.of("entertainment113-datapacks.txt"),String.join("\n",selected));
        return new WorldDataConfiguration(new DataPackConfig(selected,List.of()),WorldDataConfiguration.DEFAULT.enabledFeatures());
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!Boolean.getBoolean("unified.entertainment113") || event.phase!=TickEvent.Phase.END || stage==100)return;
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
                mc.createWorldOpenFlows().createFreshLevel("entertainment-113-"+System.currentTimeMillis(),
                    new LevelSettings("Entertainment 113 isolated",GameType.SURVIVAL,false,Difficulty.PEACEFUL,false,new GameRules(),worldConfiguration(mc)),
                    new WorldOptions(789123L,true,false),WorldPresets::createNormalWorldDimensions);
                stage=1;until=ticks+40;return;
            }
            if(mc.player==null || mc.getSingleplayerServer()==null)return;
            var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
            if(stage==1) {
                if(!mc.player.onGround())return;
                server.execute(()->{try {
                    var player=server.getPlayerList().getPlayer(id);
                    check(!server.getWorldData().getAllowCommands(),"cheats disabled");
                    check(!server.getPlayerList().isOp(player.getGameProfile()),"non-OP player");
                    check(player.gameMode.getGameModeForPlayer()==GameType.SURVIVAL,"survival player");
                    player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ExplorersCompass.explorersCompass));
                    check(TeleportService.allowedTravel(player,player.getMainHandItem()),"travel permitted without cheats");
                    check(AdventureTravel.MAX_ATTEMPTS==5,"five candidates configured");
                    if(AETHER) {
                        if(ancientAether())check(server.getPackRepository().getSelectedIds().contains("builtin/ancient_aether_worldgen_overrides"),"required Aether worldgen overrides enabled");
                        var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,FEATURE.dimension()));
                        check(level!=null,"Aether dimension exists");
                        var sampler=level.getChunkSource().randomState().sampler();
                        boolean erosion=false;
                        for(int x=0;x<4096;x+=256)if(sampler.sample(x,16,-x).erosion()!=0)erosion=true;
                        if(ancientAether())check(erosion,"Aether worldgen override erosion actually loaded");
                        Files.writeString(Path.of("entertainment113-datapacks.txt"),String.join("\n",server.getPackRepository().getSelectedIds()));
                        Vec3 landing=setupLanding(player,level);
                        player.teleportTo(level,landing.x,landing.y,landing.z,0,0);
                        required=CatalogService.snapshot(level,UUID.randomUUID()).dimensionBiomes().get(FEATURE.dimension());
                        check(required!=null && !required.isEmpty(),"runtime Aether biome directory");
                        Files.writeString(Path.of("entertainment113-required.txt"),String.join("\n",required.stream().map(Object::toString).sorted().toList()));
                    } else {
                        var adv=AdventureRequirements.advancement(player);
                        check(!player.getAdvancements().getOrStartProgress(adv).isDone(),"advancement initially unfinished");
                        check(AdventureRequirements.read(adv).size()==53,"runtime vanilla requirements=53");
                    }
                    origin=player.position();
                    TeleportService.setMode(id,AETHER?LandingMode.RELAXED_ONLY:LandingMode.DEFAULT);
                    check(CatalogService.snapshot(player,UUID.randomUUID()).canTeleport(),"catalog permits non-OP teleport");
                    setupDone=true;
                }catch(Throwable failure){error=failure;}});
                stage=2;until=ticks+20;return;
            }
            if(stage==2) {
                if(!setupDone || !mc.level.dimension().location().equals(FEATURE.dimension())
                        || mc.player.getMainHandItem().getItem()!=ExplorersCompass.explorersCompass)return;
                ConfigHandler.CLIENT.landingMode.set(AETHER?LandingMode.RELAXED_ONLY:LandingMode.DEFAULT);
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
                var search=(EditBox)field(popup,"search");
                // 先选择另一维度的功能，确认必须保持禁用。
                search.setValue(AETHER?"探索的时光":"天境全维度");
                int px=(int)field(popup,"left"),py=(int)field(popup,"top");
                screen.mouseClicked(px+12,py+40,0);check(!confirm.active,"wrong-dimension confirm disabled");
                search.setValue(AETHER?"tianjingquanweidu":"tansuodeshiguang");
                check(((List<?>)field(popup,"matches")).size()==1,"full pinyin search");
                search.setValue(AETHER?"tjqwd":"tsdsg");
                check(((List<?>)field(popup,"matches")).size()==1,"initial pinyin search");
                search.setValue(AETHER?"天境":"探索");
                check(((List<?>)field(popup,"matches")).size()==1,"Chinese search");
                int x=(int)field(popup,"left"),y=(int)field(popup,"top");
                screen.mouseClicked(x+12,y+40,0);check(confirm.active,"selection enables confirm");
                stage=4;until=ticks+5;return;
            }
            if(stage==4) {
                check(!EntertainmentClient.active(),"selection alone does not start");
                Screenshot.grab(mc.gameDirectory,"entertainment113-ui.png",mc.getMainRenderTarget(),ignored->{});
                var confirm=(Button)field(popup,"confirm");
                mc.screen.mouseClicked(confirm.getX()+4,confirm.getY()+4,0);stage=5;until=ticks+5;return;
            }
            var state=EntertainmentClient.state();
            if(stage==5) {
                if(state==null || !state.active())return;
                notes.add("PASS UI: dimension gate, selection/confirmation separation, Chinese/full/initial pinyin");
                mc.setScreen(null);stage=6;
            }
            if(stage==6) {
                if(state==null)return;
                check(state.feature()==FEATURE,"correct feature status");
                check(state.mode()==(AETHER?LandingMode.RELAXED_ONLY:LandingMode.DEFAULT),"selected mode inherited");
                check(state.attempts()<=5,"candidate retry bound");
                modeChecked=true;
                if(AETHER && state.active())server.execute(()->{
                    var player=server.getPlayerList().getPlayer(id);
                    player.serverLevel().getBiome(player.blockPosition()).unwrapKey().ifPresent(key->{
                        if(actualVisits.add(key.location()))try {
                            Files.writeString(Path.of("entertainment113-actual.txt"),key.location()+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                        }catch(Exception failure){error=failure;}
                    });
                });
                if(lastCount!=state.completed() || !lastPhase.equals(state.phase())) {
                    lastCount=state.completed();lastPhase=state.phase();
                    String line="feature="+state.feature()+" mode="+state.mode()+" attempt="+state.attempts()+" visited="+state.completed()+"/"+state.total()+" phase="+state.phase()+" biome="+state.biome()+" detail="+state.detail();
                    System.out.println("ENTERTAINMENT113: "+line);
                    Files.writeString(Path.of("entertainment113-progress.txt"),line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                }
                if(state.active())return;
                check(state.phase().equals("completed"),"journey ended early: "+state);
                server.execute(()->{try {
                    var player=server.getPlayerList().getPlayer(id);
                    if(AETHER)check(actualVisits.containsAll(required),"all runtime Aether biomes really visited: "+actualVisits.size()+"/"+required.size());
                    else {
                        var adv=AdventureRequirements.advancement(player);
                        check(player.getAdvancements().getOrStartProgress(adv).isDone(),"vanilla advancement really completed");
                        for(var visit:AdventureRequirements.read(adv))check(AdventureRequirements.visited(player,adv,visit),"unvisited "+visit.biome());
                    }
                    check(modeChecked,"mode status observed");
                    check(player.position().distanceTo(origin)<2,"returned to departure point: "+player.position()+" vs "+origin);
                    checked=true;
                }catch(Throwable failure){error=failure;}});
                stage=7;until=ticks+60;return;
            }
            if(stage==7 && checked) {
                server.execute(()->{try{check(TeleportService.ticketCount()==0,"landing tickets released");}
                    catch(Throwable failure){error=failure;}});
                notes.add("PASS: "+(AETHER?required.size()+" runtime Aether biomes really visited":"53 vanilla criteria naturally completed")+"; departure point restored; non-OP survival without cheats");
                stage=8;until=ticks+10;return;
            }
            if(stage==8)finish(mc,"PASS: one "+FEATURE+" end-to-end acceptance\n"+String.join("\n",notes));
        }catch(Throwable failure){failure.printStackTrace();finish(mc,"FAIL: "+failure+"\n"+String.join("\n",notes));}
    }
    private static Vec3 setupLanding(ServerPlayer player,ServerLevel level) {
        // 仅测试起点准备：不调用娱乐功能、不改地形、不预先到访目标名单。
        for(int radius=0;radius<=16;radius++)for(int x=-radius;x<=radius;x++)for(int z=-radius;z<=radius;z++) {
            if(Math.max(Math.abs(x),Math.abs(z))!=radius)continue;
            int bx=x*16+8,bz=z*16+8;var chunk=level.getChunk(bx>>4,bz>>4);
            int y=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,bx&15,bz&15)+1;
            var pos=new BlockPos(bx,y,bz);
            if(TeleportService.safe(player,level,ItemStack.EMPTY,pos))return new Vec3(bx+.5,y,bz+.5);
        }
        throw new AssertionError("no initial Aether landing");
    }
    private static Object field(Object object,String name)throws Exception {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void finish(Minecraft mc,String message) {
        stage=100;try{Files.writeString(Path.of("entertainment113-results.txt"),message);}catch(Exception failure){throw new IllegalStateException(failure);}mc.stop();
    }
}
