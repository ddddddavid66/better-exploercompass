package com.david.compass.runtime.game.search;
import java.util.*;
import net.minecraft.world.level.ChunkPos;
/** 候选检查所需的完整水平足迹，不能逐个缺块请求后逐出仍在使用的依赖。 */
final class LandingFootprint {
    static List<ChunkPos> chunks(double minX,double maxX,double minZ,double maxZ,int margin){
        var result=new ArrayList<ChunkPos>(4);
        for(int x=((int)Math.floor(minX-margin))>>4;x<=((int)Math.floor(maxX+margin))>>4;x++)
            for(int z=((int)Math.floor(minZ-margin))>>4;z<=((int)Math.floor(maxZ+margin))>>4;z++)result.add(new ChunkPos(x,z));
        return List.copyOf(result);
    }
}
