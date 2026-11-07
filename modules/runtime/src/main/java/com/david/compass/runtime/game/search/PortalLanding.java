package com.david.compass.runtime.game.search;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.phys.Vec3;

/** 只读取门房和门框外侧用到的实际区块。等待不会消耗探测游标。 */
final class PortalLanding {
    record Progress(Vec3 point, ChunkPos waiting, boolean done) {}
    private final PortalHint hint;
    private final List<ChunkPos> chunks;
    private int frameIndex, chunkIndex, lx, ly, lz, outsideIndex;
    private BlockPos frame;

    PortalLanding(PortalHint hint){
        this.hint=hint;
        var box=hint.room();
        chunks=new ArrayList<>();
        for(int z=box.minZ()>>4;z<=box.maxZ()>>4;z++)
            for(int x=box.minX()>>4;x<=box.maxX()>>4;x++)chunks.add(new ChunkPos(x,z));
    }
    Progress step(ServerPlayer player, ServerLevel level, boolean relaxed, long deadline){
        while(System.nanoTime()<deadline){
            if(frame==null){
                if(!hint.frames().isEmpty()){
                    if(frameIndex>=hint.frames().size())return new Progress(null,null,true);
                    var next=hint.frames().get(frameIndex);
                    var chunk=new ChunkPos(next);
                    if(level.getChunkSource().getChunkNow(chunk.x,chunk.z)==null)return new Progress(null,chunk,false);
                    frameIndex++;frame=next;
                } else {
                    var box=hint.room();
                    if(chunkIndex>=chunks.size())return new Progress(null,null,true);
                    var chunk=chunks.get(chunkIndex);
                    var loaded=level.getChunkSource().getChunkNow(chunk.x,chunk.z);
                    if(loaded==null)return new Progress(null,chunk,false);
                    int x=Math.max(box.minX(),chunk.getMinBlockX())+lx;
                    int y=box.minY()+ly;
                    int z=Math.max(box.minZ(),chunk.getMinBlockZ())+lz;
                    var point=new BlockPos(x,y,z);
                    if(y>=level.getMinBuildHeight() && y<level.getMaxBuildHeight()
                            && loaded.getSection(level.getSectionIndex(y)).maybeHas(s->s.is(Blocks.END_PORTAL_FRAME))
                            && loaded.getBlockState(point).is(Blocks.END_PORTAL_FRAME))frame=point;
                    if(x>=Math.min(box.maxX(),chunk.getMaxBlockX())){
                        lx=0;
                        if(z>=Math.min(box.maxZ(),chunk.getMaxBlockZ())){
                            lz=0;
                            if(y==box.maxY()){ly=0;chunkIndex++;}else ly++;
                        } else lz++;
                    } else lx++;
                }
                outsideIndex=0;
                if(frame==null)continue;
            }
            var owner=new ChunkPos(frame);
            var loaded=level.getChunkSource().getChunkNow(owner.x,owner.z);
            if(loaded==null)return new Progress(null,owner,false);
            var state=loaded.getBlockState(frame);
            if(!state.is(Blocks.END_PORTAL_FRAME) || outsideIndex>=8){frame=null;continue;}
            Direction outward=state.getValue(EndPortalFrameBlock.FACING).getOpposite();
            var near=frame.relative(outward,1+outsideIndex/4).above(switch(outsideIndex%4){
                case 1 -> 1;case 2 -> -1;case 3 -> 2;default -> 0;
            });
            var waiting=LandingChecks.missing(player,level,near,false);
            if(waiting!=null)return new Progress(null,waiting,false);
            outsideIndex++;
            Vec3 landing=LandingChecks.find(player,level,null,near,relaxed,false);
            // 不把外侧相邻列的深坑误算成门旁位置。
            if(landing!=null && Math.abs(landing.y-frame.getY())<=2.0)return new Progress(landing,null,false);
        }
        return new Progress(null,null,false);
    }
    boolean framePresent(ServerLevel level){
        if(frame==null)return false;
        var c=level.getChunkSource().getChunkNow(frame.getX()>>4,frame.getZ()>>4);
        return c!=null && c.getBlockState(frame).is(Blocks.END_PORTAL_FRAME);
    }
}
