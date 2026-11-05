package com.chaosthedude.explorerscompass.unified.creature;

import com.david.compass.runtime.SearchParameters;
import com.chaosthedude.explorerscompass.unified.data.AtomicFiles;
import com.chaosthedude.explorerscompass.unified.data.LandingMode;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.function.Consumer;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** 生物产品的配置所有权独立；迁移只复制，不回写探险者配置。 */
public final class CreatureConfig {
    private static final ForgeConfigSpec.Builder COMMON_BUILDER=new ForgeConfigSpec.Builder(), CLIENT_BUILDER=new ForgeConfigSpec.Builder();
    public static final Common GENERAL=new Common(COMMON_BUILDER);
    public static final Client CLIENT=new Client(CLIENT_BUILDER);
    public static final ForgeConfigSpec COMMON_SPEC=COMMON_BUILDER.build(), CLIENT_SPEC=CLIENT_BUILDER.build();
    private static final String COMMON_FILE="creaturecompass-common.toml", CLIENT_FILE="creaturecompass-client.toml";
    private CreatureConfig() {}

    public static final class Common {
        public final ForgeConfigSpec.BooleanValue allowTeleport,allowCreaturePreExplore,asyncBiomeSearch;
        public final ForgeConfigSpec.IntValue radius,spacing,vertical,depth;
        public final ForgeConfigSpec.LongValue samples;
        private Common(ForgeConfigSpec.Builder b) {
            b.push("General"); allowTeleport=b.define("allowTeleport",true);
            allowCreaturePreExplore=b.define("allowCreaturePreExplore",true); b.pop();
            b.push("Search"); asyncBiomeSearch=b.define("asyncBiomeSearch",true);
            radius=b.defineInRange("searchRadius",80000,0,1000000);
            samples=b.defineInRange("maxBiomeSamples",32000000L,0L,8000000000L);
            spacing=b.defineInRange("biomeSampleSpacing",32,4,512);
            vertical=b.defineInRange("biomeVerticalSampleSpacing",64,0,512);
            depth=b.defineInRange("biomeDepthSampleInterval",4,1,64); b.pop();
        }
        public SearchParameters snapshot() {
            return new SearchParameters(radius.get(),samples.get(),spacing.get(),vertical.get(),depth.get(),asyncBiomeSearch.get());
        }
    }
    public static final class Client {
        public final ForgeConfigSpec.IntValue creatureRounds;
        public final ForgeConfigSpec.BooleanValue creatureDots;
        public final ForgeConfigSpec.ConfigValue<String> creatureDimension,creatureBiome;
        public final ForgeConfigSpec.EnumValue<LandingMode> landingMode;
        private Client(ForgeConfigSpec.Builder b) {
            creatureRounds=b.defineInRange("creatureRounds",4,0,32); creatureDots=b.define("creatureDots",true);
            creatureDimension=b.define("creatureDimension",""); creatureBiome=b.define("creatureBiome","");
            b.push("Client"); landingMode=b.defineEnum("landingMode",LandingMode.DEFAULT); b.pop();
        }
    }
    public static void register(Path directory,Consumer<String> warning) {
        migrate(directory,warning);
        var loading=ModLoadingContext.get();
        loading.registerConfig(ModConfig.Type.COMMON,COMMON_SPEC,COMMON_FILE);
        loading.registerConfig(ModConfig.Type.CLIENT,CLIENT_SPEC,CLIENT_FILE);
    }
    public static void migrate(Path dir, Consumer<String> warning) {
        copy(dir,"explorerscompass-common.toml",COMMON_FILE,List.of("General.allowTeleport","General.allowCreaturePreExplore",
                "Search.asyncBiomeSearch","Search.searchRadius","Search.maxBiomeSamples","Search.biomeSampleSpacing",
                "Search.biomeVerticalSampleSpacing","Search.biomeDepthSampleInterval"),warning);
        copy(dir,"explorerscompass-client.toml",CLIENT_FILE,List.of("creatureRounds","creatureDots","creatureDimension",
                "creatureBiome","Client.landingMode"),warning);
    }
    private static void copy(Path dir,String oldName,String newName,List<String> keys,Consumer<String> warning) {
        Path old=dir.resolve(oldName), target=dir.resolve(newName);
        if(Files.exists(target) || !Files.isRegularFile(old))return;
        try(var reader=Files.newBufferedReader(old,StandardCharsets.UTF_8)) {
            var legacy=new TomlParser().parse(reader); var selected=Config.inMemory();
            for(var key:keys) { Object value=legacy.get(key); if(value!=null)selected.set(key,value); }
            if(!selected.isEmpty()) AtomicFiles.write(target,new TomlWriter().writeToString(selected).getBytes(StandardCharsets.UTF_8));
        } catch(Exception error) {warning.accept("无法导入生物指南针配置 "+old+": "+error.getMessage());}
    }
}
