package com.chaosthedude.explorerscompass.unified.search;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** 普通定位、娱乐与生物扫描锚点共用的冻结任务构造入口。 */
public final class BiomeQueryFactory {
    public static final class Plan {
        final ServerLevel level;final BlockPos origin;final ResourceLocation target;
        final BiomeSamplingProfile profile;
        private final GenerationStamp stamp;
        private Plan(ServerLevel level,BlockPos origin,ResourceLocation target,BiomeSamplingProfile profile) {
            this.level=level;this.origin=origin.immutable();this.target=target;this.profile=profile;
            stamp=GenerationStamp.capture(level);
        }
        public BlockPos origin(){return origin;}
        public ResourceLocation target(){return target;}
        public BiomeSamplingProfile profile(){return profile;}
        private void validate() {
            if(!level.getServer().isSameThread())throw new IllegalStateException("群系计划只能由服务端线程提交");
            if(!stamp.current(level))
                throw new IllegalStateException("群系生成上下文已失效");
        }
    }
    private BiomeQueryFactory() {}
    public static SearchJob habitats(ServerLevel level,BlockPos origin,java.util.Set<ResourceLocation> targets,
                                    java.util.Set<Long> excluded,int radius,long quota,boolean async,com.david.compass.runtime.SearchParameters settings) {
        var target=targets.stream().sorted().findFirst().orElseThrow();
        var plan=prepare(level,origin,target,settings);
        return new HabitatSearch(level,origin,targets,excluded,radius,quota,async,plan.profile);
    }
    public static Plan prepare(ServerLevel level,BlockPos origin,ResourceLocation target,com.david.compass.runtime.SearchParameters settings) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("群系计划只能在服务端线程准备");
        BiomeSourceSafety.prepare(level);
        int spacing=settings.horizontalSpacing(),interval=settings.depthInterval();
        BiomeSamplingProfile profile;
        if(level.dimension().location().toString().equals("aether:the_aether")) {
            var hint=AetherSamplingHints.prepare(level,target,origin.getY());
            profile=BiomeSamplingProfile.aether(level.getMinBuildHeight(),level.getMaxBuildHeight(),origin.getY(),spacing,interval,hint.height(),hint.evidence());
        } else profile=BiomeSamplingProfile.legacy(level.getMinBuildHeight(),level.getMaxBuildHeight(),origin.getY(),spacing,settings.verticalSpacing(),interval);
        return new Plan(level,origin,target,profile);
    }
    public static SearchJob create(Plan plan,List<BlockPos> excluded,int radius,long configuredQuota,boolean async,boolean exact,int innerRadius) {
        plan.validate();long quota=plan.profile.quota(configuredQuota,radius,innerRadius);
        if(AlexCavesAdapter.target(plan.target))return new AlexCavesSearch(plan.level,plan.origin,plan.target,excluded,radius,
                Math.max(0,configuredQuota),plan.profile.spacing(),async,AlexCavesAdapter.prepare(plan.level,plan.target));
        return new BiomeSearch(plan.level,plan.origin,plan.target,excluded,radius,Math.max(0,quota),async,exact,innerRadius,plan.profile);
    }
}
