package com.david.compass.runtime.game.client;

import net.minecraft.Util;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;

/** 产品无关的名称解析，避免仅为翻译加载结构筛选界面。 */
public final class BiomeNames {
    private BiomeNames() {}
    public static String name(ResourceLocation id) {
        String key=Util.makeDescriptionId("biome",id);
        return I18n.exists(key)?I18n.get(key):id.toString();
    }
}
