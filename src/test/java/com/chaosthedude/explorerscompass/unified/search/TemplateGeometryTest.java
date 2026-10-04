package com.chaosthedude.explorerscompass.unified.search;

import java.util.List;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TemplateGeometryTest {
    @Test void geometryMatchesFullVanillaTemplateForRotationsMirrorsPivotsAndNegativePositions() {
        for (Vec3i size : List.of(new Vec3i(1,1,1),new Vec3i(17,9,43),new Vec3i(64,33,2))) {
            StructureTemplate reference = vanilla(size);
            var snapshot = new TemplateGeometry(reference.getSize());
            for (Rotation rotation : Rotation.values()) for (Mirror mirror : Mirror.values())
                for (BlockPos position : List.of(BlockPos.ZERO,new BlockPos(-129,-60,512)))
                    for (BlockPos pivot : List.of(BlockPos.ZERO,new BlockPos(5,11,-3))) {
                        var settings = new StructurePlaceSettings().setRotation(rotation).setMirror(mirror).setRotationPivot(pivot);
                        assertEquals(reference.getSize(rotation),snapshot.getSize(rotation));
                        assertArrayEquals(corners(reference.getBoundingBox(settings,position)),corners(snapshot.getBoundingBox(settings,position)));
                        assertEquals(reference.getZeroPositionWithTransform(position,mirror,rotation),snapshot.getZeroPositionWithTransform(position,mirror,rotation));
                    }
        }
    }
    @Test void snapshotsDoNotRetainMutableSizeOrExposeReusableBoundingBoxes() throws Exception {
        var mutableSize = new BlockPos.MutableBlockPos(17,9,43);
        var snapshot = new TemplateGeometry(mutableSize);
        mutableSize.set(1,2,3);
        assertEquals(new Vec3i(17,9,43),snapshot.getSize());
        int[] expected = corners(snapshot.getBoundingBox(new StructurePlaceSettings(),BlockPos.ZERO));
        var pool = Executors.newFixedThreadPool(4);
        try {
            var checks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i=0;i<32;i++) checks.add(pool.submit(() -> {
                var box = snapshot.getBoundingBox(new StructurePlaceSettings(),BlockPos.ZERO);
                assertArrayEquals(expected,corners(box));box.move(100,100,100);
                assertArrayEquals(expected,corners(snapshot.getBoundingBox(new StructurePlaceSettings(),BlockPos.ZERO)));
            }));
            for (var check:checks) check.get();
        } finally { pool.shutdownNow(); }
    }
    @Test void unexpectedBlockEntityAndMutationPathsFailInsteadOfSilentlyPredictingAnEmptyTemplate() {
        var snapshot = new TemplateGeometry(new Vec3i(17,9,43));
        assertThrows(IllegalStateException.class,() -> snapshot.filterBlocks(null,null,null));
        assertThrows(IllegalStateException.class,() -> snapshot.filterBlocks(null,null,null,false));
        assertThrows(IllegalStateException.class,() -> snapshot.placeInWorld(null,null,null,null,null,0));
        assertThrows(IllegalStateException.class,() -> snapshot.fillFromWorld(null,null,null,false,null));
        assertThrows(IllegalStateException.class,() -> snapshot.load(null,new CompoundTag()));
        assertThrows(IllegalStateException.class,() -> snapshot.save(new CompoundTag()));
        assertThrows(IllegalStateException.class,() -> snapshot.setAuthor("test"));
        assertThrows(IllegalArgumentException.class,() -> new TemplateGeometry(new Vec3i(-1,1,1)));
    }
    private static StructureTemplate vanilla(Vec3i size) {
        var tag = new CompoundTag();var dimensions = new ListTag();
        dimensions.add(IntTag.valueOf(size.getX()));dimensions.add(IntTag.valueOf(size.getY()));dimensions.add(IntTag.valueOf(size.getZ()));
        tag.put("size",dimensions);tag.put("palettes",new ListTag());
        // 空调色板无需注册表，用真实原版 load 设置尺寸，再对照官方实例方法。
        var template = new StructureTemplate();template.load(null,tag);return template;
    }
    private static int[] corners(BoundingBox box) { return new int[]{box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()}; }
}
