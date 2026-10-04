package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.mojang.serialization.JsonOps;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/** 可选 API 适配。只调用官方候选算法，不复制 Structure Gel 的随机/频率逻辑。 */
final class GelGridPlacement {
    static final String TYPE = "com.legacy.structure_gel.api.structure.GridStructurePlacement";
    private static final ClassValue<Method> FEATURE_METHOD = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> type) {
            try { return type.getMethod("getFeatureChunk", ChunkGeneratorStructureState.class, int.class, int.class); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException("Structure Gel 候选接口不兼容", error); }
        }
    };
    private final StructurePlacement placement;
    private final Method feature;
    final int spacing, offset;
    final boolean exclusion;
    final boolean backgroundAllowed;
    private GelGridPlacement(StructurePlacement placement, RegistryAccess registries) {
        this.placement = placement;
        feature = FEATURE_METHOD.get(placement.getClass());
        var json = StructurePlacement.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), placement)
                .getOrThrow(false, message -> { throw new IllegalStateException(message); }).getAsJsonObject();
        spacing = json.get("spacing").getAsInt();
        if (spacing <= 0) throw new IllegalArgumentException("Structure Gel spacing 必须大于零");
        offset = Math.min(spacing, Math.max(0, json.get("offset").getAsInt()));
        exclusion = json.has("exclusion_zone");
        backgroundAllowed = net.minecraftforge.fml.ModList.get().getModContainerById("structure_gel")
                .map(container -> container.getModInfo().getVersion().toString().equals("2.16.2")).orElse(false);
    }
    static GelGridPlacement of(StructurePlacement placement, RegistryAccess registries) {
        if (!placement.getClass().getName().equals(TYPE)) return null;
        try { return new GelGridPlacement(placement, registries); }
        catch (RuntimeException error) {
            ExplorersCompass.LOGGER.warn("Structure Gel 网格适配不可用，使用兼容搜索: {}", error.toString());
            return null;
        }
    }
    @SuppressWarnings("unchecked")
    Optional<ChunkPos> feature(ChunkGeneratorStructureState state, int regionX, int regionZ) {
        try { return (Optional<ChunkPos>)feature.invoke(placement, state, regionX * spacing, regionZ * spacing); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Structure Gel 网格候选计算失败", error); }
    }
}
