package com.david.compass.runtime.game.search;

import com.david.compass.runtime.game.data.LandingColumns;
import com.david.compass.runtime.game.data.LandingMode;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** 群系/结构统一传送。所有世界读取都在主线程且区块已就绪。 */
public final class TeleportService {
    public enum State { PREPARING, NEEDS_CONFIRMATION, FALLBACK_PREPARING, FAILED_RETRYABLE, SUCCEEDED, CANCELLED }
    public record RecordedTarget(UUID token, ResourceLocation key, boolean biome, int x, Integer y, int z) {}
    /** 产品拥有目标读写、权限及事件；核心只处理实际世界与票据。 */
    public interface RecordedCompass {
        default RecordedTarget read(ServerPlayer player,ItemStack stack){return null;}
        default PortalHint portal(ItemStack stack){return null;}
        default boolean portalRequired(ResourceLocation key){return false;}
        default ResourceLocation biome(ItemStack stack){return null;}
        default UUID token(ItemStack stack){return null;}
        default boolean allowedReturn(ServerPlayer player,ItemStack stack){return false;}
        default void bindLegacy(ServerPlayer player,ItemStack stack,UUID token){}
        default void status(ServerPlayer player,UUID token,State state,long revision,String reason){}
        default void preparationFailed(ServerPlayer player,ResourceLocation key,int x,int y,int z,String reason){}
        default void moved(ServerPlayer player,boolean biome,ResourceLocation key,ResourceLocation dimension,Vec3 point){}
    }
    private static final org.apache.logging.log4j.Logger LOGGER=org.apache.logging.log4j.LogManager.getLogger("compass_runtime");
    private static volatile RecordedCompass recorded=new RecordedCompass(){};
    public static void bindRecordedCompass(RecordedCompass adapter){recorded=Objects.requireNonNull(adapter);}

    public enum TravelResult { MOVED, NO_POSITION, BUDGET_EXHAUSTED, TIMED_OUT, CANCELLED, ERROR }
    public record TravelOutcome(TravelResult result,List<BlockPos> provenUnusable) {}
    private static final TicketType<UUID> PREPARE=TicketType.create("compassfilters_landing",Comparator.<UUID>naturalOrder());
    private static final Map<UUID,Task> tasks=new LinkedHashMap<>();
    private static final Map<UUID,LinkedHashMap<UUID,Long>> requests=new HashMap<>();
    private static final LandingFailures preparationFailures=new LandingFailures();
    private static final Map<UUID,LandingMode> modes=new HashMap<>();
    private static long revision;
    private static int cursor;
    private static List<Task> foregroundTasks=List.of(),backgroundTasks=List.of();
    private static final long CONFIRM_NANOS=30_000_000_000L;
    private static final long MANUAL_STALL=10_000_000_000L, MANUAL_TOTAL=30_000_000_000L;
    private static final long WAIT_NANOS=5_000_000_000L, REQUEST_NANOS=10_000_000_000L;
    private static final class Task {
        final ServerPlayer player; final ServerLevel level; final ItemStack stack;
        final UUID token,ticketId=UUID.randomUUID(); final ResourceLocation key;
        final boolean biome,legacyHeight,treasure,portalRequired;
        final PortalHint portalHint; PortalLanding portal; LandingMode mode; int x,z,sourceY;
        BlockPos origin; ChunkPos center;
        final int[] strictHeights=new int[LandingColumns.STRICT.size()], relaxedHeights=new int[LandingColumns.RELAXED.size()];
        int[] heights=strictHeights;
        int expires,column;
        final int[] surfaceHeights=new int[LandingColumns.RELAXED.size()];
        long requestDeadline,centerDeadline,checkNanos,attemptStarted,waitStarted,waitNanos; int lastCenterTick=-1,handoffUntil;
        String phase="";
        boolean heightResolved,alternateSurface,quickChecked; boolean ticket,clicked,fallback,waiting;
        long chunksReady,confirmationUntil;
        Vec3 landing,strictLanding,relaxedLanding,travelAlternate;
        boolean travel, returning,budgetPaused; float returnYaw, returnPitch;
        java.util.function.Consumer<TravelResult> completion;
        java.util.function.Consumer<TravelOutcome> detailedCompletion;
        LocatorTarget external;
        AetherHeightProbe heightProbe;
        long progressAt;int readyCount;
        long queueNanos,queueStarted,manualProgress;
        long preparedAt=System.nanoTime(),landingReadyAt;
        Integer chestY;
        boolean slotHeld;
        final GenerationStamp generation;

