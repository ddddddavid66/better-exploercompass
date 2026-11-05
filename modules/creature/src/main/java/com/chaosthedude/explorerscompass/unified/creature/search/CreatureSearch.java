package com.chaosthedude.explorerscompass.unified.creature.search;

import com.david.compass.runtime.game.search.BiomeQueryFactory;
import com.david.compass.runtime.game.search.LocatorTarget;
import com.david.compass.runtime.game.search.RectFrontier;
import com.david.compass.runtime.game.search.SearchJob;
import com.david.compass.runtime.game.search.SearchPool;
import com.david.compass.runtime.game.search.SearchReplacement;
import com.david.compass.runtime.game.search.TeleportService;
import com.david.compass.runtime.game.search.VerificationSlots;
import com.david.compass.runtime.game.data.WorldDirectory;

import com.chaosthedude.explorerscompass.unified.creature.CreatureIdentity;
import com.david.compass.runtime.SearchParameters;
import com.chaosthedude.explorerscompass.unified.creature.CreatureConfig;
import com.david.compass.runtime.game.data.LandingMode;
import com.chaosthedude.explorerscompass.unified.creature.CreatureItem;
import com.chaosthedude.explorerscompass.unified.creature.catalog.*;
import com.chaosthedude.explorerscompass.unified.creature.network.*;
import com.chaosthedude.explorerscompass.unified.creature.network.CreatureNetwork;
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
        final SearchParameters settings; LandingMode mode=LandingMode.DEFAULT;
        final ResourceLocation type,biome;final int rounds,radius;final BlockPos origin;
        final Map<UUID,MobPoint> points=new LinkedHashMap<>();
        List<Entity> capture;Iterator<Entity> bootstrap;int captureAt;MobGrid grid=new MobGrid();Future<List<MobPoint>> sorted;
        Future<Boolean> plan;CreatureChunkPlanner.Plan planner;List<ChunkPos> chunks;int checked,round,anchorOffset,plannedAt;
        final List<ChunkWork> flights=new ArrayList<>();final ArrayDeque<BlockPos> habitatHints=new ArrayDeque<>();
        final Set<Long> hintChunks=new HashSet<>();CreatureHabitats.Rules rules;String patchKind="";ChunkPos visitCandidate;
        SavedCreatureIndex.Cursor saved;SavedCreatureIndex.Point savedCandidate;boolean savedFinished;
        int storedVerified,storedRejected;long storedScanned,storedTotal,resultLeaseUntil;
        String source="loaded";Set<ResourceLocation> anchorBiomes;
        final Set<Long> checkedChunks=new HashSet<>();
        final Set<Long> habitatExcluded=new HashSet<>();
        final ArrayDeque<Entity> deferred=new ArrayDeque<>();Entity boundary;
        long anchorBase;

        HabitatVisit visit; int guideChecks;
        SearchJob anchorJob;BlockPos anchor;ChunkPos pending;boolean ticket,finished,reloading;
        UUID teleport,teleportRequest;boolean fallback,confirmation;
        long lastProgressPacket,anchorLimit;String anchorMode="",anchorStage="";
        long waitStarted,started=System.nanoTime(),lastUpdate,lastProgress=started,resultRevision,anchorSamples;
        String stage="initial";boolean terminal;
        String measuredStage="initial";long stageSince=started;final Map<String,Long> phaseNanos=new HashMap<>();
        List<UUID> refreshing;int refreshAt;
        Query(ServerPlayer p,ItemStack s,UUID token,ResourceLocation type,ResourceLocation biome,int rounds){player=p;level=p.serverLevel();stack=s;this.token=token;this.type=type;this.biome=biome;this.rounds=rounds;settings=CreatureConfig.GENERAL.snapshot();radius=settings.radius();origin=p.blockPosition().immutable();rules=CreatureHabitats.get(level,type);anchorBiomes=biome==null?rules.biomes():Set.of(biome);}
        boolean valid(){return held(player)==stack && !player.isRemoved() && player.isAlive() && player.serverLevel()==level;}
    }
    private static final class ChunkWork {
        final ChunkPos center;final UUID ticketId=UUID.randomUUID();final long started=System.nanoTime();
        ChunkPos retainedCenter;
        boolean ticket;long waiting;List<Entity> entities;int at;Map<ChunkPos,List<SavedCreatureIndex.Point>> snapshots;
        ChunkWork(ChunkPos center){this.center=center;retainedCenter=center;}
    }
    private CreatureSearch() {}
    public static ItemStack held(ServerPlayer p){if(p.getMainHandItem().getItem()==CreatureItem.INSTANCE)return p.getMainHandItem();if(p.getOffhandItem().getItem()==CreatureItem.INSTANCE)return p.getOffhandItem();return ItemStack.EMPTY;}
    public static void request(ServerPlayer player,CompoundTag n) {
        ItemStack stack=held(player);if(stack.isEmpty() || !n.hasUUID("request"))return;
        UUID token=n.getUUID("request");String action=n.getString("action");
        if(action.equals("world_catalog")) {
            CreatureNetwork.send(player,new CreatureWorldCatalogPacket(token,player.serverLevel().dimension().location(),
                    com.david.compass.runtime.game.data.WorldDirectory.get(player.getServer())));
            return;
        }
        if(action.equals("catalog")) {
            for(var response:CreatureCatalogPackets.encode(token,CreatureCatalog.all(player.getServer()),
                    com.chaosthedude.explorerscompass.unified.creature.CreatureConfig.GENERAL.allowTeleport.get()))send(player,response);
            return;
        }
        if(action.equals("cancel")){Query q=tasks.get(player.getUUID());if(q!=null && q.token.equals(token))cancel(player.getUUID());return;}
        if(action.equals("habitat_visit")) {
            Query q=tasks.get(player.getUUID());if(q==null || !q.token.equals(token) || !q.valid() || q.visit==null || !q.visit.ready || q.visit.waiting || q.visit.watching())return;
            q.mode=mode(n,q.mode);q.visit.visit(n.getBoolean("fallback"));q.stage=q.visit.state;publish(q);return;
        }
        if(action.equals("teleport")) {
            Query q=tasks.get(player.getUUID());if(q==null || !q.token.equals(token) || !q.valid() || !q.finished || !n.hasUUID("entity"))return;
            UUID id=n.getUUID("entity");q.teleportRequest=n.hasUUID("teleportRequest")?n.getUUID("teleportRequest"):null;
            if(!q.points.containsKey(id) || !com.chaosthedude.explorerscompass.unified.creature.CreatureConfig.GENERAL.allowTeleport.get()){teleportReceipt(q,false);return;}q.mode=mode(n,q.mode);q.teleport=id;q.fallback=n.getBoolean("fallback");
            Entity entity=q.level.getEntity(id);
            if(entity!=null && CreatureIndex.ready(q.level,entity.blockPosition())){teleport(q);return;}
            release(q);releaseFlights(q);q.reloading=true;q.stage="reload_entity";q.pending=new ChunkPos(entity==null?BlockPos.containing(q.points.get(id).x(),q.points.get(id).y(),q.points.get(id).z()):entity.blockPosition());q.waitStarted=System.nanoTime();publish(q);teleportReceipt(q,true);return;
        }
        if(!action.equals("search"))return;
        ResourceLocation type=parse(n.getString("type")),biome=parse(n.getString("biome")),dimension=parse(n.getString("dimension"));
        CreatureEntry entry=type==null?null:CreatureCatalog.get(player.getServer(),type);int rounds=n.getInt("rounds");
        if(entry==null || rounds<0 || rounds>MAX_ROUNDS || dimension!=null && !dimension.equals(player.serverLevel().dimension().location())
                || !entry.matches(player.serverLevel().dimension().location(),biome)) {
            var rejected=new CompoundTag();rejected.putString("action","results");rejected.putUUID("request",token);rejected.putString("state","rejected_dimension_or_filter");rejected.putBoolean("finished",true);rejected.put("points",new ListTag());send(player,rejected);return;
        }
        SearchReplacement.replace(CreatureIdentity.MOD_ID,player);cancel(player.getUUID());
        Query q=new Query(player,stack,token,type,biome,rounds);q.mode=mode(n,LandingMode.DEFAULT);tasks.put(player.getUUID(),q);beginCapture(q);publish(q);
    }
    private static LandingMode mode(CompoundTag tag,LandingMode fallback) {
        try {return LandingMode.valueOf(tag.getString("landingMode"));}
        catch(IllegalArgumentException invalid){return fallback;}
    }
    private static ResourceLocation parse(String value){return value.isBlank()?null:ResourceLocation.tryParse(value);}
    private static void beginCapture(Query q){q.capture=CreatureIndex.entities(q.level,q.type);q.captureAt=0;q.bootstrap=com.google.common.collect.ImmutableList.copyOf(q.level.getAllEntities()).iterator();q.grid=new MobGrid();q.sorted=null;}
    public static boolean active(){return !tasks.isEmpty();}
    public static int taskCount(){return tasks.size();}
    public static boolean step(long limit) {
        long quantum=Math.min(limit,System.nanoTime()+250_000L);CreatureIndex.drain(quantum);
        if(tasks.isEmpty())return false;var all=new ArrayList<>(tasks.values());Query q=all.get(Math.floorMod(cursor++,all.size()));
        if(!q.valid()){cancel(q.player.getUUID());return true;}
        try {
            boolean work=advance(q,quantum);long now=System.nanoTime();
            if(!q.finished && now-q.lastProgressPacket>=200_000_000L){q.lastProgressPacket=now;send(q.player,base(q));}
            return work;
        }catch(Exception error){CreatureIdentity.LOGGER.warn("生物搜索 {} 异常",q.token,error);done(q,"error");return true;}
    }
    private static boolean advance(Query q,long deadline) throws Exception {
        if(q.visit!=null){
            if(q.visit.watching()){
                for(var point:q.visit.observe(deadline))if(accepts(q,point))q.points.put(point.uuid(),point);
                if(!q.points.isEmpty()){q.source="natural";q.visit=null;done(q,"found");return true;}
            }else if(!q.visit.ready && !q.visit.empty){
                if(!acquire(q) || !ready(q))return false;
                q.visit.prepare(deadline);
            }
            q.stage=q.visit.state;
            if(q.visit.empty){
                boolean observed=q.visit.state.startsWith("natural_spawn_");
                if(observed){
                    q.habitatExcluded.add(q.visit.center.toLong());
                    CreatureIdentity.LOGGER.info("CREATURE habitat {} type={} state={} population={}/{}",q.token,q.type,q.visit.state,q.visit.population,q.visit.capacity);
                }
                release(q);q.pending=null;q.visit=null;
                if(observed){q.anchor=null;q.chunks=null;q.plan=null;q.planner=null;q.anchorOffset=q.checked;beginCapture(q);}
                return true;
            }
            else {if(q.visit.ready){release(q);if(!q.visit.waiting && !q.visit.watching())q.visit.visit(q.visit.confirmation);}return false;}
        }
        if(q.reloading){
            if(!acquire(q))return false;
            if(ready(q)){CreatureIndex.drain(deadline);q.reloading=false;teleport(q);release(q);return true;}
            if(System.nanoTime()-q.waitStarted>60_000_000_000L){q.reloading=false;release(q);q.stage="entity_load_timeout";publish(q);}return false;
        }
        if(q.finished) {
            if(q.ticket && (System.nanoTime()>q.resultLeaseUntil || VerificationSlots.queued()>0))release(q);
            if(!q.flights.isEmpty() && (System.nanoTime()>q.resultLeaseUntil || VerificationSlots.queued()>0))releaseFlights(q);
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
            while(q.captureAt<q.capture.size() && System.nanoTime()<deadline){Entity e=q.capture.get(q.captureAt++);capture(q,e);}
            if(q.captureAt<q.capture.size())return true;
            // 补扫实际已加载实体，避免加入事件队列尚未排空时遗漏；不在后台访问 Entity。
            while(q.bootstrap!=null && q.bootstrap.hasNext() && System.nanoTime()<deadline){
                Entity e=q.bootstrap.next();if(!q.type.equals(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()))){
                    if(e instanceof net.minecraft.world.entity.LivingEntity && q.habitatHints.size()<32){var hint=CreatureIndex.point(q.level,e);if(hint!=null && q.anchorBiomes.contains(hint.biome()) && q.hintChunks.add(new ChunkPos(e.blockPosition()).toLong()))q.habitatHints.addLast(e.blockPosition().immutable());}
                    continue;
                }
                capture(q,e);
            }
            if(q.bootstrap!=null && q.bootstrap.hasNext())return true;q.bootstrap=null;
            if(q.sorted==null){var snapshot=q.grid.snapshot();int ox=q.origin.getX(),oz=q.origin.getZ(),radius=q.radius;try{q.sorted=SearchPool.executor().submit(()->MobGrid.nearest(snapshot,ox,oz,radius));}catch(RejectedExecutionException busy){return false;}}
            if(!q.sorted.isDone())return false;
            for(var predicted:q.sorted.get()){var actual=CreatureIndex.point(q.level,q.level.getEntity(predicted.uuid()));if(accepts(q,actual))q.points.put(actual.uuid(),actual);}
            q.capture=null;q.sorted=null;
            if(!q.points.isEmpty()){done(q,"found");return true;}
            if(q.rounds==0){done(q,"not_found");return true;}
        }
        if(q.boundary!=null || !q.deferred.isEmpty()) {
            if(q.boundary==null){q.boundary=q.deferred.removeFirst();q.pending=new ChunkPos(q.boundary.blockPosition());q.waitStarted=System.nanoTime();q.stage="verifying_loaded";}
            if(!q.boundary.isAlive() || q.boundary.isRemoved() || q.boundary.level()!=q.level){q.boundary=null;release(q);q.pending=null;return true;}
            if(!acquire(q))return false;
            if(!ready(q)){if(System.nanoTime()-q.waitStarted<60_000_000_000L)return false;q.boundary=null;release(q);q.pending=null;return true;}
            var actual=CreatureIndex.point(q.level,q.boundary);q.boundary=null;
            if(accepts(q,actual)){q.points.put(actual.uuid(),actual);done(q,"found");return true;}
            release(q);q.pending=null;return true;
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
        if(q.visitCandidate!=null && q.flights.isEmpty()){
            q.pending=q.visitCandidate;q.visitCandidate=null;q.guideChecks++;
            q.visit=new HabitatVisit(q.player,q.stack,q.token,q.type,q.anchorBiomes,q.pending,()->tasks.get(q.player.getUUID())==q && q.valid(),point->accepts(q,point),()->q.mode);
            q.stage="checking_habitat";return true;
        }
        if(q.checked>=q.rounds*ROUND_CHUNKS && q.flights.isEmpty()){done(q,"not_found");return true;}
        if(q.anchor==null){
            while(!q.habitatHints.isEmpty() && q.anchor==null){var hint=q.habitatHints.removeFirst();if(!excludedHabitats(q).contains(new ChunkPos(hint).toLong()))q.anchor=hint;}
            if(q.anchor==null && q.anchorJob==null && !q.anchorBiomes.isEmpty()){
                long remaining=q.settings.sampleLimit()-q.anchorSamples;
                if(remaining<=0){done(q,"biome_sample_limit");return true;}
                q.anchorBase=q.anchorSamples;
                q.anchorJob=BiomeQueryFactory.habitats(q.level,q.origin,q.anchorBiomes,excludedHabitats(q),q.radius,remaining,q.settings.asynchronous(),q.settings);
                q.anchorJob.prime();q.lastProgress=System.nanoTime();
            }
            if(q.anchor==null && q.anchorJob!=null){
                q.stage="locating_biome";boolean work;
                try{work=q.anchorJob.step();}catch(Exception error){
                    if(!q.anchorJob.asynchronous())throw error;
                    CreatureIdentity.LOGGER.warn("生物适生区后台查询失败，保留完整来源改为兼容查询",error);
                    q.anchorBase=q.anchorSamples;q.anchorJob.cancel();
                    q.anchorJob=BiomeQueryFactory.habitats(q.level,q.origin,q.anchorBiomes,excludedHabitats(q),q.radius,Math.max(0,q.settings.sampleLimit()-q.anchorBase),false,q.settings).asFallback();return true;
                }
                long samples=q.anchorBase+q.anchorJob.samples();q.anchorLimit=q.anchorJob.sampleLimit();q.anchorMode=q.anchorJob.mode();q.anchorStage=q.anchorJob.stage();
                if(samples!=q.anchorSamples){q.lastProgress=System.nanoTime();q.anchorSamples=samples;}
                var candidate=q.anchorJob.nextCandidate();
                if(candidate!=null){if(!excludedHabitats(q).contains(new ChunkPos(candidate.position()).toLong()))q.anchor=candidate.position();return true;}
                if(q.anchorJob.done()){done(q,"biome_"+q.anchorJob.stage());return true;}
                if(System.nanoTime()-q.lastProgress>40_000_000_000L){done(q,"biome_stalled");return true;}
                return work;
            }
            if(q.anchor==null){
                if(q.rules.feature()){done(q,"biome_rules_unavailable");return true;}
                q.anchor=q.origin;
            }
        }
        if(q.chunks==null){
            q.stage="ranking_chunks";
            if(q.planner==null)q.planner=CreatureChunkPlanner.prepare(q.level,q.anchor,Math.min(ROUND_CHUNKS,q.rounds*ROUND_CHUNKS-q.checked),excludedHabitats(q),q.origin,q.radius,q.type,q.anchorBiomes,q.settings);
            if(q.planner.asynchronous()){
                if(q.plan==null){try{q.plan=SearchPool.executor().submit(()->q.planner.step(4_000_000L));}catch(RejectedExecutionException busy){return false;}return true;}
                if(!q.plan.isDone())return false;q.plan.get();q.plan=null;
            }else q.planner.step(Math.max(1,deadline-System.nanoTime()));
            if(!q.planner.done())return true;
            q.chunks=q.planner.result();q.plannedAt=0;q.patchKind=q.planner.kind();
            CreatureIdentity.LOGGER.info("CREATURE_PATCH {} type={} kind={} centers={}",q.token,q.type,q.patchKind,q.chunks.size());publish(q);
        }
        return neighborStep(q,deadline);
    }
    private static boolean neighborStep(Query q,long deadline){
        boolean progress=false;q.stage="loading_chunks";
        for(var work:List.copyOf(q.flights)){
            if(!work.ticket){if(!VerificationSlots.acquire(work))continue;q.level.getChunkSource().addRegionTicket(TICKET,work.center,1,work.ticketId);work.ticket=true;work.waiting=System.nanoTime();progress=true;}
            if(!CreaturePreExplore.ready(q.level,work.center)){
                if(System.nanoTime()-work.waiting>60_000_000_000L){done(q,"chunk_timeout");return true;}continue;
            }
            if(work.entities==null){
                work.snapshots=new HashMap<>();for(int x=work.center.x-1;x<=work.center.x+1;x++)for(int z=work.center.z-1;z<=work.center.z+1;z++)work.snapshots.put(new ChunkPos(x,z),new ArrayList<>());
                var box=new net.minecraft.world.phys.AABB(work.center.getMinBlockX()-16,q.level.getMinBuildHeight(),work.center.getMinBlockZ()-16,work.center.getMaxBlockX()+17,q.level.getMaxBuildHeight(),work.center.getMaxBlockZ()+17);
                work.entities=q.level.getEntities((Entity)null,box,e->!(e instanceof net.minecraft.world.entity.player.Player));
            }
            while(work.at<work.entities.size() && System.nanoTime()<deadline){
                Entity e=work.entities.get(work.at);var candidateType=net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
                // 新生成实体的区段先出现，UUID可访问索引可能在下一tick才接管；不能提前报告成功或丢掉它。
                if(e.isAlive() && !e.isRemoved() && e.level()==q.level && q.type.equals(candidateType) && q.level.getEntity(e.getUUID())!=e){
                    q.stage="verifying_loaded";if(System.nanoTime()-work.waiting>60_000_000_000L){done(q,"entity_load_timeout");return true;}return false;
                }
                work.at++;if(!e.isAlive() || e.isRemoved() || e.level()!=q.level)continue;
                CreatureIndex.record(e);var type=net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType());var chunk=new ChunkPos(e.blockPosition());
                var rows=work.snapshots.get(chunk);if(rows!=null && type!=null)rows.add(new SavedCreatureIndex.Point(e.getUUID(),type,e.getX(),e.getY(),e.getZ(),chunk));
                if(q.type.equals(type)){var p=CreatureIndex.point(q.level,e);if(accepts(q,p))q.points.put(p.uuid(),p);}
            }
            if(work.at<work.entities.size())return true;
            for(var row:work.snapshots.entrySet()){SavedCreatureIndex.seed(q.level,row.getKey(),row.getValue());q.checkedChunks.add(row.getKey().toLong());}
            q.checked++;q.round=(q.checked+ROUND_CHUNKS-1)/ROUND_CHUNKS;q.lastProgress=System.nanoTime();progress=true;
            if(!q.points.isEmpty()){
                // AABB可能碰到邻窗中的大型实体；先将票据接到实际目标，再释放其它窗口。
                MobPoint nearest=nearest(q);ChunkPos actual=new ChunkPos(BlockPos.containing(nearest.x(),nearest.y(),nearest.z()));
                if(!actual.equals(work.retainedCenter)){
                    q.level.getChunkSource().addRegionTicket(TICKET,actual,1,work.ticketId);
                    q.level.getChunkSource().removeRegionTicket(TICKET,work.retainedCenter,1,work.ticketId);
                    CreatureIdentity.LOGGER.info("CREATURE_PIN {} source={} actual={} uuid={}",q.token,work.retainedCenter,actual,nearest.uuid());work.retainedCenter=actual;
                }
                for(var other:List.copyOf(q.flights))if(other!=work){release(q,other);q.flights.remove(other);}
                q.source="neighbor";done(q,"found");return true;
            }
            if(q.rules.natural() && q.guideChecks<q.rounds && q.visitCandidate==null && com.chaosthedude.explorerscompass.unified.creature.CreatureConfig.GENERAL.allowTeleport.get()){
                var type=net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(q.type);
                if(type!=null && (type.getCategory()!=net.minecraft.world.entity.MobCategory.CREATURE || q.checked>=q.rounds*ROUND_CHUNKS))q.visitCandidate=work.center;
            }
            release(q,work);q.flights.remove(work);
        }
        if(q.visitCandidate!=null)return progress;
        while(q.flights.size()<2 && q.plannedAt<q.chunks.size() && q.checked+q.flights.size()<q.rounds*ROUND_CHUNKS){
            var next=q.chunks.get(q.plannedAt++);if(q.checkedChunks.contains(next.toLong()))continue;
            if(q.flights.stream().anyMatch(w->w.center.equals(next)))continue;
            q.flights.add(new ChunkWork(next));progress=true;
        }
        if(q.flights.isEmpty() && q.plannedAt>=q.chunks.size()){
            q.habitatExcluded.add(new ChunkPos(q.anchor).toLong());
            if(q.patchKind.equals("small"))q.habitatExcluded.addAll(q.planner.covered());
            if(q.anchorJob!=null)q.anchorJob.excludeCenters(excludedHabitats(q));
            q.anchor=null;q.chunks=null;q.planner=null;q.plan=null;q.anchorOffset=q.checked;return true;
        }
        return progress;
    }
    private static void release(Query q,ChunkWork work){if(work.ticket){q.level.getChunkSource().removeRegionTicket(TICKET,work.retainedCenter,1,work.ticketId);work.ticket=false;}VerificationSlots.release(work);}
    private static void releaseFlights(Query q){for(var work:q.flights)release(q,work);q.flights.clear();}
    private static Set<Long> excludedHabitats(Query q){var excluded=new HashSet<>(q.checkedChunks);excluded.addAll(q.habitatExcluded);return Set.copyOf(excluded);}
    private static void capture(Query q,Entity e){
        MobPoint p=CreatureIndex.point(q.level,e);
        if(p!=null){if(q.biome==null || q.biome.equals(p.biome()))q.grid.put(p);}
        else if(e.isAlive() && !e.isRemoved() && e.level()==q.level && e.distanceToSqr(q.origin.getX(),e.getY(),q.origin.getZ())<=(double)q.radius*q.radius && !q.deferred.contains(e))q.deferred.addLast(e);
    }
    private static boolean accepts(Query q,MobPoint point){return point!=null && point.type().equals(q.type) && (q.biome==null || q.biome.equals(point.biome())) && point.distance(q.origin.getX(),q.origin.getZ())<=(double)q.radius*q.radius;}
    private static void rejectSaved(Query q){q.storedRejected++;q.saved.rejected();release(q);q.pending=null;q.savedCandidate=null;q.stage="scanning_saved";}
    public static List<ChunkPos> chunkPlan(BlockPos anchor,int count) {
        return chunkPlan(anchor,count,Set.of(),anchor,2048);
    }
    public static List<ChunkPos> chunkPlan(BlockPos anchor,int count,Set<Long> excluded,BlockPos origin,int radius) {
        var geometry=new RectFrontier.Geometry(anchor.getX(),anchor.getZ(),16,8,8,8,8);
        var frontier=new RectFrontier(geometry,geometry.domain(2048),8,2048L*2048);var chunks=new ArrayList<ChunkPos>(count);
        while(chunks.size()<count && frontier.available(Long.MAX_VALUE)){long p=frontier.next();int x=(int)(p>>32),z=(int)p;
            long dx=(long)x*16+8-origin.getX(),dz=(long)z*16+8-origin.getZ();
            if(frontier.inRange(x,z) && !excluded.contains(ChunkPos.asLong(x,z)) && dx*dx+dz*dz<=(long)radius*radius)chunks.add(new ChunkPos(x,z));}
        return List.copyOf(chunks);
    }
    private static boolean acquire(Query q){if(q.ticket)return true;if(!VerificationSlots.acquire(q))return false;q.level.getChunkSource().addRegionTicket(TICKET,q.pending,1,q.token);q.ticket=true;return true;}
    private static boolean ready(Query q){for(int x=q.pending.x-1;x<=q.pending.x+1;x++)for(int z=q.pending.z-1;z<=q.pending.z+1;z++)if(q.level.getChunkSource().getChunkNow(x,z)==null || !q.level.areEntitiesLoaded(ChunkPos.asLong(x,z)))return false;return true;}
    private static void release(Query q){if(q.ticket){q.level.getChunkSource().removeRegionTicket(TICKET,q.pending,1,q.token);q.ticket=false;}VerificationSlots.release(q);}
    private static void done(Query q,String stage){
        if(stage.equals("found") && q.ticket && !q.points.isEmpty()){
            var point=nearest(q);var actual=new ChunkPos(BlockPos.containing(point.x(),point.y(),point.z()));
            if(!actual.equals(q.pending)){q.level.getChunkSource().addRegionTicket(TICKET,actual,1,q.token);q.level.getChunkSource().removeRegionTicket(TICKET,q.pending,1,q.token);q.pending=actual;}
        }
        if(stage.equals("found"))q.resultLeaseUntil=System.nanoTime()+60_000_000_000L;else {release(q);releaseFlights(q);}
        if(q.anchorJob!=null)q.anchorJob.cancel();if(q.saved!=null)q.saved.close();q.finished=true;q.stage=stage;publish(q);if(!q.terminal){q.terminal=true;CreatureIdentity.LOGGER.info("CREATURE {} type={} biome={} state={} rounds={}/{} chunks={} count={} source={} stored={}/{} verified={} rejected={} elapsed_ms={}",q.token,q.type,q.biome,stage,q.round,q.rounds,q.checked,q.points.size(),q.source,q.storedScanned,q.storedTotal,q.storedVerified,q.storedRejected,(System.nanoTime()-q.started)/1e6);}}
    private static MobPoint nearest(Query q){return q.points.values().stream().min(Comparator.<MobPoint>comparingDouble(p->p.distance(q.origin.getX(),q.origin.getZ())).thenComparing(p->p.uuid().toString())).orElseThrow();}
    private static void teleport(Query q) {
        UUID id=q.teleport;var e=q.level.getEntity(id);var point=CreatureIndex.point(q.level,e);
        if(point==null || !point.type().equals(q.type) || q.biome!=null && !q.biome.equals(point.biome())){q.points.remove(id);q.stage="entity_gone";publish(q);teleportReceipt(q,false);return;}
        q.points.put(id,point);
        boolean accepted=TeleportService.requestLocator(q.player,q.stack,q.token,new LocatorTarget(){
            public boolean allowed(){return CreatureConfig.GENERAL.allowTeleport.get();}
            public LandingMode mode(){return q.mode;}
            public Object identity(){return id;}
            public boolean creature(){return true;}
            public List<Vec3> relaxedCandidates(ServerPlayer player){return CreatureLanding.candidates(player,e);}
            public BlockPos position(){return e.blockPosition();}
            public boolean valid(){return tasks.get(q.player.getUUID())==q && q.valid() && q.level.getEntity(id)==e && e.isAlive() && !e.isRemoved() && (q.biome==null || !CreatureIndex.ready(q.level,e.blockPosition()) || q.level.getBiome(e.blockPosition()).unwrapKey().map(k->k.location().equals(q.biome)).orElse(false));}
            public boolean accepts(Vec3 landing){double reach=Math.max(e.getBbWidth(),12)+2;return valid() && CreatureIndex.ready(q.level,e.blockPosition()) && e.distanceToSqr(landing)<reach*reach && !e.getBoundingBox().intersects(q.player.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(landing));}
            public void state(String phase,boolean confirmation){q.stage=phase.equals("moved") || phase.endsWith("_moved")?"teleport_moved":"teleport_"+phase;q.confirmation=confirmation;send(q.player,base(q));}
            public void moved(){q.stage="teleport_moved";q.confirmation=false;publish(q);}
        },q.fallback);
        teleportReceipt(q,accepted);
    }
    private static void teleportReceipt(Query q,boolean accepted){var n=base(q);if(q.teleportRequest!=null)n.putUUID("teleportRequest",q.teleportRequest);n.putBoolean("teleportAccepted",accepted);send(q.player,n);if(!accepted)q.player.displayClientMessage(net.minecraft.network.chat.Component.literal("生物传送请求未被接受，请检查目标和传送总开关。"),false);}
    private static void measure(Query q){long now=System.nanoTime();q.phaseNanos.merge(q.measuredStage,now-q.stageSince,Long::sum);q.stageSince=now;q.measuredStage=q.stage;}
    private static void phaseTimes(Query q,CompoundTag n){measure(q);n.putLong("anchorMillis",q.phaseNanos.getOrDefault("locating_biome",0L)/1_000_000L);n.putLong("storageMillis",q.phaseNanos.getOrDefault("scanning_saved",0L)/1_000_000L);long wait=0;for(String phase:List.of("loading_chunks","verifying_saved","verifying_loaded","reload_entity"))wait+=q.phaseNanos.getOrDefault(phase,0L);n.putLong("chunkWaitMillis",wait/1_000_000L);}
    private static CompoundTag base(Query q){var n=new CompoundTag();phaseTimes(q,n);n.putBoolean("habitatReady",q.visit!=null && q.visit.ready && !q.visit.waiting && !q.visit.watching());n.putLong("naturalWaitMillis",q.visit==null?0:q.visit.waitedMillis());n.putInt("spawnPopulation",q.visit==null?0:q.visit.population);n.putInt("spawnCapacity",q.visit==null?0:q.visit.capacity);n.putString("action","results");n.putUUID("request",q.token);n.putString("state",q.stage);n.putInt("round",q.round);n.putInt("rounds",q.rounds);n.putInt("chunks",q.checked);n.putBoolean("finished",q.finished);n.putBoolean("confirmation",q.visit==null?q.confirmation:q.visit.confirmation);n.putLong("anchorSamples",q.anchorSamples);n.putLong("anchorLimit",q.anchorLimit);n.putString("anchorMode",q.anchorMode);n.putString("anchorStage",q.anchorStage);n.putInt("captured",q.captureAt);n.putInt("captureTotal",q.capture==null?q.captureAt:q.capture.size());n.putInt("chunksTotal",q.rounds*ROUND_CHUNKS);n.putLong("storedScanned",q.storedScanned);n.putLong("storedTotal",q.storedTotal);n.putInt("storedVerified",q.storedVerified);n.putInt("storedRejected",q.storedRejected);n.putString("source",q.source);n.putString("patchKind",q.patchKind);n.putInt("inFlight",q.flights.size());if(q.saved!=null){n.putInt("storedRegions",q.saved.regionsDone());n.putInt("storedRegionsTotal",q.saved.regionsTotal());}return n;}
    private static void publish(Query q){
        var values=q.points.values().stream().sorted(Comparator.<MobPoint>comparingDouble(p->p.distance(q.origin.getX(),q.origin.getZ())).thenComparing(p->p.uuid().toString())).toList();
        int parts=Math.max(1,(values.size()+255)/256);long revision=++q.resultRevision;
        for(int part=0;part<parts;part++) {var n=base(q);var rows=new ListTag();for(int i=part*256;i<Math.min(values.size(),(part+1)*256);i++)rows.add(values.get(i).encode());n.put("points",rows);n.putInt("part",part);n.putInt("parts",parts);n.putLong("revision",revision);send(q.player,n);}
    }
    private static void send(ServerPlayer p,CompoundTag n){CreatureNetwork.send(p,new CreatureUpdate(n));}
    public static void cancel(UUID player){Query q=tasks.remove(player);if(q==null)return;if(q.anchorJob!=null)q.anchorJob.cancel();if(q.sorted!=null)q.sorted.cancel(true);if(q.plan!=null)q.plan.cancel(true);if(q.saved!=null)q.saved.close();release(q);releaseFlights(q);TeleportService.cancel(player);q.stage="cancelled";q.finished=true;publish(q);}
    public static void clear(){CreaturePreExplore.clear();for(var id:List.copyOf(tasks.keySet()))cancel(id);CreatureIndex.clear();SavedCreatureIndex.clear();CreatureHabitats.clear();CreatureCatalog.clear();}
    public static void destroyed(ServerLevel level,UUID entity){SavedCreatureIndex.destroyed(level,entity);for(var q:tasks.values())if(q.level==level && q.points.remove(entity)!=null){q.lastUpdate=0;}}
    public static int tickets(){return tasks.values().stream().mapToInt(q->(q.ticket?1:0)+(int)q.flights.stream().filter(w->w.ticket).count()).sum();}
}
