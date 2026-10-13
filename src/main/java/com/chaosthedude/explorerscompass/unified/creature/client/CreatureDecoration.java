package com.chaosthedude.explorerscompass.unified.creature.client;

import com.chaosthedude.explorerscompass.unified.creature.CreatureItem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterItemDecorationsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid="explorerscompass",value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class CreatureDecoration {
    @SubscribeEvent public static void register(RegisterItemDecorationsEvent event){event.register(CreatureItem.INSTANCE,(g,font,stack,x,y)->{g.drawString(font,"生",x+7,y+8,0xff5dc5ff,true);return true;});}
}
