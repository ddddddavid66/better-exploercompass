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
        List<Entity> capture;int captureAt;MobGrid grid=new MobGrid();Future<List<MobPoint>> sorted;
        Future<List<ChunkPos>> plan;List<ChunkPos> chunks;int checked,round;
        SearchJob anchorJob;BlockPos anchor;ChunkPos pending;boolean ticket,finished,reloading;
        UUID teleport;boolean fallback,confirmation;
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
            var response=new CompoundTag();response.putString("action","catalog");response.putUUID("request",token);var rows=new ListTag();
            CreatureCatalog.all(player.getServer()).forEach(e->rows.add(CreatureCatalog.encode(e)));response.put("entries",rows);
            response.putBoolean("canTeleport",com.chaosthedude.explorerscompass.config.ConfigHandler.GENERAL.allowTeleport.get());send(player,response);return;
        }
        if(action.equals("cancel")){Query q=tasks.get(player.getUUID());if(q!=null && q.token.equals(token))cancel(player.getUUID());return;}
        if(action.equals("teleport")) {
            Query q=tasks.get(player.getUUID());if(q==null || !q.token.equals(token) || !q.valid() || !q.finished || !n.hasUUID("entity"))return;
            UUID id=n.getUUID("entity");if(!q.points.containsKey(id))return;q.teleport=id;q.fallback=n.getBoolean("fallback");
            Entity entity=q.level.getEntity(id);
            if(entity!=null){teleport(q);return;}
            release(q);q.reloading=true;q.stage="reload_entity";q.pending=new ChunkPos(BlockPos.containing(q.points.get(id).x(),q.points.get(id).y(),q.points.get(id).z()));q.waitStarted=System.nanoTime();publish(q);return;
        }
        if(!action.equals("search"))return;
        ResourceLocation type=parse(n.getString("type")),biome=parse(n.getString("biome")),dimension=parse(n.getString("dimension"));
        CreatureEntry entry=type==null?null:CreatureCatalog.get(player.getServer(),type);int rounds=n.getInt("rounds");
        if(entry==null || rounds<0 || rounds>MAX_ROUNDS || dimension!=null && !dimension.equals(player.serverLevel().dimension().location())
                || !entry.dimensions().contains(player.serverLevel().dimension().location()) || biome!=null && !entry.biomes().contains(biome)) {
            var rejected=new CompoundTag();rejected.putString("action","results");rejected.putUUID("request",token);rejected.putString("state","rejected_dimension_or_filter");rejected.putBoolean("finished",true);rejected.put("points",new ListTag());send(player,rejected);return;
        }
        com.chaosthedude.explorerscompass.unified.entertainment.AdventureTravel.interrupt(player,"entertainment.replaced");
        SearchService.cancel(player,true);cancel(player.getUUID());
        Query q=new Query(player,stack,token,type,biome,rounds);tasks.put(player.getUUID(),q);beginCapture(q);publish(q);
    }
    private static ResourceLocation parse(String value){return value.isBlank()?null:ResourceLocation.tryParse(value);}
    private static void beginCapture(Query q){q.capture=CreatureIndex.entities(q.level,q.type);q.captureAt=0;q.grid=new MobGrid();q.sorted=null;}
    public static boolean active(){return !tasks.isEmpty();}
    public static boolean step() {
        long quantum=System.nanoTime()+250_000L;CreatureIndex.drain(quantum);
        if(tasks.isEmpty())return false;var all=new ArrayList<>(tasks.values());Query q=all.get(Math.floorMod(cursor++,all.size()));
        if(!q.valid()){cancel(q.player.getUUID());return true;}
        try{return advance(q,quantum);}catch(Exception error){ExplorersCompass.LOGGER.warn("生物搜索 {} 异常",q.token,error);done(q,"error");return true;}
    }
    private static boolean advance(Query q,long deadline) throws Exception {
        if(q.reloading){
            if(!acquire(q))return false;
            if(ready(q)){CreatureIndex.drain(deadline);q.reloading=false;teleport(q);release(q);return true;}
            if(System.nanoTime()-q.waitStarted>60_000_000_000L){q.reloading=false;release(q);q.stage="entity_load_timeout";publish(q);}return false;
        }
        if(q.finished) {
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
            if(q.sorted==null){var snapshot=q.grid.snapshot();int ox=q.origin.getX(),oz=q.origin.getZ(),radius=q.radius;try{q.sorted=SearchPool.executor().submit(()->MobGrid.nearest(snapshot,ox,oz,radius));}catch(RejectedExecutionException busy){return false;}}
            if(!q.sorted.isDone())return false;
            for(var predicted:q.sorted.get()){var actual=CreatureIndex.point(q.level,q.level.getEntity(predicted.uuid()));if(actual!=null && (q.biome==null || q.biome.equals(actual.biome())))q.points.put(actual.uuid(),actual);}
            q.capture=null;q.sorted=null;
            if(!q.points.isEmpty()){done(q,"found");return true;}
            if(q.rounds==0){done(q,"not_found");return true;}
            if(q.checked==0 && q.anchor==null && q.biome!=null){q.anchorJob=new BiomeSearch(q.level,q.origin,q.biome,List.of(),q.radius,SearchConfig.BIOME_SAMPLES.get(),SearchConfig.SPACING.get(),SearchConfig.VERTICAL.get(),SearchConfig.DEPTH_INTERVAL.get(),SearchConfig.ASYNC_BIOMES.get(),true);q.stage="locating_biome";q.anchorJob.prime();publish(q);return true;}
        }
        if(q.anchorJob!=null){
            boolean work=q.anchorJob.step();long samples=q.anchorJob.samples();if(work || samples!=q.anchorSamples){q.lastProgress=System.nanoTime();q.anchorSamples=samples;}
            if(!q.anchorJob.done()){if(System.nanoTime()-q.lastProgress>40_000_000_000L){q.anchorJob.cancel();q.anchorJob=null;done(q,"biome_stalled");}return work;}
            var p=q.anchorJob.result();String reason=q.anchorJob.stage();q.anchorJob.cancel();q.anchorJob=null;
            if(p==null){done(q,"biome_"+reason);return true;}q.anchor=p.position();
        }
        if(q.chunks==null) {
            if(q.anchor==null)q.anchor=q.origin;
            if(q.plan==null){var anchor=q.anchor;int count=q.rounds*ROUND_CHUNKS;try{q.plan=SearchPool.executor().submit(()->chunkPlan(anchor,count));}catch(RejectedExecutionException busy){return false;}}
            if(!q.plan.isDone())return false;q.chunks=q.plan.get();q.stage="loading_chunks";publish(q);
        }
        if(q.pending!=null){
            if(!acquire(q))return false;
            if(!ready(q)) {if(System.nanoTime()-q.waitStarted>60_000_000_000L){release(q);q.pending=null;done(q,"chunk_timeout");return true;}return false;}
            CreatureIndex.drain(deadline);
            var box=new net.minecraft.world.phys.AABB(q.pending.getMinBlockX()-16,q.level.getMinBuildHeight(),q.pending.getMinBlockZ()-16,q.pending.getMaxBlockX()+17,q.level.getMaxBuildHeight(),q.pending.getMaxBlockZ()+17);
            for(var entity:q.level.getEntities((Entity)null,box,e->q.type.equals(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType())))) {
                var p=CreatureIndex.point(q.level,entity);
                if(p!=null && (q.biome==null || q.biome.equals(p.biome())) && p.distance(q.origin.getX(),q.origin.getZ())<=(double)q.radius*q.radius)q.points.put(p.uuid(),p);
            }
            q.checked++;q.lastProgress=System.nanoTime();release(q);q.pending=null;
            if(!q.points.isEmpty()){q.round=(q.checked+ROUND_CHUNKS-1)/ROUND_CHUNKS;done(q,"found");return true;}
            if(q.checked%ROUND_CHUNKS==0 || q.checked==q.chunks.size()) {q.round++;beginCapture(q);publish(q);return true;}
        }
        if(q.checked>=q.chunks.size()){done(q,"not_found");return true;}
        q.pending=q.chunks.get(q.checked);q.waitStarted=System.nanoTime();return acquire(q);
    }
    public static List<ChunkPos> chunkPlan(BlockPos anchor,int count) {
        var geometry=new RectFrontier.Geometry(anchor.getX(),anchor.getZ(),16,8,8,8,8);
        var frontier=new RectFrontier(geometry,geometry.domain(2048),8,2048L*2048);var chunks=new ArrayList<ChunkPos>(count);
        while(chunks.size()<count && frontier.available(Long.MAX_VALUE)){long p=frontier.next();int x=(int)(p>>32),z=(int)p;if(frontier.inRange(x,z))chunks.add(new ChunkPos(x,z));}
        return List.copyOf(chunks);
    }
    private static boolean acquire(Query q){if(q.ticket)return true;if(!VerificationSlots.acquire(q))return false;q.level.getChunkSource().addRegionTicket(TICKET,q.pending,1,q.token);q.ticket=true;return true;}
    private static boolean ready(Query q){for(int x=q.pending.x-1;x<=q.pending.x+1;x++)for(int z=q.pending.z-1;z<=q.pending.z+1;z++)if(q.level.getChunkSource().getChunkNow(x,z)==null || !q.level.areEntitiesLoaded(ChunkPos.asLong(x,z)))return false;return true;}
    private static void release(Query q){if(q.ticket){q.level.getChunkSource().removeRegionTicket(TICKET,q.pending,1,q.token);q.ticket=false;}VerificationSlots.release(q);}
    private static void done(Query q,String stage){release(q);q.finished=true;q.stage=stage;publish(q);if(!q.terminal){q.terminal=true;ExplorersCompass.LOGGER.info("CREATURE {} type={} biome={} state={} rounds={}/{} chunks={} count={} elapsed_ms={}",q.token,q.type,q.biome,stage,q.round,q.rounds,q.checked,q.points.size(),(System.nanoTime()-q.started)/1e6);}}
    private static void teleport(Query q) {
        UUID id=q.teleport;var e=q.level.getEntity(id);var point=CreatureIndex.point(q.level,e);
        if(point==null || !point.type().equals(q.type) || q.biome!=null && !q.biome.equals(point.biome())){q.points.remove(id);q.stage="entity_gone";publish(q);return;}
        q.points.put(id,point);
        TeleportService.requestLocator(q.player,q.stack,q.token,new LocatorTarget(){
            public BlockPos position(){return e.blockPosition();}
            public boolean valid(){return tasks.get(q.player.getUUID())==q && q.valid() && q.level.getEntity(id)==e && e.isAlive() && !e.isRemoved() && CreatureIndex.ready(q.level,e.blockPosition()) && (q.biome==null || q.level.getBiome(e.blockPosition()).unwrapKey().map(k->k.location().equals(q.biome)).orElse(false));}
            public boolean accepts(Vec3 landing){return valid() && e.distanceToSqr(landing)<144 && !e.getBoundingBox().intersects(q.player.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(landing));}
            public void state(String phase,boolean confirmation){q.stage="teleport_"+phase;q.confirmation=confirmation;send(q.player,base(q));}
            public void moved(){q.stage="teleport_moved";q.confirmation=false;publish(q);}
        },q.fallback);
    }
    private static CompoundTag base(Query q){var n=new CompoundTag();n.putString("action","results");n.putUUID("request",q.token);n.putString("state",q.stage);n.putInt("round",q.round);n.putInt("rounds",q.rounds);n.putInt("chunks",q.checked);n.putBoolean("finished",q.finished);n.putBoolean("confirmation",q.confirmation);return n;}
    private static void publish(Query q){
        var values=q.points.values().stream().sorted(Comparator.<MobPoint>comparingDouble(p->p.distance(q.origin.getX(),q.origin.getZ())).thenComparing(p->p.uuid().toString())).toList();
        int parts=Math.max(1,(values.size()+255)/256);long revision=++q.resultRevision;
        for(int part=0;part<parts;part++) {var n=base(q);var rows=new ListTag();for(int i=part*256;i<Math.min(values.size(),(part+1)*256);i++)rows.add(values.get(i).encode());n.put("points",rows);n.putInt("part",part);n.putInt("parts",parts);n.putLong("revision",revision);send(q.player,n);}
    }
    private static void send(ServerPlayer p,CompoundTag n){UnifiedNetwork.send(p,new CreatureUpdate(n));}
    public static void cancel(UUID player){Query q=tasks.remove(player);if(q==null)return;if(q.anchorJob!=null)q.anchorJob.cancel();if(q.sorted!=null)q.sorted.cancel(true);if(q.plan!=null)q.plan.cancel(true);release(q);TeleportService.cancel(player);q.stage="cancelled";publish(q);}
    public static void clear(){for(var id:List.copyOf(tasks.keySet()))cancel(id);CreatureIndex.clear();CreatureCatalog.clear();}
    public static void destroyed(ServerLevel level,UUID entity){for(var q:tasks.values())if(q.level==level && q.points.remove(entity)!=null){q.lastUpdate=0;}}
    public static int tickets(){return (int)tasks.values().stream().filter(q->q.ticket).count();}
}
