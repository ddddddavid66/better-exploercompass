package com.chaosthedude.explorerscompass.unified.search;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** 1.2.8 的纯随机前缀：只计算坐标，不调用 Feature.place 或模板放置。 */
public final class EndDungeonRandom {
    public record Hit(int x, int z, Mirror mirror, Rotation rotation) {}
    private static final ThreadLocal<WorldgenRandom> RANDOM = ThreadLocal.withInitial(
            () -> new WorldgenRandom(new XoroshiroRandomSource(0)));
    private EndDungeonRandom() {}
    public static Hit predict(long seed, int chunkX, int chunkZ, int step, int index) {
        WorldgenRandom random = RANDOM.get();
        long decoration = random.setDecorationSeed(seed, chunkX * 16, chunkZ * 16);
        random.setFeatureSeed(decoration, index, step);
        if (random.nextInt(1_000_000) + 1 > 1500) return null;
        // nextInt(1) 仍消费随机数，省略它会让全部后续坐标错误。
        random.nextInt(1);
        int x = chunkX * 16 + random.nextInt(16), z = chunkZ * 16 + random.nextInt(16);
        Mirror mirror = Mirror.values()[random.nextInt(2)];
        Rotation rotation = Rotation.values()[random.nextInt(3)];
        BlockPos offset = StructureTemplate.calculateRelativePosition(new StructurePlaceSettings()
                .setMirror(mirror).setRotation(rotation), new BlockPos(1, 2, 1));
        return new Hit(x + offset.getX(), z + offset.getZ(), mirror, rotation);
    }
}
