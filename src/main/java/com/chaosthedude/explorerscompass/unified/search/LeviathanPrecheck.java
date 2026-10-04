package com.chaosthedude.explorerscompass.unified.search;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.Structure;

/** 仅后台预测交换两个纯条件的顺序：先真实 XYZ 群系，再调用官方九列高度条件。 */
public final class LeviathanPrecheck {
    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();
    private static final ClassValue<Method> CAN_PLACE = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> type) {
            try {
                var placement = Class.forName("com.legacy.structure_gel.api.structure.ExtendedJigsawStructure$PlaceContext", false, type.getClassLoader());
                return type.getMethod("canPlace", Structure.GenerationContext.class, BlockPos.class, placement);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("利维坦地形验证接口不兼容", error); }
        }
    };
    private static final class Capture {
        final Object capability;
        BooleanSupplier terrain;
        Capture(Object capability) { this.capability = capability; }
    }
    private LeviathanPrecheck() {}

    public static <T> T predict(Object capability, Supplier<T> generationPoint) {
        // 即便其他调用误用此入口，真实世界生成和同步兼容验证仍按官方次序执行。
        if (!Thread.currentThread().getName().startsWith("compassfilters-search-")) return generationPoint.get();
        var previous = CURRENT.get();
        var capture = new Capture(capability); CURRENT.set(capture);
        try {
            return validate(generationPoint.get());
        } finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }

    /** 在生成起点进入缓存前完成官方地形校验，并保留当前高度缓存作用域。 */
    public static <T> T validate(T point) {
        var capture = CURRENT.get();
        if (capture == null || capture.terrain == null) return point;
        var terrain = capture.terrain; capture.terrain = null;
        // 重放官方方法时关闭全部捕获作用域；外层兜底不会重复执行已消费的校验。
        CURRENT.remove();
        try { return point != null && !terrain.getAsBoolean() ? null : point; }
        finally { CURRENT.set(capture); }
    }

    public static boolean deferring(Object capability) {
        var capture = CURRENT.get();
        return capture != null && capture.capability == capability;
    }

    public static boolean defer(Object capability, BooleanSupplier terrain) {
        if (!deferring(capability)) return false;
        var capture = CURRENT.get();
        if (capture.terrain != null) throw new IllegalStateException("利维坦预测重复调用地形条件");
        capture.terrain = terrain;
        return true;
    }

    public static boolean original(Object capability, Structure.GenerationContext context, BlockPos position, Object placement) {
        try { return (boolean)CAN_PLACE.get(capability.getClass()).invoke(capability, context, position, placement); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw new IllegalStateException("利维坦官方地形验证失败", error.getCause());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("利维坦官方地形验证失败", error); }
    }
}
