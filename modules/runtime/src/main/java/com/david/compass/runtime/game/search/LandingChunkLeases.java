package com.david.compass.runtime.game.search;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
/** FULL 准备与结构核对共用同一分阶段代理。 */
final class LandingChunkLeases {
    private LandingChunkLeases(){}
    static void retain(ServerLevel level,ChunkPos pos){ChunkPreparationBroker.retain(level,pos,ChunkStatus.FULL);}
    static void release(ServerLevel level,ChunkPos pos){ChunkPreparationBroker.release(level,pos,ChunkStatus.FULL);}
}
