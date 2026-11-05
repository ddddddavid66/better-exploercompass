package com.david.compass.runtime.game.mixin;

import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets="net.minecraft.world.level.levelgen.DensityFunctions$Ap2")
public interface DensityAddAccessor {
    @Accessor("argument1") DensityFunction unified$first();
    @Accessor("argument2") DensityFunction unified$second();
}
