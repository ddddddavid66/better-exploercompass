package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.mixin.*;
import com.mojang.datafixers.util.Either;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** 在世界原有地形I/O邮箱读取有限窗口的区域头，仅用于优先级，不判定实体存在。 */
final class GeneratedChunkHints {
    static CompletableFuture<Set<Long>> read(ServerLevel level,int cx,int cz){
        var worker=((ChunkStorageAccessor)level.getChunkSource().chunkMap).unified$terrainWorker();
        var io=(EntityIoWorkerAccessor)worker;var storage=(EntityRegionStorageAccessor)(Object)io.unified$storage();
        return io.unified$submit(()->{
            try{
                var result=new HashSet<Long>();
                for(int x=cx-4;x<=cx+4;x++)for(int z=cz-4;z<=cz+4;z++){
                    var chunk=new ChunkPos(x,z);
                    if(io.unified$pendingWrites().containsKey(chunk)){result.add(chunk.toLong());continue;}
                    var file=storage.unified$folder().resolve("r."+(x>>5)+"."+(z>>5)+".mca");
                    // 不另开活跃区域文件，不调用可能创建文件的入口处理不存在区域。
                    if(Files.isRegularFile(file) && storage.unified$getRegionFile(chunk).hasChunk(chunk))result.add(chunk.toLong());
                }
                return Either.<Set<Long>,Exception>left(Set.copyOf(result));
            }catch(Exception error){return Either.<Set<Long>,Exception>right(error);}
        }).exceptionally(error->{com.chaosthedude.explorerscompass.ExplorersCompass.LOGGER.warn("有限地形占用提示不可用，继续按群系选区",error);return Set.of();});
    }
    private GeneratedChunkHints(){}
}
