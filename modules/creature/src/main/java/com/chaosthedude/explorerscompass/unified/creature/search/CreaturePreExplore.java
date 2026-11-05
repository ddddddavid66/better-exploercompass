package com.chaosthedude.explorerscompass.unified.creature.search;

import com.david.compass.runtime.game.search.SearchPool;
import com.david.compass.runtime.game.search.VerificationSlots;

import com.chaosthedude.explorerscompass.unified.creature.CreatureConfig;
import com.chaosthedude.explorerscompass.unified.creature.network.CreatureUpdate;
import com.chaosthedude.explorerscompass.unified.creature.network.CreatureNetwork;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

/** 全服务器一个有限任务；只请求正常生成，不调用刷怪或传送。 */
public final class CreaturePreExplore {
    private static final TicketType<UUID> TICKET=TicketType.create("unified_preexplore",Comparator.<UUID>naturalOrder());
    private static Task task;
    private static final class Task {
        final UUID id;final ServerPlayer player;final ServerLevel level;final ItemStack stack;final BlockPos origin;final int radius;
        final Set<Long> prepared=new HashSet<>(),dependencies=new HashSet<>();final Set<UUID> entities=new HashSet<>();
        final long started=System.nanoTime();Future<List<ChunkPos>> planning;List<ChunkPos> plan;int at,total;
        ChunkPos pending;boolean ticket,paused,slow,finished;int high,low;long waiting,lastPacket;double tickMillis;
        String state="planning";List<Entity> capture;int captureAt;Map<ChunkPos,List<SavedCreatureIndex.Point>> snapshots;
        Task(ServerPlayer p,UUID id,int radius){this.id=id;player=p;level=p.serverLevel();stack=CreatureSearch.held(p);origin=p.blockPosition().immutable();this.radius=radius;}
        boolean valid(){return player.isAlive() && !player.isRemoved() && player.serverLevel()==level && CreatureSearch.held(player)==stack && CreatureConfig.GENERAL.allowCreaturePreExplore.get();}
    }
    private CreaturePreExplore() {}
    public static void request(ServerPlayer p,CompoundTag n) {
        if(!n.hasUUID("request"))return;UUID receipt=n.getUUID("request");String action=n.getString("action");
        if(action.equals("preexplore_status")){send(p,receipt);return;}
        if(CreatureSearch.held(p).isEmpty())return;
        if(action.equals("preexplore_start")) {
            int radius=n.getInt("radius");
            if(!CreatureConfig.GENERAL.allowCreaturePreExplore.get() || !p.isAlive() || radius<128 || radius>1024 || radius%16!=0){reply(p,receipt,"rejected");return;}
            if(task!=null && !task.finished){if(task.player==p)send(p,receipt);else reply(p,receipt,"busy");return;}
            task=new Task(p,receipt,radius);send(p,receipt);return;
        }
        if(task==null || task.finished || task.player!=p || !n.hasUUID("task") || !task.id.equals(n.getUUID("task"))){send(p,receipt);return;}
        if(action.equals("preexplore_stop"))finish("stopped");
        else if(action.equals("preexplore_pause"))task.paused=true;
        else if(action.equals("preexplore_resume"))task.paused=false;
        send(p,receipt);
    }
    public static void maintain(MinecraftServer server) {
        if(task==null || task.finished)return;
        if(!task.valid()){finish("cancelled");return;}
        task.tickMillis=server.getAverageTickTime();
        task.high=task.tickMillis>40?task.high+1:0;task.low=task.tickMillis<30?task.low+1:0;
        if(task.high>=20)task.slow=true;if(task.low>=40)task.slow=false;
        if(System.nanoTime()-task.lastPacket>=200_000_000L){task.lastPacket=System.nanoTime();send(task.player,null);}
    }
    public static boolean step(long limit) {
        Task q=task;if(q==null || q.finished)return false;
        long deadline=Math.min(limit,System.nanoTime()+250_000L);
        try {
            if(q.planning==null && q.plan==null){
                var b=q.level.getWorldBorder();double minX=b.getMinX(),maxX=b.getMaxX(),minZ=b.getMinZ(),maxZ=b.getMaxZ();
                try{q.planning=SearchPool.executor().submit(()->plan(q.origin,q.radius,minX,maxX,minZ,maxZ));}catch(RejectedExecutionException busy){return false;}
                return true;
            }
            if(q.plan==null){if(!q.planning.isDone())return false;q.plan=q.planning.get();q.total=q.plan.size();q.state="preparing";return true;}
            if(q.pending!=null){
                if(q.ticket && q.capture==null && VerificationSlots.queued()>0){release(q);return false;}
                if(!q.ticket){if(!VerificationSlots.acquireBackground(q))return false;q.level.getChunkSource().addRegionTicket(TICKET,q.pending,1,q.id);q.ticket=true;q.waiting=System.nanoTime();}
                if(!ready(q)){q.state="waiting_chunks";if(System.nanoTime()-q.waiting>60_000_000_000L)finish("chunk_timeout");return false;}
                if(q.capture==null){
                    q.state="indexing";q.snapshots=new HashMap<>();
                    for(int x=q.pending.x-1;x<=q.pending.x+1;x++)for(int z=q.pending.z-1;z<=q.pending.z+1;z++)q.snapshots.put(new ChunkPos(x,z),new ArrayList<>());
                    q.capture=q.level.getEntities((Entity)null,new AABB(q.pending.getMinBlockX()-16,q.level.getMinBuildHeight(),q.pending.getMinBlockZ()-16,q.pending.getMaxBlockX()+17,q.level.getMaxBuildHeight(),q.pending.getMaxBlockZ()+17),e->!(e instanceof net.minecraft.world.entity.player.Player));
                }
                while(q.captureAt<q.capture.size() && System.nanoTime()<deadline){
                    Entity e=q.capture.get(q.captureAt++);var chunk=new ChunkPos(e.blockPosition());var values=q.snapshots.get(chunk);
                    if(values==null || !e.isAlive() || e.isRemoved() || e.level()!=q.level)continue;
                    var type=ForgeRegistries.ENTITY_TYPES.getKey(e.getType());if(type==null)continue;
                    if(e instanceof LivingEntity || com.chaosthedude.explorerscompass.unified.creature.catalog.CreatureCatalog.get(q.level.getServer(),type)!=null)q.entities.add(e.getUUID());CreatureIndex.record(e);
                    values.add(new SavedCreatureIndex.Point(e.getUUID(),type,e.getX(),e.getY(),e.getZ(),chunk));
                }
                if(q.captureAt<q.capture.size())return true;
                for(var row:q.snapshots.entrySet()){
                    SavedCreatureIndex.seed(q.level,row.getKey(),row.getValue());long key=row.getKey().toLong();
                    long dx=(long)row.getKey().getMiddleBlockX()-q.origin.getX(),dz=(long)row.getKey().getMiddleBlockZ()-q.origin.getZ();
                    if(dx*dx+dz*dz<=(long)q.radius*q.radius && q.level.getWorldBorder().isWithinBounds(row.getKey().getMiddleBlockPosition(0)))q.prepared.add(key);
                    else q.dependencies.add(key);
                }
                release(q);q.pending=null;q.capture=null;q.captureAt=0;q.snapshots=null;return true;
            }
            if(q.paused || q.slow){q.state=q.paused?"paused":"backpressure";return false;}
            while(q.at<q.plan.size() && q.prepared.contains(q.plan.get(q.at).toLong()))q.at++;
            if(q.at>=q.plan.size()){finish("complete");return true;}
            if(VerificationSlots.queued()>0)return false;
            q.pending=q.plan.get(q.at++);q.waiting=System.nanoTime();q.state="waiting_slots";return true;
        }catch(Exception e){com.chaosthedude.explorerscompass.unified.creature.CreatureIdentity.LOGGER.warn("区块预探索失败",e);finish("error");return true;}
    }
    public static List<ChunkPos> plan(BlockPos origin,int radius,double minX,double maxX,double minZ,double maxZ){
        var result=new ArrayList<ChunkPos>();
        for(int x=(origin.getX()-radius)>>4;x<=(origin.getX()+radius)>>4;x++)for(int z=(origin.getZ()-radius)>>4;z<=(origin.getZ()+radius)>>4;z++){
            long bx=(long)x*16+8,bz=(long)z*16+8,dx=bx-origin.getX(),dz=bz-origin.getZ();
            if(dx*dx+dz*dz<=(long)radius*radius && bx>=minX && bx<maxX && bz>=minZ && bz<maxZ)result.add(new ChunkPos(x,z));
        }
        result.sort(Comparator.<ChunkPos>comparingLong(c->{long x=(long)c.getMiddleBlockX()-origin.getX(),z=(long)c.getMiddleBlockZ()-origin.getZ();return x*x+z*z;}).thenComparingInt(c->c.x).thenComparingInt(c->c.z));return List.copyOf(result);
    }
    private static boolean ready(Task q){return ready(q.level,q.pending);}
    public static boolean ready(ServerLevel level,ChunkPos center){for(int x=center.x-1;x<=center.x+1;x++)for(int z=center.z-1;z<=center.z+1;z++)if(level.getChunkSource().getChunkNow(x,z)==null || !level.areEntitiesLoaded(ChunkPos.asLong(x,z)))return false;return true;}
    private static void release(Task q){if(q.ticket){q.level.getChunkSource().removeRegionTicket(TICKET,q.pending,1,q.id);q.ticket=false;}VerificationSlots.release(q);}
    private static void finish(String state){if(task==null)return;release(task);if(task.planning!=null)task.planning.cancel(true);task.capture=null;task.snapshots=null;task.plan=null;task.planning=null;task.finished=true;task.state=state;send(task.player,null);}
    public static void cancel(UUID owner){if(task!=null && task.player.getUUID().equals(owner) && !task.finished)finish("cancelled");}
    public static void clear(){if(task!=null && !task.finished)finish("cancelled");task=null;}
    public static int tickets(){return task!=null && task.ticket?1:0;}
    private static void reply(ServerPlayer p,UUID receipt,String state){var n=new CompoundTag();n.putString("action","preexplore_status");n.putUUID("request",receipt);n.putUUID("receipt",receipt);n.putString("state",state);n.putBoolean("finished",true);CreatureNetwork.send(p,new CreatureUpdate(n));}
    private static void send(ServerPlayer p,UUID receipt){
        if(task==null || task.player!=p){if(receipt!=null)reply(p,receipt,task!=null && !task.finished?"busy":"idle");return;}
        var q=task;var n=new CompoundTag();n.putString("action","preexplore_status");n.putUUID("request",q.id);if(receipt!=null)n.putUUID("receipt",receipt);
        n.putString("state",q.state);n.putBoolean("paused",q.paused);n.putBoolean("finished",q.finished);n.putString("dimension",q.level.dimension().location().toString());n.putInt("radius",q.radius);
        n.putInt("prepared",q.prepared.size());n.putInt("total",q.total);n.putInt("dependencies",q.dependencies.size());n.putInt("entities",q.entities.size());n.putLong("elapsedMillis",(System.nanoTime()-q.started)/1_000_000L);n.putDouble("tickMillis",q.tickMillis);
        CreatureNetwork.send(p,new CreatureUpdate(n));
    }
}
