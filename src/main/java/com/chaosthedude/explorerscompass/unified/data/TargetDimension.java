package com.chaosthedude.explorerscompass.unified.data;

import net.minecraft.resources.ResourceLocation;

public final class TargetDimension {
    private TargetDimension() {}
    public static ResourceLocation parse(String text) {
        if (text == null || text.isBlank()) return null;
        var key = ResourceLocation.tryParse(text);
        return key == null || key.getPath().isBlank() ? null : key;
    }
}
