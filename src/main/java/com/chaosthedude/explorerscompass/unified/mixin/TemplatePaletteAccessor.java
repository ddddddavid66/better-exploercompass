package com.chaosthedude.explorerscompass.unified.mixin;

import java.util.List;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StructureTemplate.class)
public interface TemplatePaletteAccessor {
    @Accessor("palettes") List<StructureTemplate.Palette> unified$palettes();
}
