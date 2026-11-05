package com.david.compass.runtime.game.mixin;

import com.david.compass.runtime.game.search.ColumnClimate;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 保留原 Sampler 的身份及其他模组附加字段，仅显式作用域内复用已证明独立于 Y 的分量。 */
@Mixin(Climate.Sampler.class)
public abstract class ColumnClimateMixin {
    @Inject(method="sample",at=@At("HEAD"),cancellable=true)
    private void unified$column(int x,int y,int z,CallbackInfoReturnable<Climate.TargetPoint> callback) {
        var point=ColumnClimate.sample((Climate.Sampler)(Object)this,x,y,z);
        if(point!=null)callback.setReturnValue(point);
    }
}
