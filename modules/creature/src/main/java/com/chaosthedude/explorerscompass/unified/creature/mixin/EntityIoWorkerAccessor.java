package com.chaosthedude.explorerscompass.unified.creature.mixin;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import com.mojang.datafixers.util.Either;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.*;

@Mixin(IOWorker.class)
public interface EntityIoWorkerAccessor {
    @Accessor("mailbox") net.minecraft.util.thread.ProcessorMailbox<net.minecraft.util.thread.StrictQueue.IntRunnable> unified$mailbox();
    @Accessor("storage") RegionFileStorage unified$storage();
    @Accessor("pendingWrites") Map<ChunkPos,?> unified$pendingWrites();
    @Invoker("submitTask") <T> CompletableFuture<T> unified$submit(Supplier<Either<T,Exception>> task);
}
