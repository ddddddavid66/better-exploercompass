package com.chaosthedude.explorerscompass.unified.search;

import com.david.compass.runtime.game.search.PortalHint;
import com.mojang.datafixers.util.Either;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

final class StrongholdPortal {
    private static final ResourceLocation YUNG_ROOM=new ResourceLocation("betterstrongholds","portal_rooms/portal_room");
    private StrongholdPortal(){}

    static boolean applies(ResourceLocation key){
        return switch(key.toString()){
            case "minecraft:stronghold","betterstrongholds:stronghold" -> true;
            default -> false;
        };
    }
    static PortalHint extract(ServerLevel level,ResourceLocation key,StructureStart start){
        if(!applies(key))return null;
        boolean vanilla=key.getNamespace().equals("minecraft");
        for(var piece:start.getPieces()){
            if(vanilla && piece.getType()==StructurePieceType.STRONGHOLD_PORTAL_ROOM)
                return new PortalHint(piece.getBoundingBox(),List.of());
            if(vanilla || !(piece instanceof PoolElementStructurePiece pool))continue;
            var element=pool.getElement();
            if(!element.getClass().getSimpleName().equals("YungJigsawSinglePoolElement"))continue;
            try {
                // 按模板 ID 精确匹配，排除名称含 portal 的装饰房间。
                var templateField=element.getClass().getField("template");
                if(!(templateField.get(element) instanceof Either<?,?> id) || !id.left().filter(YUNG_ROOM::equals).isPresent())continue;
                var template=level.getStructureManager().get(YUNG_ROOM);
                if(template.isEmpty())return null;
                var settings=new StructurePlaceSettings().setRotation(pool.getRotation());
                var frames=template.get().filterBlocks(pool.getPosition(),settings,Blocks.END_PORTAL_FRAME)
                        .stream().map(StructureTemplate.StructureBlockInfo::pos).map(BlockPos::immutable).distinct().toList();
                if(frames.size()!=12 || !frames.stream().allMatch(piece.getBoundingBox()::isInside))return null;
                return new PortalHint(piece.getBoundingBox(),frames);
            } catch(ReflectiveOperationException | RuntimeException badTemplate) {
                com.chaosthedude.explorerscompass.ExplorersCompass.LOGGER.warn("无法读取要塞传送门模板",badTemplate);
                return null;
            }
        }
        return null;
    }
    static void store(net.minecraft.nbt.CompoundTag tag,PortalHint hint){
        tag.remove("ECUnifiedPortalRoom");tag.remove("ECUnifiedPortalFrames");
        if(hint==null)return;
        var b=hint.room();
        tag.putIntArray("ECUnifiedPortalRoom",new int[]{b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()});
        tag.putLongArray("ECUnifiedPortalFrames",hint.frames().stream().map(BlockPos::asLong).mapToLong(Long::longValue).toArray());
    }
    static PortalHint read(net.minecraft.nbt.CompoundTag tag){
        int[] b=tag.getIntArray("ECUnifiedPortalRoom");
        long[] raw=tag.getLongArray("ECUnifiedPortalFrames");
        if(b.length!=6 || raw.length>12)return null;
        for(int i=0;i<3;i++)if((long)b[i+3]-b[i]<0 || (long)b[i+3]-b[i]>40)return null;
        var box=new BoundingBox(b[0],b[1],b[2],b[3],b[4],b[5]);
        var frames=java.util.Arrays.stream(raw).mapToObj(BlockPos::of).toList();
        if(!frames.stream().allMatch(box::isInside))return null;
        return new PortalHint(box,frames);
    }
}
