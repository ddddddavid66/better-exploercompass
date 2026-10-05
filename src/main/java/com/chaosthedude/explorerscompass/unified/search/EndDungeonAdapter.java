package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.CompassUnified;
import com.chaosthedude.explorerscompass.unified.mixin.FeatureOrderAccessor;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/** 可选地物适配；模板只在主线程读一次，后台仅持有随机规则与几何快照。 */
public final class EndDungeonAdapter {
    public static final ResourceLocation KEY = new ResourceLocation("stalwart_dungeons:end_dungeon");
    static final ResourceLocation MARKER = new ResourceLocation("stalwart_dungeons:ender_globe_spawn");
    static final ResourceLocation ALTAR = new ResourceLocation("stalwart_dungeons:shelterer_altar");
    private static final Map<ServerLevel, Snapshot> CACHE = new IdentityHashMap<>();
    record Slot(int step, int index) {}
    record Sample(BlockPos offset, BlockState state) {}
    record Snapshot(List<Slot> slots, Set<ResourceLocation> biomes, List<Sample> cross,
                    List<Sample> globe, boolean predictive, String reason) {}
    private EndDungeonAdapter() {}
    public static boolean supported() {
        return ModList.get().getModContainerById("stalwart_dungeons")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("1.2.8")).orElse(false);
    }
    public static boolean special(ServerLevel level, ResourceLocation key) {
        return KEY.equals(key) && supported() && !level.registryAccess().registryOrThrow(Registries.STRUCTURE).containsKey(KEY)
                && level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).containsKey(KEY);
    }
    public static Set<ResourceLocation> biomes(ServerLevel level) { return snapshot(level).biomes(); }
    static Snapshot snapshot(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("地物目录只能在主线程读取");
        return CACHE.computeIfAbsent(level, EndDungeonAdapter::prepare);
    }
    private static Snapshot prepare(ServerLevel level) {
        var generator = level.getChunkSource().getGenerator();
        Set<ResourceLocation> biomes = new LinkedHashSet<>(); List<Slot> slots = new ArrayList<>();
        List<Sample> cross = List.of(), globe = List.of(); String reason = "";
        try {
            if (!level.dimension().equals(Level.END)) throw new IllegalStateException("末地地牢只在原版末地自然生成");
            var placed = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).get(KEY);
            if (placed == null) throw new IllegalStateException("未注册末地地牢地物");
            for (var holder : generator.getBiomeSource().possibleBiomes()) {
                var settings = generator.getBiomeGenerationSettings(holder);
                if (settings.features().stream().anyMatch(set -> set.stream().anyMatch(value -> value.value() == placed)))
                    holder.unwrapKey().ifPresent(key -> biomes.add(key.location()));
            }
            var manager = level.getServer().getStructureManager();
            StructureTemplate small = manager.get(new ResourceLocation("stalwart_dungeons:end_dungeon_spawn")).orElseThrow();
            StructureTemplate full = manager.get(new ResourceLocation("stalwart_dungeons:shell_globe")).orElseThrow();
            var settings = new StructurePlaceSettings();
            var marker = small.filterBlocks(BlockPos.ZERO, settings, block(MARKER));
            var altar = full.filterBlocks(BlockPos.ZERO, settings, block(ALTAR));
            if (!small.getSize().equals(new BlockPos(3,5,3)) || marker.size()!=1 || !marker.get(0).pos().equals(new BlockPos(1,2,1))
                    || !full.getSize().equals(new BlockPos(55,47,46)) || altar.size()!=1 || !altar.get(0).pos().equals(new BlockPos(27,27,22)))
                throw new IllegalStateException("地牢模板几何发生未经核对的修改");
            cross = small.filterBlocks(BlockPos.ZERO, settings, block(new ResourceLocation("stalwart_dungeons:purpur_bricks")))
                    .stream().map(info -> new Sample(info.pos().subtract(new BlockPos(1,2,1)), info.state())).toList();
            if (cross.size()!=12) throw new IllegalStateException("十字架模板被修改");
            var signature = new ArrayList<Sample>();
            signature.add(new Sample(new BlockPos(1,9,-1), altar.get(0).state()));
            for (String name : List.of("obsidian_striker", "chiseled_end_stone_bricks", "shruster")) {
                full.filterBlocks(BlockPos.ZERO, settings, block(new ResourceLocation("stalwart_dungeons:"+name))).stream()
                        .map(info -> new Sample(info.pos().subtract(new BlockPos(26,18,23)), info.state()))
                        .filter(sample -> Math.abs(sample.offset().getX())<=12 && Math.abs(sample.offset().getZ())<=12)
                        .sorted(Comparator.comparingDouble(sample -> sample.offset().distSqr(BlockPos.ZERO)))
                        .limit(3).forEach(signature::add);
            }
            if (signature.size()<7) throw new IllegalStateException("地牢核心标志不足");
            globe = List.copyOf(signature);
            // 未审核的预测规则仍保留模板证据，供历史索引重新核实。
            if (!placed.placement().isEmpty()) throw new IllegalStateException("未经核对的末地地牢放置修饰器");
            if (!placed.feature().value().feature().getClass().getName().equals("net.mcreator.stalwartdungeons.world.features.EndDungeonFeature"))
                throw new IllegalStateException("末地地牢生成入口被替换");
            if (generator.getClass() != NoiseBasedChunkGenerator.class) throw new IllegalStateException("未经核对的地物装饰生成器 " + generator.getClass().getName());
            var steps = ((FeatureOrderAccessor)generator).unified$featuresPerStep().get();
            for (int step = 0; step < steps.size(); step++) {
                int index = steps.get(step).features().indexOf(placed);
                if (index >= 0) slots.add(new Slot(step, index));
            }
        } catch (RuntimeException failure) {
            reason = Objects.toString(failure.getMessage(), failure.toString());
            CompassUnified.LOGGER.warn("末地地牢预测不可用，仅核对已收录位置：{}", reason);
        }
        return new Snapshot(List.copyOf(slots), Set.copyOf(biomes), cross, globe,
                reason.isEmpty() && !slots.isEmpty() && !biomes.isEmpty(), reason);
    }
    static Block block(ResourceLocation id) { return ForgeRegistries.BLOCKS.getValue(id); }
    /** 返回 0=无证据，1=十字架，2=已展开；不执行任何生成过程。 */
    static int evidence(Snapshot snapshot, BlockPos anchor, Function<BlockPos, BlockState> read) {
        if (!snapshot.cross().isEmpty() && read.apply(anchor).is(block(MARKER))) {
            for (Mirror mirror : new Mirror[]{Mirror.NONE,Mirror.LEFT_RIGHT}) for (Rotation rotation : Rotation.values()) {
                var settings = new StructurePlaceSettings().setMirror(mirror).setRotation(rotation);
                int matched=0;
                for (Sample sample : snapshot.cross()) {
                    BlockPos relative = StructureTemplate.calculateRelativePosition(settings, sample.offset());
                    if (read.apply(anchor.offset(relative)).equals(sample.state())) matched++;
                }
                if (matched>=8) return 1;
            }
        }
        if (!snapshot.globe().isEmpty() && read.apply(anchor.offset(1,9,-1)).is(block(ALTAR))) {
            long matched=snapshot.globe().stream().filter(sample -> read.apply(anchor.offset(sample.offset())).equals(sample.state())).count();
            if (matched>=Math.max(6,snapshot.globe().size()-2)) return 2;
        }
        return 0;
    }
    public static void clear() { CACHE.clear(); }
}
