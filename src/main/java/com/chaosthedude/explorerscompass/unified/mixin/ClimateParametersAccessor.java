package com.chaosthedude.explorerscompass.unified.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultiNoiseBiomeSource.class)
public interface ClimateParametersAccessor {
    @Invoker("parameters") Climate.ParameterList<Holder<Biome>> unified$parameters();
}
