package com.david.compass.runtime.game.search;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** 搜索坐标和实际门房是两个独立的目标。 */
public record PortalHint(BoundingBox room, List<BlockPos> frames) {
    public PortalHint {
        frames=List.copyOf(frames);
    }
}
