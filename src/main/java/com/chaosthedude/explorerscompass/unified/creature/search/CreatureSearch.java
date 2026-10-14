package com.chaosthedude.explorerscompass.unified.creature.search;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.creature.CreatureItem;
import com.chaosthedude.explorerscompass.unified.creature.catalog.*;
import com.chaosthedude.explorerscompass.unified.creature.network.*;
import com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork;
import com.chaosthedude.explorerscompass.unified.search.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** 每玩家独立查询；世界、实体和票据仅在服务器线程访问。 */
public final class CreatureSearch {
    public static final int ROUND_CHUNKS=64,MAX_ROUNDS=32;
    private static final TicketType<UUID> TICKET=TicketType.create("unified_creature",Comparator.<UUID>naturalOrder());
    private static final Map<UUID,Query> tasks=new LinkedHashMap<>();
    private static int cursor;
    private static final class Query {
        final ServerPlayer player;final ServerLevel level;final ItemStack stack;final UUID token;
        final ResourceLocation type,biome;final int rounds,radius;final BlockPos origin;
        final Map<UUID,MobPoint> points=new LinkedHashMap<>();
        List<Entity> capture;Iterator<Entity> bootstrap;int captureAt;MobGrid grid=new MobGrid();Future<List<MobPoint>> sorted;
        Future<List<ChunkPos>> plan;List<ChunkPos> chunks;int checked,round,anchorOffset;
        SavedCreatureIndex.Cursor saved;SavedCreatureIndex.Point savedCandidate;boolean savedFinished;
        int storedVerified,storedRejected;long storedScanned,storedTotal,resultLeaseUntil;
        String source="loaded";List<ResourceLocation> anchorBiomes;int anchorAt;

