package com.chaosthedude.explorerscompass.unified.creature.map;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.creature.client.CreatureClient;
import com.chaosthedude.explorerscompass.unified.creature.search.MobPoint;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid="explorerscompass",value=Dist.CLIENT)
public final class CreatureMaps {
    private static boolean world,mini,failed;
    private CreatureMaps() {}
    public static void tick(){
        if(failed || net.minecraft.client.Minecraft.getInstance().player==null)return;
        try {
            if(!world && ModList.get().isLoaded("xaeroworldmap")){WorldMapAdapter.install();world=true;}
            if(!mini && ModList.get().isLoaded("xaerominimap")){MinimapAdapter.install();mini=true;}
        }catch(LinkageError|RuntimeException error){failed=true;ExplorersCompass.LOGGER.warn("生物蓝点接口不可用；定位不受影响",error);net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(net.minecraft.network.chat.Component.literal("生物蓝点接口不兼容，详情见日志；定位仍可使用。"),false);}
    }
    @SubscribeEvent public static void init(ScreenEvent.Init.Post event){if(world && !failed)try{WorldMapAdapter.button(event.getScreen());}catch(LinkageError|RuntimeException error){failed=true;ExplorersCompass.LOGGER.warn("地图生物按钮不可用",error);}}
    public static String label(MobPoint p){return CreatureClient.entries.stream().filter(e->e.entity().equals(p.type())).findFirst().map(e->e.name()).orElse(p.type().toString());}
    public static void draw(GuiGraphics g){g.fill(-4,-2,4,2,0xffc0e8ff);g.fill(-2,-4,2,4,0xffc0e8ff);g.fill(-3,-2,3,2,0xff168cff);g.fill(-2,-3,2,3,0xff168cff);}
}
