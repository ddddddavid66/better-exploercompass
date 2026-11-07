package com.david.compass.validation;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.network.*;
import com.chaosthedude.explorerscompass.unified.search.SearchService;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.david.compass.runtime.game.data.LandingMode;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=SplitProbe.ID,value=net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class Release132Probe {
    private static final String[] TARGETS={"cataclysm:sunken_city","cataclysm:cursed_pyramid",
            "minecraft:buried_treasure","betterstrongholds:stronghold"};
    private static final Path REPORT=Path.of("release132-verify.txt");
    private static final boolean OLD=Boolean.getBoolean("compass.release132.baseline");
    private static int index=Boolean.getBoolean("compass.release132.portalOnly")?3:0,phase;
    private static long started,deadline;
    private static UUID oldToken;
    private static BlockPos anchor,origin;
    private static volatile String verified;
    private static boolean done;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END || !Boolean.getBoolean("compass.release132.probe") || done)return;
        var mc=Minecraft.getInstance();
        try {
            if(deadline==0)deadline=System.nanoTime()+300_000_000_000L;
            if(System.nanoTime()>deadline)throw new IllegalStateException("timeout phase "+phase+" target "+TARGETS[index]+" screen "+mc.screen);
            if(mc.screen instanceof AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
            if(mc.screen!=null && mc.screen.getClass().getName().equals("net.minecraftforge.client.gui.LoadingErrorScreen")){
                for(var child:mc.screen.children())if(child instanceof Button b && b.active){
                    var c=b.getMessage().getContents();
                    if(c instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().equals("fml.button.continue")
                            || b.getMessage().getString().contains("返回主菜单") || b.getMessage().getString().equals("Continue") || b.getMessage().getString().equals("Proceed to main menu")){b.onPress();break;}
                }
                return;
            }
            if(phase==0){
                if(mc.screen instanceof TitleScreen){
                    mc.options.pauseOnLostFocus=false;
                    mc.createWorldOpenFlows().loadLevel(mc.screen,"split-world");next(1);
                }
                return;
            }
            if(phase==1 && mc.player==null && mc.screen instanceof ConfirmScreen){
                for(var child:mc.screen.children())if(child instanceof Button b && b.active){b.onPress();break;}
                return;
            }
            var player=mc.player;var server=mc.getSingleplayerServer();
            if(player==null || server==null)return;
            var target=new ResourceLocation(TARGETS[index]);
            switch(phase){
                case 1 -> {
                    UUID id=player.getUUID();
                    server.execute(()->{
                        var p=server.getPlayerList().getPlayer(id);
                        if(p!=null)p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ExplorersCompass.explorersCompass));
                    });
                    next(2);
                }
                case 2 -> {
                    var stack=player.getMainHandItem();
                    if(stack.getItem()!=ExplorersCompass.explorersCompass)return;
                    oldToken=SearchService.token(stack);
                    started=System.nanoTime();
                    note("start "+target+" from "+player.blockPosition()+" seed="+server.overworld().getSeed());
                    UnifiedNetwork.CHANNEL.sendToServer(new StructureSearchPacket(target,false,false,null,player.level().dimension().location()));
                    next(3);
                }
                case 3 -> {
                    var stack=player.getMainHandItem();
                    var compass=ExplorersCompass.explorersCompass;
                    var token=SearchService.token(stack);
                    if(token==null || token.equals(oldToken) || compass.getState(stack)!=CompassState.FOUND
                            || !target.equals(compass.getStructureKey(stack)))return;
                    anchor=new BlockPos(compass.getFoundStructureX(stack),stack.getTag().getInt(SearchService.FOUND_Y),compass.getFoundStructureZ(stack));
                    note("found "+target+" "+anchor+" token="+token+" search_ms="+elapsed());
                    if(index<2){index++;next(2);return;}
                    origin=player.blockPosition();started=System.nanoTime();
                    UnifiedNetwork.CHANNEL.sendToServer(new TeleportRequestPacket(token,UUID.randomUUID(),true,LandingMode.RELAXED_ONLY));
                    next(4);
                }
                case 4 -> {
                    if(player.blockPosition().distSqr(origin)<16)return;
                    UUID id=player.getUUID();BlockPos fromResult=anchor;boolean portal=index==3;
                    server.execute(()->{
                        var p=server.getPlayerList().getPlayer(id);
                        var at=p.blockPosition();var level=p.serverLevel();
                        if(portal){
                            boolean frame=false;
                            for(var b:BlockPos.betweenClosed(at.offset(-3,-3,-3),at.offset(3,3,3))){
                                var chunk=level.getChunkSource().getChunkNow(b.getX()>>4,b.getZ()>>4);
                                if(chunk==null)continue;
                                var state=chunk.getBlockState(b);
                                if(!state.is(Blocks.END_PORTAL_FRAME) || Math.abs(p.getY()-b.getY())>2.001)continue;
                                var outward=state.getValue(net.minecraft.world.level.block.EndPortalFrameBlock.FACING).getOpposite();
                                var edge=b.relative(outward);
                                var outer=edge.relative(outward);
                                if(edge.atY(at.getY()).equals(at) || outer.atY(at.getY()).equals(at)){frame=true;break;}
                            }
                            verified=frame && !level.getBlockState(at).is(Blocks.END_PORTAL)?"portal outside "+at:"FAIL portal "+at;
                        } else {
                            boolean chest=false;var chunk=level.getChunkSource().getChunkNow(at.getX()>>4,at.getZ()>>4);
                            if(chunk!=null)for(int y=at.getY()-1;y>=level.getMinBuildHeight();y--)
                                if(chunk.getBlockState(new BlockPos(at.getX(),y,at.getZ())).is(Blocks.CHEST)){chest=true;break;}
                            verified=at.getX()==fromResult.getX() && at.getZ()==fromResult.getZ() && chest?"treasure same column "+at:"FAIL treasure "+at;
                        }
                    });
                    next(5);
                }
                case 5 -> {
                    if(verified==null)return;
                    note(verified+" click_ms="+elapsed());
                    if(verified.startsWith("FAIL"))throw new IllegalStateException(verified);
                    if(index==3 || OLD){note("PASS");done=true;mc.stop();return;}
                    verified=null;index++;next(2);
                }
                default -> throw new IllegalStateException("unexpected phase");
            }
        } catch(Throwable err) {
            try{note("FAIL "+err);}catch(Exception ignored){}
            err.printStackTrace();done=true;mc.stop();
        }
    }
    private static void next(int p){phase=p;deadline=System.nanoTime()+240_000_000_000L;}
    private static double elapsed(){return (System.nanoTime()-started)/1e6;}
    private static void note(String line)throws java.io.IOException{
        Files.writeString(REPORT,line+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
}

