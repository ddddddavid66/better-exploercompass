package com.david.compass.validation;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.unified.creature.*;
import com.chaosthedude.explorerscompass.unified.creature.catalog.CreatureCatalog;
import com.chaosthedude.explorerscompass.unified.creature.client.*;
import com.chaosthedude.explorerscompass.unified.creature.map.*;
import com.chaosthedude.explorerscompass.unified.creature.search.*;
import com.chaosthedude.explorerscompass.unified.network.*;
import com.chaosthedude.explorerscompass.unified.search.SearchService;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.david.compass.runtime.game.data.LandingMode;
import com.david.compass.runtime.game.search.TeleportService;
import com.david.compass.runtime.game.search.VerificationSlots;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** 单次整合包串联验收；不替换生产搜索、传送或地图接口。 */
@Mod.EventBusSubscriber(modid=SplitProbe.ID,value=net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class PackFeatureProbe {
    private static final ResourceLocation STRUCTURE=new ResourceLocation("dungeons_plus:leviathan"), BIOME=new ResourceLocation("minecraft:plains");
    private static final Path REPORT=Path.of("split-feature.txt");
    private static final List<String> NOTES=new ArrayList<>();
    private static int phase,delay;
    private static long deadline;
    private static UUID previous;
    private static BlockPos structurePos,biomePos;
    private static volatile ResourceLocation type;
    private static volatile boolean verified,done;
    private static volatile Throwable serverFailure;
    private PackFeatureProbe(){}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END || !Boolean.getBoolean("compass.split.full") || done)return;
        var mc=Minecraft.getInstance();
        try{
            if(serverFailure!=null)throw new IllegalStateException("server verification",serverFailure);
            if(deadline==0)deadline=System.nanoTime()+180_000_000_000L;
            if(System.nanoTime()>deadline)throw new IllegalStateException("timeout at phase "+phase+" screen="+(mc.screen==null?"none":mc.screen.getClass().getName())+" creature="+CreatureClient.state);
            if(warnings(mc))return;
            if(phase==0){
                if(mc.screen instanceof AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
                if(mc.screen instanceof TitleScreen){mc.options.pauseOnLostFocus=false;mc.createWorldOpenFlows().loadLevel(mc.screen,"split-world");advance(1);}
                return;
            }
            if(mc.player==null || mc.getSingleplayerServer()==null || delay-->0)return;
            var dimension=mc.player.level().dimension().location();
            switch(phase){
                case 1 -> {
                    onServer(p->{require(p.serverLevel().getSeed()==789123,"world seed");
                        require(p.serverLevel().dimension().location().toString().equals("minecraft:overworld"),"overworld");
                        p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ExplorersCompass.explorersCompass));});
                    advance(2);
                }
                case 2 -> {
                    if(mc.player.getMainHandItem().getItem()!=ExplorersCompass.explorersCompass || waypointSet()==null)return;
                    ConfigHandler.CLIENT.landingMode.set(LandingMode.SAFE_ONLY);
                    CreatureConfig.CLIENT.landingMode.set(LandingMode.RELAXED_ONLY);
                    CreatureConfig.CLIENT.creatureRounds.set(0);
                    UnifiedNetwork.CHANNEL.sendToServer(new LandingModePacket(LandingMode.SAFE_ONLY));
                    UnifiedNetwork.CHANNEL.sendToServer(new StructureSearchPacket(STRUCTURE,false,false,null,dimension));advance(3);
                }
                case 3 -> {
                    ItemStack stack=mc.player.getMainHandItem();if(!found(stack,false))return;
                    require(STRUCTURE.equals(ExplorersCompass.explorersCompass.getStructureKey(stack)),"actual structure ID");
                    structurePos=position(stack);if(!waypoint(structurePos))return;
                    NOTES.add("Structure and Xaero waypoint: "+STRUCTURE+" "+structurePos);
                    previous=SearchService.token(stack);UnifiedNetwork.CHANNEL.sendToServer(new BiomeSearchPacket(BIOME,dimension));advance(4);
                }
                case 4 -> {
                    ItemStack stack=mc.player.getMainHandItem();if(!found(stack,true) || Objects.equals(previous,SearchService.token(stack)))return;
                    require(BIOME.toString().equals(stack.getTag().getString(SearchService.BIOME_KEY)),"actual biome ID");
                    biomePos=position(stack);if(!waypoint(biomePos))return;
                    NOTES.add("Biome and Xaero waypoint: "+BIOME+" "+biomePos);
                    onServer(PackFeatureProbe::prepareCreature);advance(5);
                }
                case 5 -> {
                    if(type==null || mc.player.getMainHandItem().getItem()!=CreatureItem.INSTANCE)return;
                    CreatureClient.open();advance(6);
                }
                case 6 -> {
                    if(!CreatureWorldCatalog.ready() || CreatureClient.entries.stream().noneMatch(e->e.entity().equals(type)))return;
                    CreatureClient.search(type,dimension,null);advance(7);
                }
                case 7 -> {
                    if(!CreatureClient.finished)return;
                    require(CreatureClient.state.equals("found"),"creature search: "+CreatureClient.state);
                    MobPoint point=Objects.requireNonNull(CreatureClient.selected());
                    onServer(p->verifyCreature(p,point));mc.setScreen(null);advance(8);
                }
                case 8 -> {
                    if(!verified || MinimapAdapter.rendered==0)return;
                    require(ConfigHandler.CLIENT.landingMode.get()==LandingMode.SAFE_ONLY && CreatureConfig.CLIENT.landingMode.get()==LandingMode.RELAXED_ONLY,"independent client mode");
                    NOTES.add("Minimap blue renderer calls: "+MinimapAdapter.rendered);
                    Object session=Class.forName("xaero.map.WorldMapSession").getMethod("getCurrentSession").invoke(null);
                    if(session==null)return;
                    Object processor=call(session,"getMapProcessor");
                    mc.setScreen((Screen)Class.forName("xaero.map.gui.GuiMap").getConstructors()[0].newInstance(null,null,processor,mc.player));advance(9);
                }
                case 9 -> {
                    if(WorldMapAdapter.rendered==0)return;
                    var button=mc.screen.children().stream().filter(w->w instanceof Button b && b.getMessage().getString().contains("生物定位蓝点")).map(w->(Button)w).findFirst().orElseThrow();
                    boolean before=CreatureClient.dots();button.onPress();require(CreatureClient.dots()!=before,"map button toggles");button.onPress();
                    require(waypoint(structurePos) && waypoint(biomePos),"waypoints remain after creature search");
                    NOTES.add("World map blue renderer calls: "+WorldMapAdapter.rendered+"; toggle verified");
                    mc.setScreen(null);CreatureClient.stop();advance(10);delay=20;
                }
                case 10 -> {onServer(p->{TeleportService.cancel(p.getUUID());require(VerificationSlots.count()==0 && VerificationSlots.queued()==0,"tickets released");verified=false;});advance(11);delay=20;}
                case 11 -> {if(!verified)finish(mc,null);}
                default -> throw new IllegalStateException("unexpected phase");
            }
        }catch(Throwable failure){finish(mc,failure);}
    }
    private static boolean warnings(Minecraft mc){
        if(mc.screen!=null && mc.screen.getClass().getName().equals("net.minecraftforge.client.gui.LoadingErrorScreen")){
            for(var widget:mc.screen.children())if(widget instanceof Button b && b.active){
                var contents=b.getMessage().getContents();String key=contents instanceof net.minecraft.network.chat.contents.TranslatableContents t?t.getKey():"";
                if(key.equals("fml.button.continue") || b.getMessage().getString().equals("继续返回主菜单") || b.getMessage().getString().equals("Continue")){b.onPress();break;}
            }
            return true;
        }
        if(phase==1 && mc.player==null && mc.screen instanceof ConfirmScreen){for(var widget:mc.screen.children())if(widget instanceof Button b){b.onPress();break;}return true;}
        return false;
    }
    private static void prepareCreature(ServerPlayer p){
        p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CreatureItem.INSTANCE));
        var level=p.serverLevel();var server=p.getServer();LivingEntity nearest=null;
        for(var e:level.getAllEntities())if(e instanceof LivingEntity live && !(e instanceof Player) && live.isAlive() && e.distanceToSqr(p)<64*64){
            var id=ForgeRegistries.ENTITY_TYPES.getKey(e.getType());var entry=CreatureCatalog.get(server,id);
            if(entry!=null && entry.dimensions().contains(level.dimension().location()) && CreatureIndex.point(level,e)!=null
                    && (nearest==null || e.distanceToSqr(p)<nearest.distanceToSqr(p)))nearest=live;
        }
        if(nearest==null){
            var cow=net.minecraft.world.entity.EntityType.COW.create(level);require(cow!=null,"controlled live creature");
            cow.moveTo(p.getX()+4,p.getY(),p.getZ());cow.setNoAi(true);cow.setNoGravity(true);cow.setInvulnerable(true);
            require(level.addFreshEntity(cow),"controlled live creature inserted");nearest=cow;NOTES.add("Using controlled live cow (no suitable naturally loaded entity nearby)");
        }
        type=ForgeRegistries.ENTITY_TYPES.getKey(nearest.getType());
    }
    private static void verifyCreature(ServerPlayer p,MobPoint point)throws Exception{
        var real=CreatureIndex.point(p.serverLevel(),p.serverLevel().getEntity(point.uuid()));
        require(real!=null && real.type().equals(point.type()) && real.biome().equals(point.biome()) && real.dimension().equals(point.dimension()),"live UUID/type/biome/dimension");
        var tasks=(Map<?,?>)field(CreatureSearch.class,null,"tasks");var query=Objects.requireNonNull(tasks.get(p.getUUID()));
        require(field(query.getClass(),query,"mode")==LandingMode.RELAXED_ONLY && TeleportService.mode(p.getUUID())==LandingMode.SAFE_ONLY,"independent server landing policies");
        NOTES.add("Creature verified: "+point.type()+" "+point.uuid()+"; Explorer SAFE_ONLY, Creature RELAXED_ONLY");verified=true;
    }
    private static boolean found(ItemStack s,boolean biome){return s.getItem()==ExplorersCompass.explorersCompass && ExplorersCompass.explorersCompass.getState(s)==CompassState.FOUND && SearchService.isBiome(s)==biome;}
    private static BlockPos position(ItemStack s){return new BlockPos(ExplorersCompass.explorersCompass.getFoundStructureX(s),s.getTag().getInt(SearchService.FOUND_Y),ExplorersCompass.explorersCompass.getFoundStructureZ(s));}
    private static Object waypointSet()throws Exception{
        Object session=Class.forName("xaero.common.XaeroMinimapSession").getMethod("getCurrentSession").invoke(null);if(session==null)return null;
        Object world=call(call(session,"getWaypointsManager"),"getAutoWorld");return world==null?null:call(world,"getCurrentSet");
    }
    private static boolean waypoint(BlockPos p)throws Exception{
        var set=waypointSet();if(set==null)return false;
        for(var w:(Iterable<?>)call(set,"getWaypoints"))if((int)call(w,"getX")==p.getX() && (int)call(w,"getY")==p.getY() && (int)call(w,"getZ")==p.getZ())return true;
        return false;
    }
    private static Object call(Object target,String method)throws Exception{return target.getClass().getMethod(method).invoke(target);}
    private static Object field(Class<?> type,Object target,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private interface ServerCheck {void run(ServerPlayer player)throws Exception;}
    private static void onServer(ServerCheck work){var mc=Minecraft.getInstance();var server=mc.getSingleplayerServer();UUID id=mc.player.getUUID();server.execute(()->{try{work.run(Objects.requireNonNull(server.getPlayerList().getPlayer(id)));}catch(Throwable failure){serverFailure=failure;}});}
    private static void advance(int next){phase=next;delay=5;deadline=System.nanoTime()+180_000_000_000L;}
    private static void require(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
    private static void finish(Minecraft mc,Throwable failure){
        done=true;try{Files.writeString(REPORT,(failure==null?"PASS":"FAIL phase="+phase+" "+failure)+"\n"+String.join("\n",NOTES)+"\n");}catch(Exception error){error.printStackTrace();}
        if(failure!=null)failure.printStackTrace();mc.stop();
    }
}
