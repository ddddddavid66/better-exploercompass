package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.unified.data.FailureReportText;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** 只在主线程读取运行时规则，客户端收到的是有界诊断快照。 */
final class SearchFailureDetails {
    private SearchFailureDetails() {}
    static CompoundTag snapshot(ServerLevel level,SearchJob job,List<ResourceLocation> keys,String exceptions) {
        var tag=new CompoundTag();
        tag.putLong("samples",job.samples());tag.putLong("candidates",job.candidates());tag.putInt("checks",job.confirmations);tag.putInt("chunkRequests",job.chunkLoads);
        tag.putLong("mainNanos",job.mainNanos);tag.putLong("workerNanos",job.workerNanos());
        tag.putLong("preparationNanos",job.preparationNanos());tag.putLong("confirmationNanos",job.confirmationNanos());tag.putLong("waitingNanos",job.waitingNanos());
        tag.putInt("radius",job.radius);tag.putLong("sampleLimit",job.sampleLimit());
        tag.putString("generator",level.getChunkSource().getGenerator().getClass().getName());
        tag.putString("biomeSource",level.getChunkSource().getGenerator().getBiomeSource().getClass().getName());
        tag.putString("diagnostics",FailureReportText.clean(job.diagnostics()+"; source_audit="+BiomeSourceSafety.prepare(level),6000));
        tag.putString("exceptions",FailureReportText.clean(exceptions,6000));
        var rules=new StringBuilder();
        var registry=level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for(var key:keys.stream().limit(64).toList())registry.getHolder(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE,key))
            .ifPresent(holder->{
                rules.append(key).append(" : ").append(holder.value().getClass().getName()).append('\n');
                level.getChunkSource().getGeneratorState().getPlacementsForStructure(holder).forEach(p->rules.append("  ").append(p.getClass().getName()).append('\n'));
            });
        tag.putString("rules",FailureReportText.clean(rules.toString(),6000));
        tag.putString("minecraft",net.minecraft.SharedConstants.getCurrentVersion().getName());
        tag.putString("forge",net.minecraftforge.versions.forge.ForgeVersion.getVersion());
        tag.putString("mod",net.minecraftforge.fml.ModList.get().getModContainerById("explorerscompass").orElseThrow().getModInfo().getVersion().toString());
        return tag;
    }
}