        SearchJob anchorJob;BlockPos anchor;ChunkPos pending;boolean ticket,finished,reloading;
        UUID teleport,teleportRequest;boolean fallback,confirmation;
        long lastProgressPacket,anchorLimit;String anchorMode="",anchorStage="";
        long waitStarted,started=System.nanoTime(),lastUpdate,lastProgress=started,resultRevision,anchorSamples;
        String stage="initial";boolean terminal;
        List<UUID> refreshing;int refreshAt;
        Query(ServerPlayer p,ItemStack s,UUID token,ResourceLocation type,ResourceLocation biome,int rounds){player=p;level=p.serverLevel();stack=s;this.token=token;this.type=type;this.biome=biome;this.rounds=rounds;radius=SearchConfig.RADIUS.get();origin=p.blockPosition().immutable();}
        boolean valid(){return held(player)==stack && !player.isRemoved() && player.isAlive() && player.serverLevel()==level;}
    }
    private CreatureSearch() {}
    public static ItemStack held(ServerPlayer p){if(p.getMainHandItem().getItem()==CreatureItem.INSTANCE)return p.getMainHandItem();if(p.getOffhandItem().getItem()==CreatureItem.INSTANCE)return p.getOffhandItem();return ItemStack.EMPTY;}
    public static void request(ServerPlayer player,CompoundTag n) {
        ItemStack stack=held(player);if(stack.isEmpty() || !n.hasUUID("request"))return;
        UUID token=n.getUUID("request");String action=n.getString("action");
        if(action.equals("catalog")) {
            for(var response:CreatureCatalogPackets.encode(token,CreatureCatalog.all(player.getServer()),
                    com.chaosthedude.explorerscompass.config.ConfigHandler.GENERAL.allowTeleport.get()))send(player,response);
            return;
        }
        if(action.equals("cancel")){Query q=tasks.get(player.getUUID());if(q!=null && q.token.equals(token))cancel(player.getUUID());return;}
        if(action.equals("teleport")) {
            Query q=tasks.get(player.getUUID());if(q==null || !q.token.equals(token) || !q.valid() || !q.finished || !n.hasUUID("entity"))return;
            UUID id=n.getUUID("entity");q.teleportRequest=n.hasUUID("teleportRequest")?n.getUUID("teleportRequest"):null;
            if(!q.points.containsKey(id) || !com.chaosthedude.explorerscompass.config.ConfigHandler.GENERAL.allowTeleport.get()){teleportReceipt(q,false);return;}q.teleport=id;q.fallback=n.getBoolean("fallback");
            Entity entity=q.level.getEntity(id);
            if(entity!=null){teleport(q);return;}
            release(q);q.reloading=true;q.stage="reload_entity";q.pending=new ChunkPos(BlockPos.containing(q.points.get(id).x(),q.points.get(id).y(),q.points.get(id).z()));q.waitStarted=System.nanoTime();publish(q);teleportReceipt(q,true);return;
        }
        if(!action.equals("search"))return;
        ResourceLocation type=parse(n.getString("type")),biome=parse(n.getString("biome")),dimension=parse(n.getString("dimension"));
        CreatureEntry entry=type==null?null:CreatureCatalog.get(player.getServer(),type);int rounds=n.getInt("rounds");
        if(entry==null || rounds<0 || rounds>MAX_ROUNDS || dimension!=null && !dimension.equals(player.serverLevel().dimension().location())
                || !entry.matches(player.serverLevel().dimension().location(),biome)) {
            var rejected=new CompoundTag();rejected.putString("action","results");rejected.putUUID("request",token);rejected.putString("state","rejected_dimension_or_filter");rejected.putBoolean("finished",true);rejected.put("points",new ListTag());send(player,rejected);return;
        }
        com.chaosthedude.explorerscompass.unified.entertainment.AdventureTravel.interrupt(player,"entertainment.replaced");
        SearchService.cancel(player,true);cancel(player.getUUID());
        Query q=new Query(player,stack,token,type,biome,rounds);tasks.put(player.getUUID(),q);beginCapture(q);publish(q);
    }
    private static ResourceLocation parse(String value){return value.isBlank()?null:ResourceLocation.tryParse(value);}
    private static void beginCapture(Query q){q.capture=CreatureIndex.entities(q.level,q.type);q.captureAt=0;q.bootstrap=com.google.common.collect.ImmutableList.copyOf(q.level.getAllEntities()).iterator();q.grid=new MobGrid();q.sorted=null;}
    public static boolean active(){return !tasks.isEmpty();}
    public static boolean step() {
        long quantum=System.nanoTime()+250_000L;CreatureIndex.drain(quantum);
        if(tasks.isEmpty())return false;var all=new ArrayList<>(tasks.values());Query q=all.get(Math.floorMod(cursor++,all.size()));
        if(!q.valid()){cancel(q.player.getUUID());return true;}
        try {
            boolean work=advance(q,quantum);long now=System.nanoTime();
            if(!q.finished && now-q.lastProgressPacket>=200_000_000L){q.lastProgressPacket=now;send(q.player,base(q));}
            return work;
        }catch(Exception error){ExplorersCompass.LOGGER.warn("生物搜索 {} 异常",q.token,error);done(q,"error");return true;}
    }
    private static boolean advance(Query q,long deadline) throws Exception {
        if(q.reloading){
            if(!acquire(q))return false;
            if(ready(q)){CreatureIndex.drain(deadline);q.reloading=false;teleport(q);release(q);return true;}
            if(System.nanoTime()-q.waitStarted>60_000_000_000L){q.reloading=false;release(q);q.stage="entity_load_timeout";publish(q);}return false;
        }
        if(q.finished) {
            if(q.ticket && (System.nanoTime()>q.resultLeaseUntil || VerificationSlots.queued()>0))release(q);
            if(q.refreshing==null){if(System.nanoTime()-q.lastUpdate<500_000_000L)return false;q.refreshing=List.copyOf(q.points.keySet());q.refreshAt=0;}
            // 大量结果也按玩家时间片更新，不能每半秒独占主线程。
            while(q.refreshAt<q.refreshing.size() && System.nanoTime()<deadline) {
                var old=q.points.get(q.refreshing.get(q.refreshAt++));if(old==null)continue;
                Entity entity=q.level.getEntity(old.uuid());
                if(entity==null){q.points.put(old.uuid(),old.unloaded());continue;}
                if(!entity.isAlive() || entity.isRemoved()){q.points.remove(old.uuid());continue;}
                var p=CreatureIndex.point(q.level,entity);
                if(p==null)q.points.put(old.uuid(),old.unloaded());
                else if(q.biome!=null && !q.biome.equals(p.biome()))q.points.remove(old.uuid());else q.points.put(p.uuid(),p);
            }
            if(q.refreshAt<q.refreshing.size())return true;
            q.refreshing=null;q.lastUpdate=System.nanoTime();
            publish(q);return true;
        }
        if(q.capture!=null){
            while(q.captureAt<q.capture.size() && System.nanoTime()<deadline){Entity e=q.capture.get(q.captureAt++);MobPoint p=CreatureIndex.point(q.level,e);if(p!=null && (q.biome==null || q.biome.equals(p.biome())))q.grid.put(p);}
            if(q.captureAt<q.capture.size())return true;
            // 补扫实际已加载实体，避免加入事件队列尚未排空时遗漏；不在后台访问 Entity。
            while(q.bootstrap!=null && q.bootstrap.hasNext() && System.nanoTime()<deadline){
                Entity e=q.bootstrap.next();if(!q.type.equals(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType())))continue;
                MobPoint p=CreatureIndex.point(q.level,e);if(p!=null && (q.biome==null || q.biome.equals(p.biome())))q.grid.put(p);
            }
            if(q.bootstrap!=null && q.bootstrap.hasNext())return true;q.bootstrap=null;
            if(q.sorted==null){var snapshot=q.grid.snapshot();int ox=q.origin.getX(),oz=q.origin.getZ(),radius=q.radius;try{q.sorted=SearchPool.executor().submit(()->MobGrid.nearest(snapshot,ox,oz,radius));}catch(RejectedExecutionException busy){return false;}}
            if(!q.sorted.isDone())return false;
            for(var predicted:q.sorted.get()){var actual=CreatureIndex.point(q.level,q.level.getEntity(predicted.uuid()));if(accepts(q,actual))q.points.put(actual.uuid(),actual);}
            q.capture=null;q.sorted=null;
            if(!q.points.isEmpty()){done(q,"found");return true;}
            if(q.rounds==0){done(q,"not_found");return true;}
        }
        if(!q.savedFinished){
            if(q.saved==null){q.saved=SavedCreatureIndex.open(q.level,q.type,q.origin,q.radius);q.source="saved";q.stage="scanning_saved";publish(q);}
            q.saved.step(deadline);q.storedScanned=q.saved.scanned();q.storedTotal=q.saved.total();
            if(q.savedCandidate!=null){
                if(!acquire(q))return false;
                if(!ready(q)){
                    if(System.nanoTime()-q.waitStarted<=60_000_000_000L)return false;
                    rejectSaved(q);return true;
                }
                var actual=CreatureIndex.point(q.level,q.level.getEntity(q.savedCandidate.uuid()));q.storedVerified++;
                if(accepts(q,actual)){q.points.put(actual.uuid(),actual);done(q,"found");return true;}
                rejectSaved(q);return true;
            }
            var candidate=q.saved.poll();
            if(candidate!=null){
                // 索引坐标只安排核对，绝不发送为 live 结果。源区块可不同于乘客的当前位置区块。
                q.savedCandidate=candidate;q.pending=candidate.source();q.waitStarted=System.nanoTime();q.stage="verifying_saved";publish(q);return acquire(q);
            }
            if(!q.saved.exhausted()){q.stage="scanning_saved";return false;}
            q.saved.close();q.savedFinished=true;q.source="neighbor";
        }
        if(q.anchor==null && q.anchorJob==null){
            if(q.checked>=q.rounds*ROUND_CHUNKS){done(q,"not_found");return true;}
            if(q.anchorBiomes==null){
                var entry=CreatureCatalog.get(q.player.getServer(),q.type);
                var possible=q.level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream().flatMap(b->b.unwrapKey().stream()).map(k->k.location()).collect(java.util.stream.Collectors.toSet());
                q.anchorBiomes=q.biome!=null?List.of(q.biome):entry.biomes().stream().filter(possible::contains).sorted().toList();
            }
            if(q.anchorAt<q.anchorBiomes.size()){
                var target=q.anchorBiomes.get(q.anchorAt++);q.anchorJob=BiomeQueryFactory.create(q.level,q.origin,target,List.of(),q.radius,SearchConfig.BIOME_SAMPLES.get(),SearchConfig.ASYNC_BIOMES.get(),true,-1);
                q.anchorJob.prime();q.stage="locating_biome";q.lastProgress=System.nanoTime();publish(q);return true;
            }
            q.anchor=q.origin;
        }
        if(q.anchorJob!=null){
            boolean work=q.anchorJob.step();long samples=q.anchorJob.samples();q.anchorLimit=q.anchorJob.sampleLimit();q.anchorMode=q.anchorJob.mode();q.anchorStage=q.anchorJob.stage();if(work || samples!=q.anchorSamples){q.lastProgress=System.nanoTime();q.anchorSamples=samples;}
            if(!q.anchorJob.done()){if(System.nanoTime()-q.lastProgress>40_000_000_000L){q.anchorJob.cancel();q.anchorJob=null;done(q,"biome_stalled");}return work;}
            var p=q.anchorJob.result();String reason=q.anchorJob.stage();q.anchorJob.cancel();q.anchorJob=null;
            if(p==null){if(q.biome==null && q.anchorAt<q.anchorBiomes.size()){q.anchor=null;return true;}done(q,"biome_"+reason);return true;}q.anchor=p.position();
        }
        if(q.chunks==null) {
            if(q.anchor==null)q.anchor=q.origin;
            if(q.plan==null){var anchor=q.anchor;int count=q.biome==null && !q.anchorBiomes.isEmpty()?Math.min(ROUND_CHUNKS,q.rounds*ROUND_CHUNKS-q.checked):q.rounds*ROUND_CHUNKS-q.checked;try{q.plan=SearchPool.executor().submit(()->chunkPlan(anchor,count));}catch(RejectedExecutionException busy){return false;}}
            if(!q.plan.isDone())return false;q.chunks=q.plan.get();q.stage="loading_chunks";publish(q);
        }
        if(q.pending!=null){
            if(!acquire(q))return false;
            if(!ready(q)) {if(System.nanoTime()-q.waitStarted>60_000_000_000L){release(q);q.pending=null;done(q,"chunk_timeout");return true;}return false;}
            CreatureIndex.drain(deadline);
            var box=new net.minecraft.world.phys.AABB(q.pending.getMinBlockX()-16,q.level.getMinBuildHeight(),q.pending.getMinBlockZ()-16,q.pending.getMaxBlockX()+17,q.level.getMaxBuildHeight(),q.pending.getMaxBlockZ()+17);
            for(var entity:q.level.getEntities((Entity)null,box,e->q.type.equals(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType())))) {
                var p=CreatureIndex.point(q.level,entity);
                if(accepts(q,p))q.points.put(p.uuid(),p);
            }
            q.checked++;q.lastProgress=System.nanoTime();
            if(!q.points.isEmpty()){q.round=(q.checked+ROUND_CHUNKS-1)/ROUND_CHUNKS;done(q,"found");return true;}
            release(q);q.pending=null;
            if(q.checked%ROUND_CHUNKS==0 || q.checked-q.anchorOffset==q.chunks.size()) {q.round++;beginCapture(q);publish(q);return true;}
        }
        if(q.checked-q.anchorOffset>=q.chunks.size()){
            if(q.biome==null && q.checked<q.rounds*ROUND_CHUNKS && q.anchorAt<q.anchorBiomes.size()){
                q.anchor=null;q.chunks=null;q.plan=null;q.anchorOffset=q.checked;return true;
            }
            done(q,"not_found");return true;
        }
        q.pending=q.chunks.get(q.checked-q.anchorOffset);q.waitStarted=System.nanoTime();return acquire(q);
    }
    private static boolean accepts(Query q,MobPoint point){return point!=null && point.type().equals(q.type) && (q.biome==null || q.biome.equals(point.biome())) && point.distance(q.origin.getX(),q.origin.getZ())<=(double)q.radius*q.radius;}
    private static void rejectSaved(Query q){q.storedRejected++;q.saved.rejected();release(q);q.pending=null;q.savedCandidate=null;q.stage="scanning_saved";}
    public static List<ChunkPos> chunkPlan(BlockPos anchor,int count) {
        var geometry=new RectFrontier.Geometry(anchor.getX(),anchor.getZ(),16,8,8,8,8);
        var frontier=new RectFrontier(geometry,geometry.domain(2048),8,2048L*2048);var chunks=new ArrayList<ChunkPos>(count);
        while(chunks.size()<count && frontier.available(Long.MAX_VALUE)){long p=frontier.next();int x=(int)(p>>32),z=(int)p;if(frontier.inRange(x,z))chunks.add(new ChunkPos(x,z));}
        return List.copyOf(chunks);
    }
    private static boolean acquire(Query q){if(q.ticket)return true;if(!VerificationSlots.acquire(q))return false;q.level.getChunkSource().addRegionTicket(TICKET,q.pending,1,q.token);q.ticket=true;return true;}
    private static boolean ready(Query q){for(int x=q.pending.x-1;x<=q.pending.x+1;x++)for(int z=q.pending.z-1;z<=q.pending.z+1;z++)if(q.level.getChunkSource().getChunkNow(x,z)==null || !q.level.areEntitiesLoaded(ChunkPos.asLong(x,z)))return false;return true;}
    private static void release(Query q){if(q.ticket){q.level.getChunkSource().removeRegionTicket(TICKET,q.pending,1,q.token);q.ticket=false;}VerificationSlots.release(q);}
    private static void done(Query q,String stage){
        if(stage.equals("found") && q.ticket)q.resultLeaseUntil=System.nanoTime()+60_000_000_000L;else release(q);
        if(q.saved!=null)q.saved.close();q.finished=true;q.stage=stage;publish(q);if(!q.terminal){q.terminal=true;ExplorersCompass.LOGGER.info("CREATURE {} type={} biome={} state={} rounds={}/{} chunks={} count={} source={} stored={}/{} verified={} rejected={} elapsed_ms={}",q.token,q.type,q.biome,stage,q.round,q.rounds,q.checked,q.points.size(),q.source,q.storedScanned,q.storedTotal,q.storedVerified,q.storedRejected,(System.nanoTime()-q.started)/1e6);}}
    private static void teleport(Query q) {
        UUID id=q.teleport;var e=q.level.getEntity(id);var point=CreatureIndex.point(q.level,e);
        if(point==null || !point.type().equals(q.type) || q.biome!=null && !q.biome.equals(point.biome())){q.points.remove(id);q.stage="entity_gone";publish(q);teleportReceipt(q,false);return;}
        q.points.put(id,point);
        boolean accepted=TeleportService.requestLocator(q.player,q.stack,q.token,new LocatorTarget(){
            public BlockPos position(){return e.blockPosition();}
            public boolean valid(){return tasks.get(q.player.getUUID())==q && q.valid() && q.level.getEntity(id)==e && e.isAlive() && !e.isRemoved() && CreatureIndex.ready(q.level,e.blockPosition()) && (q.biome==null || q.level.getBiome(e.blockPosition()).unwrapKey().map(k->k.location().equals(q.biome)).orElse(false));}
            public boolean accepts(Vec3 landing){return valid() && e.distanceToSqr(landing)<144 && !e.getBoundingBox().intersects(q.player.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(landing));}
            public void state(String phase,boolean confirmation){q.stage="teleport_"+phase;q.confirmation=confirmation;send(q.player,base(q));}
            public void moved(){q.stage="teleport_moved";q.confirmation=false;publish(q);}
        },q.fallback);
        teleportReceipt(q,accepted);
    }
    private static void teleportReceipt(Query q,boolean accepted){var n=base(q);if(q.teleportRequest!=null)n.putUUID("teleportRequest",q.teleportRequest);n.putBoolean("teleportAccepted",accepted);send(q.player,n);if(!accepted)q.player.displayClientMessage(net.minecraft.network.chat.Component.literal("生物传送请求未被接受，请检查目标和传送总开关。"),false);}
    private static CompoundTag base(Query q){var n=new CompoundTag();n.putString("action","results");n.putUUID("request",q.token);n.putString("state",q.stage);n.putInt("round",q.round);n.putInt("rounds",q.rounds);n.putInt("chunks",q.checked);n.putBoolean("finished",q.finished);n.putBoolean("confirmation",q.confirmation);n.putLong("anchorSamples",q.anchorSamples);n.putLong("anchorLimit",q.anchorLimit);n.putString("anchorMode",q.anchorMode);n.putString("anchorStage",q.anchorStage);n.putInt("captured",q.captureAt);n.putInt("captureTotal",q.capture==null?q.captureAt:q.capture.size());n.putInt("chunksTotal",q.rounds*ROUND_CHUNKS);n.putLong("storedScanned",q.storedScanned);n.putLong("storedTotal",q.storedTotal);n.putInt("storedVerified",q.storedVerified);n.putInt("storedRejected",q.storedRejected);n.putString("source",q.source);if(q.saved!=null){n.putInt("storedRegions",q.saved.regionsDone());n.putInt("storedRegionsTotal",q.saved.regionsTotal());}return n;}
    private static void publish(Query q){
        var values=q.points.values().stream().sorted(Comparator.<MobPoint>comparingDouble(p->p.distance(q.origin.getX(),q.origin.getZ())).thenComparing(p->p.uuid().toString())).toList();
        int parts=Math.max(1,(values.size()+255)/256);long revision=++q.resultRevision;
        for(int part=0;part<parts;part++) {var n=base(q);var rows=new ListTag();for(int i=part*256;i<Math.min(values.size(),(part+1)*256);i++)rows.add(values.get(i).encode());n.put("points",rows);n.putInt("part",part);n.putInt("parts",parts);n.putLong("revision",revision);send(q.player,n);}
    }
    private static void send(ServerPlayer p,CompoundTag n){UnifiedNetwork.send(p,new CreatureUpdate(n));}
    public static void cancel(UUID player){Query q=tasks.remove(player);if(q==null)return;if(q.anchorJob!=null)q.anchorJob.cancel();if(q.sorted!=null)q.sorted.cancel(true);if(q.plan!=null)q.plan.cancel(true);if(q.saved!=null)q.saved.close();release(q);TeleportService.cancel(player);q.stage="cancelled";q.finished=true;publish(q);}
    public static void clear(){for(var id:List.copyOf(tasks.keySet()))cancel(id);CreatureIndex.clear();SavedCreatureIndex.clear();CreatureCatalog.clear();}
    public static void destroyed(ServerLevel level,UUID entity){SavedCreatureIndex.destroyed(level,entity);for(var q:tasks.values())if(q.level==level && q.points.remove(entity)!=null){q.lastUpdate=0;}}
    public static int tickets(){return (int)tasks.values().stream().filter(q->q.ticket).count();}
}
