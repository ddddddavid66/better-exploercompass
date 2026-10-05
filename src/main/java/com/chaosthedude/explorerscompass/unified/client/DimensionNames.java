package com.chaosthedude.explorerscompass.unified.client;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class DimensionNames {
    private static final java.util.Set<String> ALIASES=java.util.Set.of(
        "binah:endlessdesert","callfromthedepth_:depth","distant_worlds:garhenna",
        "faded_conquest_2:the_abyss","faded_conquest_2:the_mirror_realm","graveyard:past",
        "infinite_abyss:first_layer","infinite_abyss:second_layer","infinite_abyss:fourth_layer",
        "infinite_abyss:fifth_layer","infinite_abyss:sixth_layer","infinite_abyss:seventh_layer");
    private DimensionNames() {}
    public static String name(ResourceLocation key) {
        if (key == null || key.getPath().isBlank()) return Component.translatable("filters.dimension_unknown").getString();
        String translation = Util.makeDescriptionId("dimension", key);
        if (I18n.exists(translation)) {
            String localized = I18n.get(translation);
            boolean chinese = net.minecraft.client.Minecraft.getInstance().options.languageCode.startsWith("zh");
            if (com.chaosthedude.explorerscompass.unified.data.LocalizedNames.usable(localized, chinese)) return localized;
        }
        if(ALIASES.contains(key.toString())) return Component.translatable("filters.dimension_alias."+key.toString().replace(':','.')).getString();
        String builtin = switch (key.toString()) {
            case "minecraft:overworld" -> "filters.overworld";
            case "minecraft:the_nether" -> "filters.nether";
            case "minecraft:the_end" -> "filters.end";
            default -> "";
        };
        return builtin.isEmpty() ? Component.translatable("filters.dimension_untranslated", key.toString()).getString()
                : Component.translatable(builtin).getString();
    }
    public static List<ResourceLocation> sorted(Collection<ResourceLocation> dimensions) {
        return dimensions.stream().sorted(Comparator.<ResourceLocation, Boolean>comparing(key -> !key.equals(ClientSession.currentDimension()))
                .thenComparing(DimensionNames::name).thenComparing(ResourceLocation::toString)).toList();
    }
}
