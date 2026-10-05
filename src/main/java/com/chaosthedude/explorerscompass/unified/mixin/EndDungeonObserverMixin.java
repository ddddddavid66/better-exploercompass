package com.chaosthedude.explorerscompass.unified.mixin;

import com.chaosthedude.explorerscompass.unified.search.EndDungeonAdapter;
import com.chaosthedude.explorerscompass.unified.search.EndDungeonIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 旁观自然模板放置：原方法只调用一次，不增加随机数消费或强制生成。 */
@Pseudo
@Mixin(targets="net.mcreator.stalwartdungeons.world.features.EndDungeonFeature",remap=false)
public abstract class EndDungeonObserverMixin {
    @Redirect(method="m_142674_",remap=false,require=0,at=@At(value="INVOKE",remap=true,
            target="Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;placeInWorld(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;Lnet/minecraft/util/RandomSource;I)Z"))
    private boolean unified$observe(StructureTemplate template,ServerLevelAccessor level,BlockPos origin,BlockPos pivot,
                                   StructurePlaceSettings settings,RandomSource random,int flags) {
        boolean placed=template.placeInWorld(level,origin,pivot,settings,random,flags);
        if(placed && EndDungeonAdapter.supported() && template.getSize().equals(new BlockPos(3,5,3)))
            EndDungeonIndex.observe(level.getLevel(),origin.offset(StructureTemplate.calculateRelativePosition(settings,new BlockPos(1,2,1))));
        return placed;
    }
}
