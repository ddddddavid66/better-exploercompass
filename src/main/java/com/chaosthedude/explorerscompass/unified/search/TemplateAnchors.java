package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.mixin.TemplatePaletteAccessor;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** 只冻结拼接块。palette 仅作为随机选择的身份，不在后台读取其可变索引。 */
final class TemplateAnchors extends TemplateGeometry {
    private final List<Palette> palettes;
    private final Map<Palette,List<StructureBlockInfo>> anchors=new IdentityHashMap<>();
    TemplateAnchors(StructureTemplate source) {
        super(source.getSize());
        palettes=List.copyOf(((TemplatePaletteAccessor)source).unified$palettes());
        for(Palette palette:palettes) anchors.put(palette,palette.blocks(Blocks.JIGSAW).stream()
                .map(info->new StructureBlockInfo(info.pos().immutable(),info.state(),info.nbt()==null?null:info.nbt().copy())).toList());
    }
    @Override public List<StructureBlockInfo> filterBlocks(BlockPos pos,StructurePlaceSettings settings,Block block) {
        return filterBlocks(pos,settings,block,true);
    }
    @Override public ObjectArrayList<StructureBlockInfo> filterBlocks(BlockPos pos,StructurePlaceSettings settings,Block block,boolean transformed) {
        if(block!=Blocks.JIGSAW)throw new IllegalStateException("未审核的后台模板方块查询: "+block);
        var result=new ObjectArrayList<StructureBlockInfo>();
        if(palettes.isEmpty())return result;
        var bounds=settings.getBoundingBox();
        for(var info:anchors.get(settings.getRandomPalette(palettes,pos))) {
            BlockPos point=transformed?calculateRelativePosition(settings,info.pos()).offset(pos):info.pos();
            if(bounds==null || bounds.isInside(point))result.add(new StructureBlockInfo(point,
                    info.state().rotate(settings.getRotation()),info.nbt()==null?null:info.nbt().copy()));
        }
        return result;
    }
}
