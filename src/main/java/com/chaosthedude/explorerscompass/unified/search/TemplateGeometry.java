package com.chaosthedude.explorerscompass.unified.search;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** 起始预测只需几何尺寸；不复制方块/NBT，不允许以空模板偷偷继续完整生成。 */
final class TemplateGeometry extends StructureTemplate {
    private final Vec3i dimensions;
    TemplateGeometry(Vec3i size) {
        if (size.getX() < 0 || size.getY() < 0 || size.getZ() < 0) throw new IllegalArgumentException("模板尺寸不能为负数");
        dimensions = new Vec3i(size.getX(), size.getY(), size.getZ());
    }
    @Override public Vec3i getSize() { return dimensions; }
    @Override public Vec3i getSize(Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
                ? new Vec3i(dimensions.getZ(), dimensions.getY(), dimensions.getX()) : dimensions;
    }
    @Override public BoundingBox getBoundingBox(StructurePlaceSettings settings, BlockPos position) {
        return getBoundingBox(position, settings.getRotation(), settings.getRotationPivot(), settings.getMirror());
    }
    @Override public BoundingBox getBoundingBox(BlockPos position, Rotation rotation, BlockPos pivot, Mirror mirror) {
        // 直接复用原版几何算法，保持旋转、镜像、负坐标及非零旋转中心的一致性。
        return getBoundingBox(position, rotation, pivot, mirror, dimensions);
    }
    @Override public List<StructureBlockInfo> filterBlocks(BlockPos position, StructurePlaceSettings settings, Block block) { throw unsupported(); }
    @Override public ObjectArrayList<StructureBlockInfo> filterBlocks(BlockPos position, StructurePlaceSettings settings, Block block, boolean transformed) { throw unsupported(); }
    @Override public boolean placeInWorld(ServerLevelAccessor world, BlockPos position, BlockPos pivot, StructurePlaceSettings settings, RandomSource random, int flags) { throw unsupported(); }
    @Override public void fillFromWorld(Level world, BlockPos position, Vec3i size, boolean entities, Block ignored) { throw unsupported(); }
    @Override public void load(HolderGetter<Block> registry, CompoundTag tag) { throw unsupported(); }
    @Override public CompoundTag save(CompoundTag tag) { throw unsupported(); }
    @Override public void setAuthor(String author) { throw unsupported(); }
    private static IllegalStateException unsupported() { return new IllegalStateException("后台模板仅含几何快照，方块或实体查询需兼容验证"); }
}
