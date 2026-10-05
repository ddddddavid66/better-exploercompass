package com.chaosthedude.explorerscompass.unified.mixin;

import com.chaosthedude.explorerscompass.unified.search.PredictionTemplates;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 只作用于显式快照预测；服务器生成与原版其他预测入口沿用自身模板管理器。 */
@Mixin(StructureTemplateManager.class)
public abstract class BackgroundTemplateGuardMixin {
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void unified$readSnapshot(ResourceLocation key, CallbackInfoReturnable<Optional<StructureTemplate>> callback) {
        if (PredictionTemplates.active()) callback.setReturnValue(Optional.of(PredictionTemplates.require(key)));
    }
    @Inject(method = "getOrCreate", at = @At("HEAD"), cancellable = true)
    private void unified$neverCreateTemplate(ResourceLocation key, CallbackInfoReturnable<StructureTemplate> callback) {
        if (PredictionTemplates.active()) callback.setReturnValue(PredictionTemplates.require(key));
    }
}
