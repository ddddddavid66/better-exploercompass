package com.chaosthedude.explorerscompass.unified.search;

import com.david.compass.runtime.game.search.BiomeSourceSafety;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.fml.ModList;

/** 主线程准备已审核的 Jigsaw / YUNG / Dungeons Plus 起始模板，第三方能力不因继承同一类而被放行。 */
final class StartPrediction {
    private static final String PREFIX = "com.legacy.dungeons_plus.structures.";
    private static final Map<String, String> SIMPLE = Map.of("tower", "tower.TowerStructure",
            "snowy_temple", "snowy_temple.SnowyTempleStructure", "soul_prison", "soul_prison.SoulPrisonStructure");
    private static final Set<String> JIGSAW = Set.of("leviathan", "end_ruins", "warped_garden",
            "reanimated_ruins_mossy", "reanimated_ruins_mesa", "reanimated_ruins_frozen");
    private static final String CATACLYSM_PREFIX="com.github.L_Ender.cataclysm.structures.";
    private static final Map<String,String> CATACLYSM_STUBS=Map.of(
            "sunken_city","Sunken_City_Structure","cursed_pyramid","Cursed_Pyramid_Structure",
            "burning_arena","Burning_Arena_Structure","ruined_citadel","RuinedCitadelStructure");
    private static final Set<String> CATACLYSM_JIGSAW=Set.of("acropolis","frosted_prison","soul_black_smith");

    static boolean cataclysmJigsaw(ResourceLocation key,Structure structure) {
        return key.getNamespace().equals("cataclysm") && CATACLYSM_JIGSAW.contains(key.getPath())
                && structure.getClass().getName().equals(CATACLYSM_PREFIX+"jisaw.CataclysmJigsawStructure")
                && version("cataclysm","3.08");
    }

    static boolean cataclysmPolicy(ResourceLocation key) { return key.getNamespace().equals("cataclysm"); }

