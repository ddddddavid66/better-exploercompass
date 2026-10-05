package com.chaosthedude.explorerscompass.unified.mixin;

import com.chaosthedude.explorerscompass.unified.search.LeviathanPrecheck;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 可选模组桥；未安装 Dungeons Plus 或未启用预测作用域时完全不接管。 */
@Pseudo
@Mixin(targets = "com.legacy.dungeons_plus.structures.leviathan.LeviathanStructure$Capability", remap = false)
public abstract class LeviathanPrecheckMixin {
    @Inject(method = "canPlace(Lnet/minecraft/world/level/levelgen/structure/Structure$GenerationContext;Lnet/minecraft/core/BlockPos;Lcom/legacy/structure_gel/api/structure/ExtendedJigsawStructure$PlaceContext;)Z",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void unified$deferTerrain(Structure.GenerationContext context, BlockPos position,
                                     @Coerce Object placement, CallbackInfoReturnable<Boolean> callback) {
        if (LeviathanPrecheck.deferring(this) && LeviathanPrecheck.defer(this,
                () -> LeviathanPrecheck.original(this, context, position, placement))) callback.setReturnValue(true);
    }
}
