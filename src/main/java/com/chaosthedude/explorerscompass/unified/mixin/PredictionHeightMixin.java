package com.chaosthedude.explorerscompass.unified.mixin;

import com.chaosthedude.explorerscompass.unified.search.PredictionHeights;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 不影响真实世界生成；生成入口的 getFirstOccupiedHeight 仍沿原版减一规则。 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class PredictionHeightMixin {
    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void unified$cachedHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor bounds,
                                     RandomState noise, CallbackInfoReturnable<Integer> callback) {
        Integer cached = PredictionHeights.cached(this, x, z, type, bounds, noise);
        if (cached != null) callback.setReturnValue(cached);
    }
    @Inject(method = "getBaseHeight", at = @At("RETURN"))
    private void unified$rememberHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor bounds,
                                       RandomState noise, CallbackInfoReturnable<Integer> callback) {
        PredictionHeights.remember(this, x, z, type, bounds, noise, callback.getReturnValueI());
    }
}
