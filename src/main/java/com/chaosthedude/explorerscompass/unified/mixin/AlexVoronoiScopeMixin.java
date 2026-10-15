package com.chaosthedude.explorerscompass.unified.mixin;

import com.chaosthedude.explorerscompass.unified.search.AlexBiomeScope;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets="com.github.alexmodguy.alexscaves.server.level.biome.ACBiomeRarity",remap=false)
public abstract class AlexVoronoiScopeMixin {
    @Dynamic
    @Redirect(method="getRareBiomeInfoForQuad",at=@At(value="FIELD",target="Lcom/github/alexmodguy/alexscaves/server/level/biome/ACBiomeRarity;VORONOI_GENERATOR:Lcom/github/alexmodguy/alexscaves/server/misc/VoronoiGenerator;"),remap=false,require=0)
    @Coerce private static Object unified$localVoronoi(){return AlexBiomeScope.scoped();}
}
