package com.chaosthedude.explorerscompass.unified.search;

import net.minecraftforge.common.ForgeConfigSpec;

public final class SearchConfig {
    public static ForgeConfigSpec.BooleanValue ASYNC_BIOMES, ASYNC_STRUCTURES;
    public static ForgeConfigSpec.IntValue THREADS, TICK_MILLIS, RADIUS, STRUCTURE_SAMPLES, BIOME_SAMPLES, SPACING, VERTICAL, DEPTH_INTERVAL;
    public static void define(ForgeConfigSpec.Builder builder) {
        builder.push("Search");
        ASYNC_BIOMES = builder.comment("群系噪声在后台并行采样；不兼容的群系来源可关闭。").define("asyncBiomeSearch", true);
        ASYNC_STRUCTURES = builder.comment("后台预测结构，最终在服务端核对区块。关闭后逐候选查询区块。").define("asyncStructureSearch", true);
        THREADS = builder.comment("0 自动使用 1～4 个线程。修改后重新进入世界。").defineInRange("searchThreads", 0, 0, 16);
        TICK_MILLIS = builder.comment("所有搜索共享的主线程调度预算；单次区块读取可能超时。").defineInRange("maxSearchTimePerTick", 2, 1, 50);
        builder.defineInRange("configVersion", 1, 1, 1);
        RADIUS = builder.comment("结构和群系搜索的最大水平半径；统一版搜索不读取旧 maxRadius。").defineInRange("searchRadius", 40_000, 0, 1_000_000);
        STRUCTURE_SAMPLES = builder.comment("每种结构放置规则的分片共用额度；附近找到后会提前结束。").defineInRange("maxStructureSamples", 25_000_000, 0, 100_000_000);
        BIOME_SAMPLES = builder.comment("所有群系高度及分片合计；默认网格覆盖原版维度四万格。").defineInRange("maxBiomeSamples", 8_000_000, 0, 100_000_000);
        SPACING = builder.defineInRange("biomeSampleSpacing", 32, 4, 512);
        VERTICAL = builder.comment("0 只采样起始高度。").defineInRange("biomeVerticalSampleSpacing", 64, 0, 512);
        DEPTH_INTERVAL = builder.defineInRange("biomeDepthSampleInterval", 4, 1, 64);
        builder.pop();
    }
    private SearchConfig() {}
    public static int threads() {
        int configured = THREADS.get();
        return configured == 0 ? Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)) : configured;
    }
}