        int readyMask, blockedTick=-1;
        final LinkedHashSet<ChunkPos> manualChunks=new LinkedHashSet<>();
        Task(ServerPlayer player,ItemStack stack,UUID token) { this(player,stack,token,null); }
        Task(ServerPlayer player,ItemStack stack,UUID token,BlockPos returnPoint) {
            this(player,stack,token,returnPoint,null);
        }
        Task(ServerPlayer player,ItemStack stack,UUID token,BlockPos returnPoint,LocatorTarget external) {
            this.external=external;generation=GenerationStamp.capture(player.serverLevel());
            returning=returnPoint!=null;
            this.player=player;level=player.serverLevel();this.stack=stack;this.token=token;
            var target=returning?null:Objects.requireNonNull(recorded.read(player,stack));
            biome=target!=null && target.biome(); key=target==null?null:target.key();
            x=target==null?returnPoint.getX():target.x();z=target==null?returnPoint.getZ():target.z();
            legacyHeight=target!=null && target.y()==null;
            sourceY=target==null?returnPoint.getY():legacyHeight?0:target.y();
            treasure=!returning && !biome && key!=null && key.toString().equals("minecraft:buried_treasure");
            portalRequired=!returning && !biome && key!=null && recorded.portalRequired(key);
            portalHint=portalRequired?recorded.portal(stack):null;
            portal=portalHint==null?null:new PortalLanding(portalHint);
            mode=external==null?modes.getOrDefault(player.getUUID(),LandingMode.DEFAULT):external.mode();fallback=mode==LandingMode.RELAXED_ONLY;
            heights=fallback?relaxedHeights:strictHeights;
            origin=new BlockPos(x,legacyHeight?player.blockPosition().getY():sourceY,z);center=new ChunkPos(origin);
            if(portalHint!=null)center=new ChunkPos(portalHint.frames().isEmpty()
                    ?portalHint.room().getCenter():portalHint.frames().get(0));
            expires=player.getServer().getTickCount()+100;
        }
        boolean valid() {
            if(!generation.current(level))return false;
            if(external!=null)return player.serverLevel()==level && external.valid() && external.allowed();
            if(returning)return allowedTravel(player,stack) && player.serverLevel()==level;
            var current=recorded.read(player,stack);
            return player.serverLevel()==level && current!=null && token.equals(current.token()) && key.equals(current.key())
                    && biome==current.biome() && x==current.x() && z==current.z() && (legacyHeight || Objects.equals(sourceY,current.y()));
        }
    }
    private TeleportService() {}
    /** 生物模块复用同一落点扫描、票据、二次确认与最终保护，不复制传送算法。 */
    public static boolean requestLocator(ServerPlayer player,ItemStack stack,UUID token,LocatorTarget target,boolean fallback) {
        if(!target.valid() || !target.allowed())return false;
        Task task=tasks.get(player.getUUID());
        if(task==null || task.external==null || !task.token.equals(token) || !task.external.identity().equals(target.identity()) || task.mode!=target.mode() || !task.valid() || task.handoffUntil>0) {
            cancel(player.getUUID());task=new Task(player,stack,token,target.position(),target);
            task.returnYaw=player.getYRot();task.returnPitch=player.getXRot();tasks.put(player.getUUID(),task);
        }
        task.external=target;
        if(task.clicked)return true;
        if(task.mode==LandingMode.SAFE_ONLY)fallback=false;
        if(task.mode==LandingMode.RELAXED_ONLY)fallback=true;
        if(task.mode==LandingMode.DEFAULT && task.waiting && System.nanoTime()>task.confirmationUntil){task.waiting=false;fallback=false;}
        if(task.mode==LandingMode.DEFAULT && fallback && !task.waiting)return false;
        if(task.mode==LandingMode.DEFAULT && task.waiting && !fallback){task.external.state("confirm",true);return true;}
        task.clicked=true;task.waiting=false;task.fallback=fallback;task.phase="";task.column=0;
        task.heights=fallback?task.relaxedHeights:task.strictHeights;task.landing=fallback?task.relaxedLanding:task.strictLanding;
        long now=System.nanoTime();task.attemptStarted=now;task.waitStarted=now;task.checkNanos=0;task.waitNanos=0;
        task.progressAt=now;task.requestDeadline=now+(target.creature()?60_000_000_000L:REQUEST_NANOS);task.centerDeadline=task.requestDeadline;task.expires=player.getServer().getTickCount()+1200;
        phase(task,"waiting_chunks");return true;
    }
    public static LandingMode mode(UUID player) {return modes.getOrDefault(player,LandingMode.DEFAULT);}
    public static void setMode(UUID player,LandingMode mode) {
        if(modes.put(player,mode)==mode)return;
        Task task=tasks.get(player);if(task==null || task.external!=null || task.handoffUntil>0)return;
        // 不丢弃已就绪区块和候选，但旧的确认授权立即失效。
        task.mode=mode;
        if(task.portalHint!=null){task.portal=new PortalLanding(task.portalHint);task.strictLanding=null;task.relaxedLanding=null;}
        if(task.travel) {beginTravelStage(task,mode==LandingMode.RELAXED_ONLY);return;}
        task.waiting=false;task.clicked=false;task.fallback=mode==LandingMode.RELAXED_ONLY;
        task.heights=task.fallback?task.relaxedHeights:task.strictHeights;task.column=0;
        task.landing=task.fallback?task.relaxedLanding:task.strictLanding;
        task.expires=task.player.getServer().getTickCount()+100;
        task.requestDeadline=0;task.checkNanos=0;
        status(task,State.CANCELLED,"mode_changed");
    }
    public static boolean eligible(ServerPlayer player,ItemStack stack,UUID token) {
        var target=recorded.read(player,stack);
        return token!=null && target!=null && token.equals(target.token());
    }
    public static void prepare(ServerPlayer player,ItemStack stack,UUID token) {
        if(!eligible(player,stack,token))return;
        preparationFailures.target(player.getUUID(),player.serverLevel(),token);
        Task existing=tasks.get(player.getUUID());
        if(existing!=null && existing.stack==stack && existing.token.equals(token) && existing.valid() && existing.handoffUntil==0)return;
        cancel(player.getUUID());Task task=new Task(player,stack,token);tasks.put(player.getUUID(),task);
    }
    /** 只用于探险者结构完成交接；娱乐路径必须先确定任务类型再申请票据。 */
    public static void prepareStructure(ServerPlayer player,ItemStack stack,UUID token){
        prepare(player,stack,token);Task task=tasks.get(player.getUUID());
        if(task!=null && manualCompass(task) && eligible(player,stack,token))acquire(task,task.center);
    }
    public static boolean start(ServerPlayer player,ItemStack stack,UUID token) {
        return request(player,stack,token,UUID.randomUUID(),false);
    }
    public static boolean request(ServerPlayer player,ItemStack stack,UUID supplied,UUID requestId,boolean fallback) {
        var target=recorded.read(player,stack);
        if(target==null) {message(player,"filters.teleport_unavailable");return false;}
        if(!target.biome() && recorded.portalRequired(target.key()) && recorded.portal(stack)==null){
            message(player,"filters.teleport_reason_portal_relocate");return false;
        }
        UUID actual=target.token();
        if(supplied!=null && !supplied.equals(actual) || supplied==null && actual!=null) return false;
        // 旧结构目标首次请求由服务端赋标识；客户端不能把第一次点击伪装成降级。
        if(actual==null) {
            if(target.biome() || fallback && mode(player.getUUID())!=LandingMode.RELAXED_ONLY)return false;
            actual=UUID.randomUUID();
        }
        recorded.bindLegacy(player,stack,actual);
        long now=System.nanoTime();var ledger=requests.computeIfAbsent(player.getUUID(),ignored->new LinkedHashMap<>());
        ledger.values().removeIf(time->now-time>CONFIRM_NANOS);
        if(ledger.containsKey(requestId))return true;
        ledger.put(requestId,now);while(ledger.size()>256)ledger.remove(ledger.keySet().iterator().next());
        Task task=tasks.get(player.getUUID());
        if(task==null || task.stack!=stack || !task.token.equals(actual) || !task.valid()) {
            prepare(player,stack,actual);task=tasks.get(player.getUUID());
        }
        if(task.handoffUntil>0) {finish(task,"handoff",1);prepare(player,stack,actual);task=tasks.get(player.getUUID());}
        if(task.clicked)return true;
        if(task.waiting && now>=task.confirmationUntil) {finish(task,"expired",0);prepare(player,stack,actual);task=tasks.get(player.getUUID());}
        if(task.mode==LandingMode.SAFE_ONLY)fallback=false;
        if(task.mode==LandingMode.RELAXED_ONLY)fallback=true;
        if(fallback && task.mode==LandingMode.DEFAULT && !task.waiting) {status(task,State.CANCELLED,"confirmation_required");return false;}
        if(task.waiting && !fallback && task.mode==LandingMode.DEFAULT) {status(task,State.NEEDS_CONFIRMATION,"confirm");return true;}
        boolean retry=task.waiting;
        if(task.portalHint!=null && (task.portal==null || task.fallback!=fallback))
            task.portal=new PortalLanding(task.portalHint);
        task.waiting=false;task.clicked=true;task.fallback=fallback;task.phase="";
        if(retry && !fallback && Arrays.stream(task.strictHeights).allMatch(h->h>=33)) {
            Arrays.fill(task.strictHeights,0);task.strictLanding=null;task.landing=null;
        }
        if(retry && fallback && Arrays.stream(task.relaxedHeights).allMatch(h->h>=129)
                && Arrays.stream(task.surfaceHeights).allMatch(h->h>=1)) {
            Arrays.fill(task.relaxedHeights,0);Arrays.fill(task.surfaceHeights,0);task.relaxedLanding=null;
        }
        task.heights=fallback?task.relaxedHeights:task.strictHeights;task.column=0;
        task.quickChecked=false;
        task.requestDeadline=now+MANUAL_TOTAL;task.attemptStarted=now;task.waitStarted=task.ticket?now:0;
        task.queueStarted=task.ticket?0:now;task.queueNanos=0;task.manualProgress=now;
        task.waitNanos=0;task.checkNanos=0;task.centerDeadline=now+MANUAL_STALL;
        task.expires=player.getServer().getTickCount()+200;
        task.landing=fallback?task.relaxedLanding:task.strictLanding;
        phase(task,task.ticket?"waiting_chunks":"waiting_slots");
        message(player,"filters.teleport_checking");return true;
    }
    /** 自动旅行继承用户模式；默认模式在同一任务内推进降级，保留区块与扫描进度。 */
    public static boolean travel(ServerPlayer player,ItemStack stack,java.util.function.Consumer<TravelResult> completion) {
        return travel(player,stack,completion,null);
    }
    public static boolean travelDetailed(ServerPlayer player,ItemStack stack,java.util.function.Consumer<TravelOutcome> completion) {
        return travel(player,stack,null,completion);
    }
    private static boolean travel(ServerPlayer player,ItemStack stack,java.util.function.Consumer<TravelResult> completion,
                                  java.util.function.Consumer<TravelOutcome> detailed) {
        var target=recorded.read(player,stack);
        UUID token=target==null?null:target.token();
        if(!eligible(player,stack,token))return false;
        Task prepared=tasks.get(player.getUUID());
        if(prepared==null || !prepared.valid() || !prepared.token.equals(token) || prepared.handoffUntil>0)prepare(player,stack,token);
        Task task=tasks.get(player.getUUID());boolean resumed=task.travel;
        if(!task.travel && !task.manualChunks.isEmpty())release(task);
        task.travel=true;task.completion=completion;task.detailedCompletion=detailed;
        if(task.budgetPaused){
            // 同一逻辑落点任务继续下一段CPU预算；不重置总期限、中心等待或高度游标。
            task.budgetPaused=false;task.clicked=true;task.waiting=false;task.checkNanos=0;
            phase(task,"height_probe");return true;
        }
        beginTravelStage(task,resumed?task.fallback:task.mode==LandingMode.RELAXED_ONLY);return true;
    }
    public static boolean allowedTravel(ServerPlayer player,ItemStack stack) {
        return recorded.allowedReturn(player,stack);
    }
    /** 返回也复用同一票据、落点检查与调度预算，但不写目标和不累计次数。 */
    public static boolean returnTravel(ServerPlayer player,ItemStack stack,Vec3 origin,float yaw,float pitch,
                                        java.util.function.Consumer<Boolean> completion) {
        if(!allowedTravel(player,stack))return false;
        cancel(player.getUUID());
        UUID token=recorded.token(stack);
        Task task=new Task(player,stack,token==null?UUID.randomUUID():token,BlockPos.containing(origin));
        task.travel=true;
        task.clicked=true;task.completion=result->completion.accept(result==TravelResult.MOVED);task.returnYaw=yaw;task.returnPitch=pitch;
        task.requestDeadline=System.nanoTime()+REQUEST_NANOS;task.attemptStarted=System.nanoTime();
        task.waitStarted=System.nanoTime();task.expires=player.getServer().getTickCount()+200;
        if(LandingChecks.ready(player,task.level,BlockPos.containing(origin),false)) {
            if(LandingChecks.standing(player,task.level,null,origin,false))task.strictLanding=origin;
            // 降级返回也先复用原坐标，避免切换阶段时丢失已验证的出发位置。
            if(LandingChecks.standing(player,task.level,null,origin,true)
                    || LandingChecks.waterStanding(player,task.level,origin))task.relaxedLanding=origin;
        }
        tasks.put(player.getUUID(),task);beginTravelStage(task,task.mode==LandingMode.RELAXED_ONLY);return true;
    }
    private static void beginTravelStage(Task task,boolean relaxed) {
        task.budgetPaused=false;task.fallback=relaxed;task.clicked=true;task.waiting=false;task.phase="";
        task.heights=relaxed?task.relaxedHeights:task.strictHeights;task.column=0;
        task.landing=relaxed?task.relaxedLanding:task.strictLanding;
        long now=System.nanoTime();task.requestDeadline=now+REQUEST_NANOS;
        task.centerDeadline=now+(task.travel?60_000_000_000L:WAIT_NANOS);task.attemptStarted=now;task.checkNanos=0;
        if(task.travel)task.requestDeadline=now+70_000_000_000L;
        task.waitStarted=now;task.waitNanos=0;task.expires=task.player.getServer().getTickCount()+200;
        phase(task,task.ticket?"waiting_chunks":"waiting_slots");
    }
    private static ResourceLocation landingBiome(Task task){return task.biome?task.key:null;}
    private static void completed(Task task,TravelResult result) {
        var callback=task.completion;var detailed=task.detailedCompletion;task.completion=null;task.detailedCompletion=null;
        var proof=result==TravelResult.NO_POSITION && task.heightProbe!=null?task.heightProbe.provenUnusableColumn():List.<BlockPos>of();
        if(detailed!=null)detailed.accept(new TravelOutcome(result,proof));else if(callback!=null)callback.accept(result);
    }
    /** 仅读取已经就绪的真实区块；加载期间返回未知，不能用生成预测冒充到访。 */
    public static ResourceLocation loadedBiome(ServerPlayer player) {
        var level=player.serverLevel();var pos=player.blockPosition();
        for(int x=(pos.getX()-4)>>4;x<=(pos.getX()+4)>>4;x++)
            for(int z=(pos.getZ()-4)>>4;z<=(pos.getZ()+4)>>4;z++)
                if(level.getChunkSource().getChunkNow(x,z)==null)return null;
        return level.getBiome(pos).unwrapKey().map(key->key.location()).orElse(null);
    }
    public static String travelPhase(UUID player) {
        Task task=tasks.get(player);return task==null?"landing":task.phase.equals("checking")?"checking":task.phase.equals("height_probe")?"height_probe":"waiting_chunks";
    }
    private static boolean aetherBiome(Task task){return task.travel && !task.returning && task.biome && task.level.dimension().location().toString().equals("aether:the_aether");}
    private static boolean requiresBiome(Task task){return task.travel || !task.level.dimension().location().toString().equals("aether:the_aether");}
    private static boolean travelBiome(Task task,Vec3 point) {
        if(aetherBiome(task))return task.level.getBiome(BlockPos.containing(point)).unwrapKey().map(key->key.location().equals(task.key)).orElse(false);
        return !task.travel || !task.biome || task.fallback || task.level.getBiome(BlockPos.containing(point)).unwrapKey()
                .map(key->key.location().equals(task.key)).orElse(false);
    }
    private static boolean preferredTravelLanding(Task task,Vec3 point) {
        if(!task.travel || !task.biome || !task.fallback || task.level.getBiome(BlockPos.containing(point)).unwrapKey()
                .map(key->key.location().equals(task.key)).orElse(false))return true;
        // 降级仍允许邻近群系，但先保留为备选，避免自动旅行反复落到同一个非目标群系。
        if(task.travelAlternate==null)task.travelAlternate=point;
        return false;
    }
    public static boolean active() {return tasks.values().stream().anyMatch(t->!t.waiting && (t.clicked || t.landing==null));}
    public static boolean awaitingConfirmation(UUID player) {Task t=tasks.get(player);return t!=null && t.waiting;}
    public static BlockPos prepared(UUID player) {Task t=tasks.get(player);return t==null || t.landing==null?null:BlockPos.containing(t.landing);}
    public static int ticketCount() {return (int)tasks.values().stream().filter(t->t.ticket).count();}
    public static void maintain() {
        for(Task task:List.copyOf(tasks.values())) {
            if(task.handoffUntil>0) {
                if(task.player.isRemoved() || task.player.serverLevel()!=task.level || task.player.getServer().getTickCount()>=task.handoffUntil) finish(task,"handoff",1);
                continue;
            }
            if(!task.valid()) {finish(task,"cancelled",0);continue;}
            if(task.waiting) {
                if(System.nanoTime()>=task.confirmationUntil)finish(task,"expired",0);
                else if(task.player.getServer().getTickCount()>=task.expires)release(task);
                continue;
            }
            if(task.clicked && aetherBiome(task)) {
                long now=System.nanoTime();int count=readyCount(task);
                if(task.progressAt==0 || count>task.readyCount){task.progressAt=now;task.readyCount=count;}
                if(now-task.progressAt>=(count==9?40_000_000_000L:60_000_000_000L))failed(task,count==9?"timeout":"chunks_not_ready");
            } else if(task.clicked && manualCompass(task)) {
                long now=System.nanoTime();
                int mask=0,i=0;
                for(var chunk:task.manualChunks){
                    if(full(task,chunk))mask|=1<<i;
                    i++;
                }
                if((mask & ~task.readyMask)!=0)task.manualProgress=now;
                task.readyMask=mask;
                boolean pending=!task.ticket || (task.manualChunks.size()>0
                        && Integer.bitCount(mask)<task.manualChunks.size());
                if(now>=task.requestDeadline || (pending && now-task.manualProgress>=MANUAL_STALL))
                    failed(task,!task.ticket?"waiting_slots":pending?"chunks_not_ready":"timeout");
            } else if(task.clicked && System.nanoTime()>=task.requestDeadline) {
                failed(task,!task.ticket?"waiting_slots":!windowReady(task)?"chunks_not_ready":"timeout");
            } else if(!task.clicked && manualCompass(task)) {
                long now=System.nanoTime();
                if(task.landing!=null){
                    if(task.landingReadyAt==0){task.landingReadyAt=now;VerificationSlots.release(task);task.slotHeld=false;}
                    if(now-task.landingReadyAt>=30_000_000_000L)finish(task,"timeout",0);
                }else if(now-task.preparedAt>=30_000_000_000L)finish(task,"timeout",0);
            } else if(!task.clicked && task.player.getServer().getTickCount()>=task.expires) finish(task,"timeout",0);
        }
        var ordered=new ArrayList<Task>(tasks.size());
        Task background=null;
        for(var task:tasks.values()){
            if(manualCompass(task) && !task.clicked && task.slotHeld)background=task;
            if(!task.waiting && task.handoffUntil==0 && task.clicked)ordered.add(task);
        }
        for(var task:tasks.values())if(!task.waiting && task.handoffUntil==0 && !task.clicked)ordered.add(task);
        for(Task task:ordered){
            if(task.ticket)continue;
            if(manualCompass(task) && !task.clicked && background!=null)continue;
            if(task.clicked && background!=null && VerificationSlots.count()>=4){release(background);background=null;}
            acquire(task,task.center);
            if(manualCompass(task) && !task.clicked && task.slotHeld)background=task;
        }
        foregroundTasks=tasks.values().stream().filter(t->!t.waiting && t.handoffUntil==0 && t.clicked).toList();
        backgroundTasks=tasks.values().stream().filter(t->!t.waiting && t.handoffUntil==0 && !t.clicked && t.landing==null).toList();
    }
    private static void acquire(Task task,ChunkPos center) {
        if(manualCompass(task)){
            if(!task.slotHeld){
                boolean admitted=task.clicked?VerificationSlots.acquire(task):VerificationSlots.acquireBackground(task);
                if(!admitted && task.clicked){
                    // 预备不可占住最后一槽；前台重新按共用 FIFO 竞争。
                    tasks.values().stream().filter(t->t!=task && manualCompass(t) && !t.clicked
                            && !t.waiting && t.ticket && t.handoffUntil==0).findFirst().ifPresent(TeleportService::release);
                    admitted=VerificationSlots.acquire(task);
                }
                if(!admitted){if(task.clicked)phase(task,"waiting_slots");return;}
                task.slotHeld=true;
            }
            long now=System.nanoTime();
            if(!task.ticket){
                task.ticket=true;task.manualProgress=now;
                if(task.queueStarted!=0){task.queueNanos+=now-task.queueStarted;task.queueStarted=0;}
            }
            task.center=center;
            var needed=new LinkedHashSet<>(LandingChecks.footprint(task.player,
                    new BlockPos(center.getMinBlockX()+8,task.origin.getY(),center.getMinBlockZ()+8),false));
            needed.add(center);
            for(var pos:needed)if(task.manualChunks.add(pos))LandingChunkLeases.retain(task.level,pos);
            // 门房按需切换区块时也保持有界；普通候选由完整足迹原子替换。
            while(task.manualChunks.size()>4){var it=task.manualChunks.iterator();var old=it.next();it.remove();LandingChunkLeases.release(task.level,old);}
            task.lastCenterTick=task.player.getServer().getTickCount();
            if(task.clicked && !full(task,center)){
                phase(task,"waiting_chunks");if(task.waitStarted==0)task.waitStarted=now;
            }
            return;
        }
        if(task.ticket && task.center.equals(center))return;
        boolean changed=!task.center.equals(center);if(task.ticket)release(task);task.center=center;
        if(changed || task.progressAt==0){task.progressAt=System.nanoTime();task.readyCount=0;}
        if(!(task.clicked?VerificationSlots.acquire(task):VerificationSlots.acquireBackground(task))){if(task.clicked)phase(task,"waiting_slots");return;}
        task.centerDeadline=System.nanoTime()+(task.travel || aetherBiome(task) || task.external!=null && task.external.creature()?60_000_000_000L:WAIT_NANOS);task.chunksReady=0;
        if(task.clicked && task.waitStarted==0)task.waitStarted=System.nanoTime();
        if(task.clicked)phase(task,"waiting_chunks");
        task.lastCenterTick=task.player.getServer().getTickCount();task.level.getChunkSource().addRegionTicket(PREPARE,center,1,task.ticketId);task.ticket=true;
    }
    private static void requireFootprint(Task task,BlockPos point,boolean biome){
        if(!task.ticket){acquire(task,new ChunkPos(point));if(!task.ticket)return;}
        var needed=new LinkedHashSet<>(LandingChecks.footprint(task.player,point,biome));
        if(task.manualChunks.equals(needed))return;
        for(var pos:needed)if(task.manualChunks.add(pos))LandingChunkLeases.retain(task.level,pos);
        for(var it=task.manualChunks.iterator();it.hasNext();){var old=it.next();if(!needed.contains(old)){LandingChunkLeases.release(task.level,old);it.remove();}}
        task.readyMask=0;
        if(task.clicked && task.waitStarted==0)task.waitStarted=System.nanoTime();
    }
    private static void release(Task task) {
        if(manualCompass(task)){
            for(var pos:task.manualChunks)LandingChunkLeases.release(task.level,pos);
            task.manualChunks.clear();task.readyMask=0;task.ticket=false;
        }else if(task.ticket){task.level.getChunkSource().removeRegionTicket(PREPARE,task.center,1,task.ticketId);task.ticket=false;}
        VerificationSlots.release(task);task.slotHeld=false;
    }
    private static boolean manualCompass(Task task) {
        return !task.travel && !task.returning && task.external==null;
    }
    private static boolean full(Task task,ChunkPos pos){
        return task.level.getChunkSource().getChunkNow(pos.x,pos.z)!=null;
    }
    private static boolean windowReady(Task task) {
        // 已就绪的中心可以先开始；候选本身仍由 LandingChecks 核实实际触及区块。
        if(manualCompass(task))return task.level.getChunkSource().getChunkNow(task.center.x,task.center.z)!=null;
        for(int x=task.center.x-1;x<=task.center.x+1;x++)
            for(int z=task.center.z-1;z<=task.center.z+1;z++)
                if(task.level.getChunkSource().getChunkNow(x,z)==null)return false;
        return true;
    }
    private static int readyCount(Task task){int count=0;for(int x=task.center.x-1;x<=task.center.x+1;x++)for(int z=task.center.z-1;z<=task.center.z+1;z++)if(task.level.getChunkSource().getChunkNow(x,z)!=null)count++;return count;}
    static int foregroundCount(){
        return (int)tasks.values().stream().filter(t->!t.waiting && t.handoffUntil==0 && t.clicked).count();
    }
    static boolean step(long deadline,boolean foreground) {
        List<Task> choices=foreground?foregroundTasks:backgroundTasks;
        for(int i=0;i<choices.size() && System.nanoTime()<deadline;i++){
            Task selected=choices.get(Math.floorMod(cursor++,choices.size()));
            if(tasks.get(selected.player.getUUID())!=selected || selected.waiting || selected.handoffUntil>0)continue;
            int tick=selected.player.getServer().getTickCount();
            if(selected.blockedTick==tick)continue;
            if(step(selected,deadline))return true;
            selected.blockedTick=tick;
        }
        return false;
    }
    private static boolean step(Task task,long deadline){
        if(!task.valid()){finish(task,"cancelled",0);return true;}
        if(task.external!=null && task.external.creature())refreshCreature(task);
        if(!task.ticket)return false;
        // 等待中心固定，不能在生成线程尚未推进前反复搬走同一组票据。
        if(!manualCompass(task) && !windowReady(task)) {
            if(task.clicked && System.nanoTime()>=task.centerDeadline)failed(task,"chunks_not_ready");
            return false;
        }
        if(manualCompass(task) && task.waitStarted!=0 && task.manualChunks.stream().allMatch(p->full(task,p))){
            task.waitNanos+=System.nanoTime()-task.waitStarted;task.waitStarted=0;
        }
        if(task.chunksReady==0 && (!manualCompass(task) || full(task,task.center))) {
            task.chunksReady=System.nanoTime();
            if(!manualCompass(task) && task.waitStarted!=0){task.waitNanos+=task.chunksReady-task.waitStarted;task.waitStarted=0;}
            if(task.clicked)phase(task,"checking");
        }
        long quantum=Math.min(deadline,System.nanoTime()+250_000L);
        try {
            if(task.external!=null && task.external.creature() && task.fallback)return creatureStep(task);
            if(task.portalRequired)return portalStep(task,quantum);
            if(task.treasure)return treasureStep(task);
            if(task.legacyHeight && !task.heightResolved) {
                var chunk=task.level.getChunkSource().getChunkNow(task.x>>4,task.z>>4);
                if(chunk==null)return false;
                task.origin=task.origin.atY(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,task.x&15,task.z&15)+1);task.heightResolved=true;
            }
            if(aetherBiome(task)) {
                if(task.heightProbe==null)task.heightProbe=new AetherHeightProbe(task.player,task.key,task.origin,task.fallback);
                if(task.heightProbe.pending(task.fallback)) {
                    phase(task,"height_probe");long before=System.nanoTime();
                    int checked=task.heightProbe.checked();Vec3 found=task.heightProbe.step(quantum,task.fallback);task.checkNanos+=System.nanoTime()-before;
                    if(task.heightProbe.checked()!=checked)task.progressAt=System.nanoTime();
                    if(found!=null){task.landing=found;if(task.clicked)move(task);return true;}
                    if(task.heightProbe.waiting!=null) {
                        ChunkPos needed=new ChunkPos(task.heightProbe.waiting);
                        if(!needed.equals(task.center) && task.lastCenterTick!=task.player.getServer().getTickCount())acquire(task,needed);
                        return false;
                    }
                    if(task.heightProbe.exhausted()){
                        if(task.clicked)failed(task,"no_position");else finish(task,"no_position",0);
                        return true;
                    }
                    // 高度游标有限；批次只让出调度权，不把累计CPU用量当成失败。
                    return true;
                }
                // 当前模式已完整穷尽，不再由通用扫描重复同一范围。
                if(task.clicked)failed(task,"no_position");else finish(task,"no_position",0);
                return true;
            }
            if(task.landing!=null) {
                if(recheck(task,task.landing,task.fallback) && preferredTravelLanding(task,task.landing)){if(task.clicked)move(task);return false;}
                task.landing=null;
            }
            if(task.fallback && task.relaxedLanding!=null) {
                if(recheck(task,task.relaxedLanding,true) && preferredTravelLanding(task,task.relaxedLanding)){task.landing=task.relaxedLanding;if(task.clicked)move(task);return true;}
                task.relaxedLanding=null;
            }
            if(manualCompass(task) && task.fallback && !task.quickChecked) {
                var point=task.origin;
                var chunk=task.level.getChunkSource().getChunkNow(point.getX()>>4,point.getZ()>>4);
                if(chunk!=null) {
                    task.quickChecked=true;
                    // 降级普通目标可在同区块内偏移少量格子，避免角落候选先等四块 FULL。
                    int localX=Math.max(2,Math.min(13,point.getX()&15));
                    int localZ=Math.max(2,Math.min(13,point.getZ()&15));
                    var interior=new BlockPos((point.getX()&~15)+localX,point.getY(),(point.getZ()&~15)+localZ);
                    int surface=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            point.getX()&15,point.getZ()&15)+1;
                    Vec3 quick=LandingChecks.compassFallback(task.player,task.level,point);
                    if(quick==null)quick=LandingChecks.compassFallback(task.player,task.level,point.atY(surface));
                    if(quick==null)quick=LandingChecks.compassFallback(task.player,task.level,interior);
                    if(quick==null){int innerSurface=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,localX,localZ)+1;
                        quick=LandingChecks.compassFallback(task.player,task.level,interior.atY(innerSurface));}
                    if(quick!=null){
                        task.landing=quick;task.relaxedLanding=quick;
                        if(task.clicked)move(task);
                        return true;
                    }
                }
            }
            var columns=task.fallback?LandingColumns.RELAXED:LandingColumns.STRICT;
            // 地表与局部交替推进，使用独立游标；不清空第一轮已完成的高度。
            boolean surface=(task.fallback || task.travel && !task.returning) && (task.alternateSurface=!task.alternateSurface);
            int[] progress=surface?task.surfaceHeights:task.heights;
            int count=surface?1:task.fallback?129:33;
            ChunkPos missing=null;boolean work=false;
            for(int attempt=0;attempt<columns.size();attempt++) {
                int index=task.column;task.column=(task.column+1)%columns.size();
                if(progress[index]>=count)continue;
                var column=columns.get(index);int x=task.x+column.x(),z=task.z+column.z();
                BlockPos point=new BlockPos(x,task.origin.getY(),z);
                var required=LandingChecks.missing(task.player,task.level,point,!task.fallback && task.biome);
                if(required!=null){
                    if(manualCompass(task) && missing==null){task.column=index;requireFootprint(task,point,!task.fallback && task.biome);return false;}
                    if(missing==null || manualCompass(task) && task.manualChunks.contains(missing))
                        missing=manualCompass(task)?required:new ChunkPos(point);
                    if(manualCompass(task) && System.nanoTime()>=quantum)break;
                    continue;
                }
                int y=surface?task.level.getChunkSource().getChunkNow(x>>4,z>>4)
                        .getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x&15,z&15)+1
                        :task.origin.getY()+LandingColumns.vertical(progress[index]);
                long before=System.nanoTime();Vec3 landing;
                try {
                    point=point.atY(y);progress[index]++;
                    if(task.external!=null && !task.external.accepts(Vec3.atBottomCenterOf(point))){if(System.nanoTime()>=quantum)return true;continue;}
                    landing=manualCompass(task) && task.fallback?LandingChecks.compassFallback(task.player,task.level,point)
                            :LandingChecks.find(task.player,task.level,landingBiome(task),point,task.fallback,requiresBiome(task));
                    if(landing==null && surface && task.fallback)landing=LandingChecks.waterSurface(task.player,task.level,point);
                    if(landing!=null && (!travelBiome(task,landing) || !preferredTravelLanding(task,landing)))landing=null;
                    if(!task.fallback && task.relaxedLanding==null)
                        task.relaxedLanding=LandingChecks.find(task.player,task.level,landingBiome(task),point,true,requiresBiome(task));
                } finally {task.checkNanos+=System.nanoTime()-before;}
                work=true;
                if(landing!=null) {
                    task.landing=landing;
                    if(task.fallback)task.relaxedLanding=landing;else task.strictLanding=landing;
                    if(task.clicked)move(task);return true;
                }
                // 在一列内连续检查上下高度，不轮转四千列后才查看第二个 Y。
                if(!surface && progress[index]<count)task.column=index;
                if(task.clicked && !manualCompass(task) && task.checkNanos>=(task.fallback?40_000_000L:80_000_000L)) {
                    failed(task,"scan_budget");return true;
                }
                if(System.nanoTime()>=quantum)return true;
            }
            boolean exhausted=Arrays.stream(task.heights).allMatch(h->h>=(task.fallback?129:33));
            if(exhausted && (!(task.fallback || task.travel && !task.returning)
                    || Arrays.stream(task.surfaceHeights,0,columns.size()).allMatch(h->h>=1))) {
                if(task.clicked)failed(task,"no_position");else finish(task,"no_position",0);
                return true;
            }
            if(!work && missing!=null && !(manualCompass(task)?task.manualChunks.contains(missing):missing.equals(task.center))
                    && task.lastCenterTick!=task.player.getServer().getTickCount()) {
                if(!manualCompass(task) && resumeReady(task,columns))return true;
                acquire(task,missing);return false;
            }
            return work;
        } catch(RuntimeException error) {
            LOGGER.warn("落点检查失败",error);
            if(task.clicked)failed(task,"error");else finish(task,"error",0);return true;
        }
    }
    private static boolean resumeReady(Task task,List<LandingColumns.Column> columns) {
        for(int i=0;i<columns.size();i++) {
            boolean local=task.heights[i]<(task.fallback?129:33);
            boolean surface=(task.fallback || task.travel && !task.returning) && task.surfaceHeights[i]<1;
            if(!local && !surface)continue;
            var c=columns.get(i);var p=new BlockPos(task.x+c.x(),task.origin.getY(),task.z+c.z());
            if(!LandingChecks.ready(task.player,task.level,p,!task.fallback && task.biome))continue;
            task.column=i;task.alternateSurface=local;return true;
        }
        return false;
    }
    private static boolean portalStep(Task task,long quantum){
        if(task.portal==null){finish(task,"portal_relocate",0);return true;}
        if(task.landing!=null && recheck(task,task.landing,task.fallback)){
            if(task.clicked)move(task);
            return false;
        }
        long start=System.nanoTime();
        var progress=task.portal.step(task.player,task.level,task.fallback,quantum);
        task.checkNanos+=System.nanoTime()-start;
        if(progress.point()!=null){
            task.landing=progress.point();
            if(task.fallback)task.relaxedLanding=task.landing;else task.strictLanding=task.landing;
            if(task.clicked)move(task);
            return true;
        }
        if(progress.done()){
            task.portal=null;
            if(task.clicked)failed(task,"portal_no_position");else finish(task,"portal_no_position",0);
            return true;
        }
        if(progress.waiting()!=null){
            if(!progress.waiting().equals(task.center) && task.lastCenterTick!=task.player.getServer().getTickCount())
                acquire(task,progress.waiting());
            return false;
        }
        return true;
    }
    /** 宝藏只检查真实箱子列的地表，绝不进入外围列或地下落点。 */
    private static boolean treasureStep(Task task) {
        var chunk=task.level.getChunkSource().getChunkNow(task.x>>4,task.z>>4);
        if(chunk==null)return false;
        int y=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,task.x&15,task.z&15)+1;
        BlockPos point=new BlockPos(task.x,y,task.z);
        var missing=LandingChecks.missing(task.player,task.level,point,false);
        if(missing!=null){
            requireFootprint(task,point,false);
            return false;
        }
        if(task.chunksReady==0)task.chunksReady=System.nanoTime();
        long before=System.nanoTime();
        Integer chestY=task.chestY;
        if(chestY==null || !chunk.getBlockState(new BlockPos(task.x,chestY,task.z)).is(net.minecraft.world.level.block.Blocks.CHEST))
            chestY=treasureChestY(task,chunk);
        task.chestY=chestY;
        if(chestY==null){
            task.checkNanos+=System.nanoTime()-before;
            if(task.clicked)failed(task,"treasure_chest_missing");else finish(task,"treasure_chest_missing",0);
            return true;
        }
        Vec3 landing=null;
        // 结构起始片段的占位高度不是宝箱高度。用已就绪区块中的真实箱子限制落点。
        for(int offset:new int[]{0,1,-1,2,-2,3,-3,4,-4,5,-5,6,-6,7,-7,8,-8}){
            BlockPos at=point.above(offset);
            if(at.getY()<=chestY)continue;
            landing=task.fallback?LandingChecks.compassFallback(task.player,task.level,at)
                    :LandingChecks.find(task.player,task.level,null,at,false,false);
            if(landing!=null && landing.y>chestY)break;
            landing=null;
        }
        task.checkNanos+=System.nanoTime()-before;
        if(landing==null) {if(task.clicked)failed(task,"no_position");else finish(task,"no_position",0);return true;}
        task.landing=landing;if(task.fallback)task.relaxedLanding=landing;else task.strictLanding=landing;
        if(task.clicked)move(task);return true;
    }
    private static Integer treasureChestY(Task task,net.minecraft.world.level.chunk.LevelChunk chunk){
        var cursor=new BlockPos.MutableBlockPos(task.x,0,task.z);
        Integer opened=null;
        int bottom=task.level.getMinBuildHeight();
        for(int y=task.level.getMaxBuildHeight()-1;y>=bottom;){
            int first=Math.max(bottom,y&~15);
            if(!chunk.getSection(task.level.getSectionIndex(y)).maybeHas(state->state.is(net.minecraft.world.level.block.Blocks.CHEST))){
                y=first-1;continue;
            }
            for(;y>=first;y--){
                cursor.set(task.x,y,task.z);
                if(!chunk.getBlockState(cursor).is(net.minecraft.world.level.block.Blocks.CHEST))continue;
                if(!(chunk.getBlockEntity(cursor) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest))continue;
                var data=chest.saveWithoutMetadata();
                if(data.getString("LootTable").equals("minecraft:chests/buried_treasure"))return y;
                if(opened==null && !data.contains("LootTable"))opened=y;
            }
        }
        return opened;
    }
    private static boolean recheck(Task task,Vec3 point,boolean relaxed) {
        if(task.portalRequired && (task.portal==null || !task.portal.framePresent(task.level)))return false;
        if(task.external!=null && task.external.creature() && relaxed)return task.external.accepts(point) && LandingChecks.allowsUnanchored(task.player,task.level,point);
        if(manualCompass(task) && relaxed)return LandingChecks.compassRelaxed(task.player,task.level,point);
        return (task.external==null || task.external.accepts(point)) && travelBiome(task,point) && (LandingChecks.standing(task.player,task.level,landingBiome(task),point,relaxed,requiresBiome(task))
                || relaxed && LandingChecks.waterStanding(task.player,task.level,point));
    }
    private static void refreshCreature(Task task){
        BlockPos current=task.external.position();
        if(current.equals(task.origin))return;
        task.origin=current;task.x=current.getX();task.z=current.getZ();task.sourceY=current.getY();
        task.landing=task.strictLanding=task.relaxedLanding=null;task.column=0;
        Arrays.fill(task.strictHeights,0);Arrays.fill(task.relaxedHeights,0);Arrays.fill(task.surfaceHeights,0);
        ChunkPos center=new ChunkPos(current);if(!center.equals(task.center))acquire(task,center);
    }
    private static boolean creatureStep(Task task){
        for(Vec3 point:task.external.relaxedCandidates(task.player))if(recheck(task,point,true)){
            task.landing=point;if(task.clicked)move(task);return true;
        }
        failed(task,"no_position");return true;
    }
    private static void failed(Task task,String reason) {
        if(task.travel) {
            if(reason.equals("error")){finish(task,reason,0);return;}
            if(reason.equals("chunks_not_ready") || reason.equals("waiting_slots") || reason.equals("timeout")) {
                // 真正等待超时保留当前候选、票据和游标，由旅行模块决定是否恢复。
                summary(task,reason);task.clicked=false;task.waiting=true;
                task.confirmationUntil=Long.MAX_VALUE;task.expires=task.player.getServer().getTickCount()+1200;
                completed(task,TravelResult.TIMED_OUT);return;
            }
            if(task.fallback && task.travelAlternate!=null && recheck(task,task.travelAlternate,true)) {
                task.landing=task.travelAlternate;move(task);return;
            }
            summary(task,reason);
            if(!task.fallback && task.mode==LandingMode.DEFAULT) {beginTravelStage(task,true);return;}
            finish(task,reason,0);return;
        }
        if(task.clicked && task.external==null && !task.returning
                && (reason.equals("chunks_not_ready") || reason.equals("waiting_slots"))) {
            UUID player=task.player.getUUID();
            if(preparationFailures.record(player,task.level,task.token))
                recorded.preparationFailed(task.player,task.key,task.x,task.origin.getY(),task.z,reason);
            // 等待确认的请求不能继续占用前台名额。
            release(task);
        }
        summary(task,reason);task.clicked=false;task.waiting=true;task.confirmationUntil=System.nanoTime()+CONFIRM_NANOS;task.expires=task.player.getServer().getTickCount()+100;
        boolean confirm=!task.fallback && task.mode==LandingMode.DEFAULT;
        status(task,confirm?State.NEEDS_CONFIRMATION:State.FAILED_RETRYABLE,reason);
        if(task.player.connection!=null)task.player.displayClientMessage(Component.translatable(confirm?"filters.teleport_confirm":"filters.teleport_retry")
                .append(" ").append(Component.translatable("filters.teleport_reason_"+reason)),true);
    }
    private static void phase(Task task,String phase) {
        if(task.phase.equals(phase))return;task.phase=phase;
        status(task,task.fallback?State.FALLBACK_PREPARING:State.PREPARING,phase);
    }
    private static void status(Task task,State state,String reason) {
        if(task.external!=null){task.external.state(reason,state==State.NEEDS_CONFIRMATION);return;}
        recorded.status(task.player,task.token,state,++revision,reason);
    }
    private static void move(Task task) {
        if(!task.valid() || task.player.connection==null){finish(task,"cancelled",0);return;}
        LandingSafety.invalidate(task.level,task.landing);
        if(!recheck(task,task.landing,task.fallback)){task.landing=null;return;}
        Vec3 point=task.landing;task.player.stopRiding();task.player.connection.teleport(point.x,point.y,point.z,task.returning?task.returnYaw:task.player.getYRot(),task.returning?task.returnPitch:task.player.getXRot());
        task.player.setDeltaMovement(0,0,0);task.player.fallDistance=0;
        if(aetherBiome(task) && !task.key.equals(loadedBiome(task.player))) {
            // 其它模组可能改写实际落点。先保留同一逻辑请求做高度复核，不发成功计数或旧回调。
            task.origin=task.player.blockPosition().immutable();task.heightProbe=new AetherHeightProbe(task.player,task.key,task.origin,task.fallback);task.landing=null;
            phase(task,"height_probe");return;
        }
        task.level.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT,new ChunkPos(BlockPos.containing(point)),1,task.player.getId());
        if(!task.returning && task.external==null){
            preparationFailures.clear(task.player.getUUID());
            recorded.moved(task.player,task.biome,task.key,task.level.dimension().location(),point);
        }
        if(task.external!=null)task.external.moved();
        boolean water=LandingChecks.waterStanding(task.player,task.level,point);
        if(water)message(task.player,"filters.teleport_water_surface");
        status(task,State.SUCCEEDED,water?"water_moved":task.fallback?"fallback_moved":"moved");
        summary(task,water?"water_moved":"moved");
        if(task.heightProbe!=null)LOGGER.info("天境高度复核 {} biome={} {}",task.token,task.key,task.heightProbe.summary());
        // 成功只保留票据交接，不占生成/核对名额。
        if(manualCompass(task)){VerificationSlots.release(task);task.slotHeld=false;}
        task.clicked=false;task.waiting=true;task.handoffUntil=task.player.getServer().getTickCount()+40;
        completed(task,TravelResult.MOVED);
    }
    private static void summary(Task task,String result) {
        long now=System.nanoTime(),waiting=task.waitNanos+(task.waitStarted==0?0:now-task.waitStarted);
        long queued=task.queueNanos+(task.queueStarted==0?0:now-task.queueStarted);
        LOGGER.info("传送 {} 阶段摘要：result={}, phase={}, elapsed_ms={}, queue_ms={}, waiting_ms={}, checking_ms={}",
                task.token,result,task.phase,(now-task.attemptStarted)/1e6,queued/1e6,waiting/1e6,task.checkNanos/1e6);
        if(task.heightProbe!=null)LOGGER.info("天境高度复核诊断 {} biome={} {}",task.token,task.key,task.heightProbe.summary());
    }
    public static boolean safeUnfiltered(ServerPlayer player,ServerLevel level,BlockPos point) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("落点检查必须在服务端线程");
        return LandingChecks.find(player,level,null,point,false)!=null;
    }
    public static boolean safe(ServerPlayer player,ServerLevel level,ItemStack stack,BlockPos point) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("落点检查必须在服务端线程");
        return LandingChecks.find(player,level,recorded.biome(stack),point,false)!=null;
    }
    public static Vec3 relaxed(ServerPlayer player,ServerLevel level,ItemStack stack,BlockPos point) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("落点检查必须在服务端线程");
        return LandingChecks.find(player,level,recorded.biome(stack),point,true);
    }
    public static boolean safeAt(ServerPlayer player,ServerLevel level,ItemStack stack,Vec3 point) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("落点检查必须在服务端线程");
        return LandingChecks.standing(player,level,recorded.biome(stack),point,false);
    }
    private static void finish(Task task,String reason,long moved) {
        if(tasks.remove(task.player.getUUID(),task)) {
            release(task);
            if(moved==0 && task.handoffUntil==0)status(task,State.CANCELLED,reason);
            completed(task,reason.equals("cancelled")?TravelResult.CANCELLED:reason.equals("error")?TravelResult.ERROR:TravelResult.NO_POSITION);
        }
    }
    public static void cancel(UUID player){Task t=tasks.get(player);if(t!=null)finish(t,"cancelled",0);}
    public static void forget(UUID player){cancel(player);requests.remove(player);modes.remove(player);preparationFailures.clear(player);}
    public static void shutdown(){clear();modes.clear();}
    public static void clear(){for(Task t:List.copyOf(tasks.values()))finish(t,"cancelled",0);requests.clear();preparationFailures.clear();cursor=0;}
    private static void message(ServerPlayer player,String key){if(player.connection!=null)player.displayClientMessage(Component.translatable(key),true);}
}
