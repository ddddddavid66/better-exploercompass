package com.chaosthedude.explorerscompass.unified.search;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

public record SearchCandidate(BlockPos position, ResourceLocation key, Structure structure,
                              StructurePlacement placement, ChunkPos chunk, long distanceSquared) {}
