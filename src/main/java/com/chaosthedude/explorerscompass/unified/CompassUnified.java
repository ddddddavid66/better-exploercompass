package com.chaosthedude.explorerscompass.unified;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.data.ConfigMigration;
import net.minecraftforge.fml.loading.FMLPaths;

/** 同一模组的基础设施；注册和客户端事件仍只使用原 explorerscompass ID。 */
public final class CompassUnified {
    public static final String MOD_ID = ExplorersCompass.MODID;
    public static final org.apache.logging.log4j.Logger LOGGER = ExplorersCompass.LOGGER;
    private CompassUnified() {}
    public static void initialize() {
        ConfigMigration.merge(FMLPaths.CONFIGDIR.get().resolve("explorerscompass-common.toml"),
                FMLPaths.CONFIGDIR.get().resolve("explorerscompassfilters-server.toml"), LOGGER::warn);
    }
}
