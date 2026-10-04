package com.chaosthedude.explorerscompass.unified.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 一次生成并共享，只在主线程逐个检查世界；相同距离采用固定顺序。 */
public final class TeleportOffsets {
    public record Offset(int x, int y, int z) { public int squared() { return x*x + y*y + z*z; } }
    public static final List<Offset> NEARBY = create();
    private TeleportOffsets() {}
    private static List<Offset> create() {
        List<Offset> offsets = new ArrayList<>();
        for (int x = -8; x <= 8; x++) for (int y = -16; y <= 16; y++) for (int z = -8; z <= 8; z++) offsets.add(new Offset(x,y,z));
        offsets.sort(Comparator.comparingInt(Offset::squared).thenComparingInt(o -> Math.abs(o.y()))
                .thenComparingInt(Offset::x).thenComparingInt(Offset::z).thenComparingInt(Offset::y));
        return List.copyOf(offsets);
    }
    public static boolean validHeight(int y, int minimum, int maximum, boolean ceiling, int logicalHeight) {
        return y > minimum && y + 1 < maximum && (!ceiling || y + 1 < minimum + logicalHeight);
    }
}
