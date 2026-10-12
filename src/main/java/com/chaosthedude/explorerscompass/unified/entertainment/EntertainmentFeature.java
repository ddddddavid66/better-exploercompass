package com.chaosthedude.explorerscompass.unified.entertainment;

import net.minecraft.resources.ResourceLocation;

/** 娱乐项目声明自己的启动维度，界面和服务端使用同一份定义。 */
public enum EntertainmentFeature {
    ADVENTURING_TIME("adventuring_time", "minecraft:overworld"),
    AETHER_BIOMES("aether_biomes", "aether:the_aether");

    private final String name;
    private final ResourceLocation dimension;
    EntertainmentFeature(String name, String dimension) {
        this.name = name;
        this.dimension = new ResourceLocation(dimension);
    }
    public String key() { return "entertainment." + name; }
    public ResourceLocation dimension() { return dimension; }
    public String unavailableKey() { return key() + "_dimension"; }
}
