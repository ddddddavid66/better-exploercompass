package com.chaosthedude.explorerscompass.unified.search;

import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** 预测作用域的模板快照。后台缺失模板直接降级，不查询磁盘、资源包或原管理器。 */
public final class PredictionTemplates {
    private static final ThreadLocal<Map<ResourceLocation, StructureTemplate>> CURRENT = new ThreadLocal<>();
    private PredictionTemplates() {}
    public static boolean active() { return CURRENT.get() != null; }
    public static StructureTemplate require(ResourceLocation key) {
        var current = CURRENT.get();
        var template = current == null ? null : current.get(key);
        if (template == null) throw new IllegalStateException("后台模板快照缺少 " + key);
        return template;
    }
    public static <T> T with(Map<ResourceLocation, StructureTemplate> templates, Supplier<T> action) {
        var previous = CURRENT.get();
        CURRENT.set(templates);
        try { return action.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