    private static final Set<String> ELEMENTS = Set.of("minecraft:single_pool_element", "minecraft:legacy_single_pool_element",
            "structure_gel:gel_single_pool_element", "minecraft:empty_pool_element");
    private final ServerLevel level;
    private boolean named;
    private HeightBiomeFilter heightFilter;
    private final ResourceLocation key;
    private final List<ResourceLocation> pending = new ArrayList<>();
    private final Map<ResourceLocation, StructureTemplate> loading = new LinkedHashMap<>();
    private Map<ResourceLocation, StructureTemplate> snapshot = Map.of();
    private int index;
    private boolean allowed = true;
    private String reason="";
    String reason(){return reason;}
    private Object deferredLeviathan;
    private int anchorPadding;
    int anchorPadding(){return anchorPadding;}
    private StartPrediction(ServerLevel level, ResourceLocation key, Structure structure) {
        this.level = level; this.key = key;
        try { inspect(structure); }
        catch (Exception error) { reject(error.toString()); }
    }
    static StartPrediction of(ServerLevel level, ResourceLocation key, Structure structure) {
        return new StartPrediction(level, key, structure);
    }
    private void inspect(Structure structure) throws ReflectiveOperationException {
        var source = BiomeSourceSafety.prepare(level);
        if (!source.allowed()) { reject(source.reason()); return; }
        if(AetherStructures.base(structure)) {
            if(AetherStructures.moved(structure)) {pending.add(new ResourceLocation("aether:bronze_dungeon/boss_room"));anchorPadding=32;}
            return;
        }
        if(AetherStructures.platinum(structure))return;
        boolean ancient=AetherStructures.ancient(structure);
        if(ancient && AetherStructures.moved(structure)){pending.add(new ResourceLocation("ancient_aether:buried_jigsaw_spawn_radius"));anchorPadding=32;}
        // 原版废弃传送门也会读取模板；只在主线程准备13种模板的几何快照。
        if(structure.getClass()==net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure.class) {
            for(int i=1;i<=10;i++)pending.add(new ResourceLocation("minecraft:ruined_portal/portal_"+i));
            for(int i=1;i<=3;i++)pending.add(new ResourceLocation("minecraft:ruined_portal/giant_portal_"+i));
            return;
        }
        boolean vanilla=structure.getClass()==net.minecraft.world.level.levelgen.structure.structures.JigsawStructure.class;
        boolean yung=structure.getClass().getName().equals("com.yungnickyoung.minecraft.yungsapi.world.structure.YungJigsawStructure")
                && version("yungsapi","1.20-Forge-4.0.5")
                && (key.getNamespace().equals("betterjungletemples") && version("betterjungletemples","1.20-Forge-2.0.5")
                    || key.getNamespace().equals("betteroceanmonuments") && version("betteroceanmonuments","1.20-Forge-3.0.4"));
        boolean cataclysm=cataclysmJigsaw(key,structure);
        if(cataclysmPolicy(key) && !vanilla && !cataclysm) {
            String stub=CATACLYSM_STUBS.get(key.getPath());
            // 3.08 的这些生成入口只求起点，组装回调仍由 Minecraft 执行。
            if(version("cataclysm","3.08") && stub!=null
                    && structure.getClass().getName().equals(CATACLYSM_PREFIX+stub))return;
            reject("未经核对的灾变生成入口: "+structure.getClass().getName());return;
        }
        if(!vanilla && !yung && !ancient && !cataclysm) {
            if(!version("dungeons_plus","1.5.0") || !version("structure_gel","2.16.2")) {reject("未经核对的结构版本");return;}
            String simple=SIMPLE.get(key.getPath());
            if(key.getNamespace().equals("dungeons_plus") && simple!=null && structure.getClass().getName().equals(PREFIX+simple))return;
            if(!key.getNamespace().equals("dungeons_plus") || !JIGSAW.contains(key.getPath())
                    || !structure.getClass().getName().equals("com.legacy.structure_gel.api.structure.ExtendedJigsawStructure")) {reject("未经核对的结构类型");return;}
            String family=key.getPath().startsWith("reanimated_ruins_")?"reanimated_ruins":key.getPath();
            String title=switch(family){case "leviathan"->"Leviathan";case "end_ruins"->"EndRuins";case "warped_garden"->"WarpedGarden";default->"ReanimatedRuins";};
            var capability=(java.util.Optional<?>)structure.getClass().getMethod("getCapability").invoke(structure);
            if(capability.isEmpty() || !capability.get().getClass().getName().equals(PREFIX+family+"."+title+"Structure$Capability")) {reject("未经核对的拼接能力");return;}
            if(family.equals("leviathan"))deferredLeviathan=capability.get();
        }
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        var json = Structure.DIRECT_CODEC.encodeStart(ops, structure).getOrThrow(false, message -> {
            throw new IllegalStateException(message);
        }).getAsJsonObject();
        // 带命名起始拼接的分支可能更新模板内部方块索引缓存，留在主线程处理。
        named=json.has("start_jigsaw_name");
        if(named && !yung && !vanilla && !ancient) {reject("自定义命名起始拼接");return;}
        var height = json.get("start_height");
        if (height!=null && height.isJsonObject() && height.getAsJsonObject().has("type")
                && !Set.of("minecraft:constant", "minecraft:uniform").contains(height.getAsJsonObject().get("type").getAsString())) {
            reject("未经核对的高度提供器"); return;
        }
        if(vanilla && key.getNamespace().equals("aquamirae") && !named
                && json.has("project_start_to_heightmap") && height.isJsonObject()
                && height.getAsJsonObject().has("absolute")
                && level.getChunkSource().getGenerator().getClass()==net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.class)
            heightFilter=new HeightBiomeFilter(level,structure,height.getAsJsonObject().get("absolute").getAsInt());
        if(ancient && AetherStructures.moved(structure) && json.has("max_distance_from_center"))anchorPadding+=json.get("max_distance_from_center").getAsInt();
        var poolKey = new ResourceLocation(json.get("start_pool").getAsString());
        var pool = level.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL).get(poolKey);
        if (pool == null) { reject("起始模板池不存在"); return; }
        var poolJson = StructureTemplatePool.DIRECT_CODEC.encodeStart(ops, pool).getOrThrow(false, message -> {
            throw new IllegalStateException(message);
        }).getAsJsonObject();
        for (var weighted : poolJson.getAsJsonArray("elements")) {
            var element = weighted.getAsJsonObject().getAsJsonObject("element");
            String type = element.get("element_type").getAsString();
            if(element.has("conditions") && element.get("conditions").getAsJsonArray().size()>0) {reject("未经核对的起始拼接条件");return;}
            if (!ELEMENTS.contains(type) && !(yung && type.equals("yungsapi:yung_single_element"))) { reject("未知起始模板元素 " + type); return; }
            if (!type.equals("minecraft:empty_pool_element")) {
                if (!element.has("location") || !element.get("location").isJsonPrimitive()) { reject("内联或未知模板"); return; }
                var template = new ResourceLocation(element.get("location").getAsString());
                if (!pending.contains(template)) pending.add(template);
            }
        }
    }
    private static boolean version(String mod, String expected) {
        return ModList.get().getModContainerById(mod).map(container -> expected.equals(container.getModInfo().getVersion().toString())).orElse(false);
    }
    private void reject(String reason) {
        this.reason=reason;allowed = false;
        ExplorersCompass.LOGGER.info("{} 保留网格候选，转入兼容验证: {}", key, reason);
    }
    boolean preparing() { return allowed && index < pending.size(); }
    boolean allowed() { return allowed && !preparing(); }
    void prepareOne() {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("模板只能在主线程准备");
        if (!preparing()) return;
        var id = pending.get(index++);
        try {
            var source = level.getServer().getStructureManager().get(id);
            if (source.isEmpty()) { reject("模板不存在: " + id); return; }
            // 起始片段只查尺寸与包围盒，保存几何即可，不复制整份调色板和实体 NBT。
            loading.put(id, named?new TemplateAnchors(source.get()):new TemplateGeometry(source.get().getSize()));
            if(anchorPadding>0)anchorPadding=Math.addExact(anchorPadding,Math.max(source.get().getSize().getX(),source.get().getSize().getZ()));
            if (!preparing()) snapshot = Map.copyOf(loading);
        } catch (RuntimeException error) { reject("模板准备失败: " + error); }
    }
    <T> T predict(HeightBiomeFilter.Metrics metrics,Supplier<T> action) {
        return HeightBiomeFilter.measure(metrics,()->predict(action));
    }
    <T> T predict(Supplier<T> action) {
        if(heightFilter!=null)return PredictionTemplates.with(snapshot,()->heightFilter.with(action));
        return PredictionTemplates.with(snapshot, () -> deferredLeviathan == null ? action.get() : LeviathanPrecheck.predict(deferredLeviathan, action));
    }
}
