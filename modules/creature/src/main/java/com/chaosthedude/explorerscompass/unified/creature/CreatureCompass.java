package com.chaosthedude.explorerscompass.unified.creature;

import com.chaosthedude.explorerscompass.unified.creature.network.CreatureNetwork;
import com.chaosthedude.explorerscompass.unified.creature.search.CreatureSearch;
import com.chaosthedude.explorerscompass.unified.creature.search.CreatureServerParticipant;
import com.david.compass.runtime.game.search.SearchReplacement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

@Mod(CreatureIdentity.MOD_ID)
public final class CreatureCompass {
    public CreatureCompass(){
        CreatureConfig.register(FMLPaths.CONFIGDIR.get(),CreatureIdentity.LOGGER::warn);
        var bus=FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener(this::items);bus.addListener(this::creative);bus.addListener(this::setup);
    }
    private void items(RegisterEvent event){
        event.register(ForgeRegistries.Keys.ITEMS,helper->{
            CreatureItem.INSTANCE=new CreatureItem();
            helper.register(new ResourceLocation(CreatureIdentity.MOD_ID,"creature_compass"),CreatureItem.INSTANCE);
        });
    }
    private void creative(BuildCreativeModeTabContentsEvent event){
        if(event.getTabKey()==CreativeModeTabs.TOOLS_AND_UTILITIES)event.accept(CreatureItem.INSTANCE);
    }
    private void setup(FMLCommonSetupEvent event){
        CreatureNetwork.register();CreatureServerParticipant.register();
        SearchReplacement.register(CreatureIdentity.MOD_ID,player->CreatureSearch.cancel(player.getUUID()));
    }
}
