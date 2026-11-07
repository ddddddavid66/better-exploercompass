package com.david.compass.validation;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
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
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 单次整合包串联验收；不替换生产搜索、传送或地图接口。 */
@Mod.EventBusSubscriber(modid=SplitProbe.ID,value=net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class XaeroWaypointProbe {
    private static final ResourceLocation STRUCTURE=new ResourceLocation("minecraft:village_plains"), BIOME=new ResourceLocation("minecraft:plains");
    private static final Path REPORT=Path.of("xaero-verify.txt");
    private static final List<String> NOTES=new ArrayList<>();
    private static int phase,delay;
    private static long deadline;
    private static UUID previous;
    private static BlockPos structurePos,biomePos;
    private static boolean done;
    private static volatile Throwable serverFailure;
    private XaeroWaypointProbe(){}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END || !Boolean.getBoolean("compass.xaero.verify") || done)return;
        var mc=Minecraft.getInstance();
        try{
            if(serverFailure!=null)throw new IllegalStateException("server verification",serverFailure);
            if(deadline==0)deadline=System.nanoTime()+180_000_000_000L;
            if(System.nanoTime()>deadline)throw new IllegalStateException("timeout at phase "+phase+" screen="+(mc.screen==null?"none":mc.screen.getClass().getName()));
            if(warnings(mc))return;
            if(phase==0){
                if(mc.screen instanceof AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
                if(mc.screen instanceof TitleScreen){mc.options.pauseOnLostFocus=false;mc.createWorldOpenFlows().loadLevel(mc.screen,"split-world");advance(1);}
                return;
            }
            if(phase==6){
                if(mc.screen instanceof TitleScreen){mc.createWorldOpenFlows().loadLevel(mc.screen,"split-world");advance(7);}
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
                    UnifiedNetwork.CHANNEL.sendToServer(new com.chaosthedude.explorerscompass.unified.network.ClearCachePacket());
                    previous=SearchService.token(stack);
                    UnifiedNetwork.CHANNEL.sendToServer(new BiomeSearchPacket(BIOME,dimension));advance(5);
                }
                case 5 -> {
                    ItemStack stack=mc.player.getMainHandItem();
                    if (!found(stack,true) || Objects.equals(previous,SearchService.token(stack))) return;
                    require(biomePos.equals(position(stack)), "repeated query XYZ");
                    require(waypoint(biomePos), "one named biome waypoint");
                    mc.level.disconnect();mc.clearLevel(new TitleScreen());advance(6);
                }
                case 7 -> {
                    if (waypointSet()==null || !waypoint(structurePos) || !waypoint(biomePos)) return;
                    NOTES.add("Both named waypoints restored after rejoining; repeated biome query did not duplicate.");
                    Object mapSession=Class.forName("xaero.map.WorldMapSession").getMethod("getCurrentSession").invoke(null);
                    if(mapSession==null)return;
                    mc.setScreen((Screen)Class.forName("xaero.map.gui.GuiMap").getConstructors()[0]
                            .newInstance(null,null,call(mapSession,"getMapProcessor"),mc.player));advance(8);
                }
                case 8 -> {
                    Object support=Class.forName("xaero.map.mods.SupportMods").getField("xaeroMinimap").get(null);
                    var points=(Iterable<?>)call(support,"getWaypointsSorted");
                    boolean structure=false,biome=false;
                    for(var point:points){
                        var pos=new BlockPos((int)call(point,"getX"),(int)call(point,"getY"),(int)call(point,"getZ"));
                        structure |= pos.equals(structurePos) && call(point,"getName").equals(com.chaosthedude.explorerscompass.util.StructureUtils.getPrettyStructureName(STRUCTURE));
                        biome |= pos.equals(biomePos) && call(point,"getName").equals(com.chaosthedude.explorerscompass.unified.client.FilterUi.biomeName(BIOME));
                    }
                    if(!structure || !biome)return;
                    NOTES.add("Both named waypoints available from World Map waypoint menu.");
                    finish(mc,null);
                }
            }
        } catch(Throwable failure){finish(mc,failure);}
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
    private static boolean found(ItemStack s,boolean biome){return s.getItem()==ExplorersCompass.explorersCompass && ExplorersCompass.explorersCompass.getState(s)==CompassState.FOUND && SearchService.isBiome(s)==biome;}
    private static BlockPos position(ItemStack s){return new BlockPos(ExplorersCompass.explorersCompass.getFoundStructureX(s),s.getTag().getInt(SearchService.FOUND_Y),ExplorersCompass.explorersCompass.getFoundStructureZ(s));}
    private static Object waypointSet()throws Exception{
        Object session=Class.forName("xaero.common.XaeroMinimapSession").getMethod("getCurrentSession").invoke(null);if(session==null)return null;
        Object world=call(call(session,"getWaypointsManager"),"getAutoWorld");
        if(world==null)return null;
        require(((net.minecraft.resources.ResourceKey<?>)call(world,"getDimId")).location().toString().equals("minecraft:overworld"),"Xaero waypoint dimension");
        return call(world,"getCurrentSet");
    }
    private static boolean waypoint(BlockPos p)throws Exception{
        var set=waypointSet();if(set==null)return false;
        String name=p.equals(structurePos)?
                com.chaosthedude.explorerscompass.util.StructureUtils.getPrettyStructureName(STRUCTURE):
                com.chaosthedude.explorerscompass.unified.client.FilterUi.biomeName(BIOME);
        int count=0;
        for(var w:(Iterable<?>)call(set,"getWaypoints"))
            if(name.equals(call(w,"getName")) && (int)call(w,"getX")==p.getX()
                    && (int)call(w,"getY")==p.getY() && (int)call(w,"getZ")==p.getZ())count++;
        require(count<=1,"duplicate "+name);
        return count==1;
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