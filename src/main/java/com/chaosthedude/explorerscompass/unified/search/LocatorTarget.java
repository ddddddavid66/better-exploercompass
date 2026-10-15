package com.chaosthedude.explorerscompass.unified.search;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** 可独立拆包的外部目标契约；坐标与有效性均由服务端提供。 */
public interface LocatorTarget {
    /** 查询内切换实例时必须换目标，不能沿用上一只实体的坐标和确认。 */
    default Object identity(){return position();}
    default boolean creature(){return false;}
    default java.util.List<Vec3> relaxedCandidates(net.minecraft.server.level.ServerPlayer player){return java.util.List.of();}
    BlockPos position();
    boolean valid();
    boolean accepts(Vec3 landing);
    void state(String phase,boolean confirmation);
    void moved();
}
