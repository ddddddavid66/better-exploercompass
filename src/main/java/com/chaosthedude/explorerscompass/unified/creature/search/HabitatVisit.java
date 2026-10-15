package com.chaosthedude.explorerscompass.unified.creature.search;

import com.chaosthedude.explorerscompass.unified.search.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/** 生境只是等待自然刷新的地点，绝不作为实体结果或地图蓝点发送。 */
final class HabitatVisit {
    private final ServerPlayer player;
    private final ItemStack stack;
    private final UUID request;
    private final EntityType<?> type;
    private final Set<ResourceLocation> biomes;
    final ChunkPos center;
    private final BooleanSupplier valid;
    private final java.util.function.Predicate<MobPoint> accepts;
    private final RandomSource diagnosticRandom=RandomSource.create(0);
    private BlockPos spawn,observer;
    private int cell,preferredY;
    private boolean searchingObserver;
    private long watchingSince,lastPoll,cappedSince;
    int population,capacity;
    String state="checking_habitat";
    boolean ready,empty,waiting,confirmation;

    HabitatVisit(ServerPlayer player,ItemStack stack,UUID request,ResourceLocation type,
                 Set<ResourceLocation> biomes,ChunkPos center,BooleanSupplier valid,java.util.function.Predicate<MobPoint> accepts){
        this.player=player;this.stack=stack;this.request=request;
        this.type=ForgeRegistries.ENTITY_TYPES.getValue(type);this.biomes=Set.copyOf(biomes);
        this.center=center;this.valid=valid;this.accepts=accepts;
        var level=player.serverLevel();preferredY=level.getHeight(SpawnPlacements.getHeightmapType(this.type),center.getMiddleBlockX(),center.getMiddleBlockZ());
    }
    boolean watching(){return watchingSince!=0;}
    long waitedMillis(){return watchingSince==0?0:(System.nanoTime()-watchingSince)/1_000_000;}
    /** 只读取外层已经准备好的 3×3 区块；扫描与落点检查均让出共享主线程预算。 */
    boolean prepare(long deadline){
        if(ready || empty || waiting || watching())return false;
        var level=player.serverLevel();int height=level.getHeight(),total=12*12*height;
        while(cell<total && System.nanoTime()<deadline){
            int at=cell++,column=at%144,n=at/144;
            // 先看地表附近，随后覆盖全部有效高度，避免无约束飞行生物把观察者带到深层洞穴。
            int y=preferredY+((n&1)==0?n/2:-(n+1)/2);
            int minimum=level.getMinBuildHeight();y=minimum+Math.floorMod(y-minimum,height);
            var pos=new BlockPos(center.getMinBlockX()-14+(column%12)*4,y,center.getMinBlockZ()-14+(column/12)*4);
            if(y<=level.getMinBuildHeight() || y>=level.getMaxBuildHeight()-2 || !level.getWorldBorder().isWithinBounds(pos))continue;
            if(!CreatureIndex.ready(level,pos))continue;
            if(!searchingObserver){
                if(!eligible(pos))continue;
                spawn=pos;searchingObserver=true;cell=0;
            }else{
                double distance=pos.distSqr(spawn);
                // 正常刷怪排除玩家附近24格；站在可加载生境的距离，不能站到刷怪点上。
                if(!observerDistance(distance) || !TeleportService.safe(player,level,stack,pos))continue;
                observer=pos;ready=true;state="habitat_ready";return true;
            }
        }
        if(cell>=total){empty=true;state="habitat_unavailable";}
        return true;
    }
    static boolean observerDistance(double squared){return squared>=28*28 && squared<=112*112;}
    private boolean eligible(BlockPos pos){
        var level=player.serverLevel();
        var biome=level.getBiome(pos);
        if(!biome.unwrapKey().map(k->biomes.contains(k.location())).orElse(false))return false;
        if(biome.value().getMobSettings().getMobs(type.getCategory()).unwrap().stream().noneMatch(s->s.type==type && s.getWeight().asInt()>0 && s.maxCount>0))return false;
        if(!NaturalSpawner.isSpawnPositionOk(SpawnPlacements.getPlacementType(type),level,pos,type))return false;
        if(!level.noCollision(type.getDimensions().makeBoundingBox(pos.getX()+.5,pos.getY(),pos.getZ()+.5)))return false;
        // 调用登记的放置谓词做选址，不创建实体、不消费世界随机数、不调用任何出生方法。
        return SpawnPlacements.checkSpawnRules(type,level,MobSpawnType.NATURAL,pos,diagnosticRandom);
    }
    boolean visit(boolean fallback){
        if(!ready || waiting || watching() || !valid.getAsBoolean())return false;
        waiting=true;state="habitat_preparing";
        boolean accepted=TeleportService.requestLocator(player,stack,request,new LocatorTarget(){
            public BlockPos position(){return observer;}
            public boolean valid(){return HabitatVisit.this.valid.getAsBoolean();}
            public boolean accepts(Vec3 point){return observerDistance(point.distanceToSqr(Vec3.atCenterOf(spawn)));}
            public void state(String phase,boolean confirm){
                confirmation=confirm;state=watching()?"waiting_natural_spawn":"habitat_"+phase;
                waiting=!watching() && !confirm && (phase.equals("waiting_chunks") || phase.equals("waiting_slots") || phase.equals("checking") || phase.equals("checking_surface"));
            }
            public void moved(){watchingSince=System.nanoTime();lastPoll=0;waiting=false;confirmation=false;state="waiting_natural_spawn";}
        },fallback);
        if(!accepted){waiting=false;state="habitat_visit_rejected";}
        return accepted;
    }
    List<MobPoint> observe(long deadline){
        if(!watching() || System.nanoTime()-lastPoll<200_000_000L)return List.of();lastPoll=System.nanoTime();
        var level=player.serverLevel();var result=new ArrayList<MobPoint>();
        // 活体索引每200ms快照；暂未就绪的实体保留到下一次，不能把预测点伪装成出生。
        for(var entity:CreatureIndex.entities(level,ForgeRegistries.ENTITY_TYPES.getKey(type))){
            if(System.nanoTime()>=deadline)break;
            var point=CreatureIndex.point(level,entity);
            if(point!=null && entity.distanceToSqr(player)<128*128 && accepts.test(point))result.add(point);
        }
        var spawning=level.getChunkSource().getLastSpawnState();
        if(spawning!=null){
            population=spawning.getMobCategoryCounts().getInt(type.getCategory());
            capacity=(int)((long)type.getCategory().getMaxInstancesPerChunk()*spawning.getSpawnableChunkCount()/289);
            if(capacity>0 && population>=capacity){if(cappedSince==0)cappedSince=System.nanoTime();}
            else cappedSince=0;
        }
        // 真实分类额度持续已满时不空等一分钟；换生境仍需用户重新确认，不能清除或强刷生物。
        if(result.isEmpty() && cappedSince!=0 && System.nanoTime()-cappedSince>5_000_000_000L){
            state="natural_spawn_capped";watchingSince=0;ready=false;empty=true;
        }
        if(result.isEmpty() && waitedMillis()>60_000){state="natural_spawn_not_found";watchingSince=0;ready=false;empty=true;}
        return List.copyOf(result);
    }
}
