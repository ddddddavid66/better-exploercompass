package com.david.compass.validation;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod(SplitProbe.ID)
@Mod.EventBusSubscriber(modid=SplitProbe.ID)
public final class SplitProbe {
    static final String ID="compasssplitprobe";
    private static final AtomicBoolean DONE=new AtomicBoolean();
    @SubscribeEvent public static void dedicated(ServerStartedEvent event){
        if(!event.getServer().isDedicatedServer())return;
        if(check("dedicated"))event.getServer().halt(false);
    }
    @Mod.EventBusSubscriber(modid=ID,value=Dist.CLIENT)
    public static final class Client {
        @SubscribeEvent public static void title(ScreenEvent.Init.Post event){
            if(event.getScreen() instanceof TitleScreen && check("client"))Minecraft.getInstance().stop();
        }
    }
    private static boolean check(String side){
        if(!DONE.compareAndSet(false,true))return false;
        Path output=Path.of(System.getProperty("compass.split.report","split-startup.txt"));
        try {
            String expected=System.getProperty("compass.split.expected","");
            boolean explorer=expected.equals("explorer") || expected.equals("both");
            boolean creature=expected.equals("creature") || expected.equals("both");
            require(explorer || creature,"invalid test mode");
            var mods=ModList.get();
            require(mods.isLoaded("explorerscompass")==explorer,"explorer mod list");
            require(mods.isLoaded("creaturecompass")==creature,"creature mod list");
            long core=mods.getMods().stream().filter(m->m.getModId().equals("compass_runtime")).count();
            require(core==1,"core must be loaded once");
            require(item("explorerscompass:explorerscompass")==explorer,"explorer item registration");
            require(item("creaturecompass:creature_compass")==creature,"creature item registration");
            require(!item("explorerscompass:creature_compass"),"legacy creature ID must not be registered");
            if(side.equals("dedicated"))checkGate();
            Files.writeString(output,"PASS\nside="+side+"\nmode="+expected+"\ncore="+core+"\n");
            return true;
        }catch(Throwable failure){
            try{Files.writeString(output,"FAIL\n"+failure+"\n");}catch(Exception ignored){}
            throw new IllegalStateException("Split startup probe failed",failure);
        }
    }
    private static void checkGate() throws Exception {
        var gate=Class.forName("com.david.compass.runtime.game.search.VerificationSlots");
        var acquire=gate.getMethod("acquire",Object.class);var release=gate.getMethod("release",Object.class);
        var owners=new Object[]{new Object(),new Object(),new Object(),new Object(),new Object()};
        try {for(int i=0;i<owners.length;i++)require((Boolean)acquire.invoke(null,owners[i])==(i<4),"four-slot boundary");}
        finally {for(var owner:owners)release.invoke(null,owner);}
        require((Integer)gate.getMethod("count").invoke(null)==0,"slots released");
    }
    private static boolean item(String id){return ForgeRegistries.ITEMS.containsKey(new ResourceLocation(id));}
    private static void require(boolean condition,String failure){if(!condition)throw new IllegalStateException(failure);}
}
