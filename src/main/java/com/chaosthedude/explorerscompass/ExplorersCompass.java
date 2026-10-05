package com.chaosthedude.explorerscompass;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod(ExplorersCompass.MODID)
public class ExplorersCompass {

	public static final String MODID = "explorerscompass";

	public static final Logger LOGGER = LogManager.getLogger(MODID);

	public static ExplorersCompassItem explorersCompass;

	public static boolean canTeleport;
	public static int maxNextSearches;
	public static boolean infiniteXp;
	public static List<ResourceLocation> allowedStructureKeys = new ArrayList<>();
	public static Map<ResourceLocation, Integer> xpLevelsForAllowedStructureKeys = new HashMap<>();
	public static ListMultimap<ResourceLocation, ResourceLocation> dimensionKeysForAllowedStructureKeys = ArrayListMultimap.create();
	public static Map<ResourceLocation, ResourceLocation> structureKeysToTypeKeys = new HashMap<>();

	public ExplorersCompass() {
		com.chaosthedude.explorerscompass.unified.CompassUnified.initialize();
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
		FMLJavaModLoadingContext.get().getModEventBus().addListener(this::buildCreativeTabContents);

		ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ConfigHandler.GENERAL_SPEC);
		ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ConfigHandler.CLIENT_SPEC);
	}

	private void commonSetup(FMLCommonSetupEvent event) {
        com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork.register();
    }

	private void buildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
		if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
			event.accept(new ItemStack(explorersCompass));
		}
	}

}
