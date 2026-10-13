package com.chaosthedude.explorerscompass.unified.search;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** 可独立拆包的外部目标契约；坐标与有效性均由服务端提供。 */
public interface LocatorTarget {
    BlockPos position();
    boolean valid();
    boolean accepts(Vec3 landing);
    void state(String phase,boolean confirmation);
    void moved();
}
