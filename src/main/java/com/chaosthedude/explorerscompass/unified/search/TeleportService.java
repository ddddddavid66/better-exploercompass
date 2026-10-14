package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.unified.data.LandingColumns;
import com.chaosthedude.explorerscompass.unified.data.LandingMode;
import com.chaosthedude.explorerscompass.unified.network.*;
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
    public enum TravelResult { MOVED, NO_POSITION, BUDGET_EXHAUSTED, TIMED_OUT, CANCELLED, ERROR }
    public record TravelOutcome(TravelResult result,List<BlockPos> provenUnusable) {}
    private static final TicketType<UUID> PREPARE=TicketType.create("compassfilters_landing",Comparator.<UUID>naturalOrder());
    private static final Map<UUID,Task> tasks=new LinkedHashMap<>();
    private static final Map<UUID,LinkedHashMap<UUID,Long>> requests=new HashMap<>();
    private static final Map<UUID,LandingMode> modes=new HashMap<>();
    private static long revision;
    private static int cursor;
    private static final long CONFIRM_NANOS=30_000_000_000L;
    private static final long WAIT_NANOS=5_000_000_000L, REQUEST_NANOS=10_000_000_000L;
    private static final class Task {
        final ServerPlayer player; final ServerLevel level; final ItemStack stack;
        final UUID token,ticketId=UUID.randomUUID(); final ResourceLocation key;
        final boolean biome,legacyHeight,treasure; LandingMode mode; final int x,z,sourceY;
        BlockPos origin; ChunkPos center;
        final int[] strictHeights=new int[LandingColumns.STRICT.size()], relaxedHeights=new int[LandingColumns.RELAXED.size()];
        int[] heights=strictHeights;
        int expires,column;
        final int[] surfaceHeights=new int[LandingColumns.RELAXED.size()];
        long requestDeadline,centerDeadline,checkNanos,attemptStarted,waitStarted,waitNanos; int lastCenterTick=-1,handoffUntil;
        String phase="";
        boolean heightResolved,alternateSurface; boolean ticket,clicked,fallback,waiting;
        long chunksReady,confirmationUntil;
        Vec3 landing,strictLanding,relaxedLanding,travelAlternate;
        boolean travel, returning,budgetPaused; float returnYaw, returnPitch;
        java.util.function.Consumer<TravelResult> completion;
        java.util.function.Consumer<TravelOutcome> detailedCompletion;
        LocatorTarget external;
        AetherHeightProbe heightProbe;
        Task(ServerPlayer player,ItemStack stack,UUID token) { this(player,stack,token,null); }
        Task(ServerPlayer player,ItemStack stack,UUID token,BlockPos returnPoint) {
            returning=returnPoint!=null;
            this.player=player;level=player.serverLevel();this.stack=stack;this.token=token;
            var compass=ExplorersCompass.explorersCompass;
            biome=!returning && SearchService.isBiome(stack);key=target(stack);
            x=returning?returnPoint.getX():compass.getFoundStructureX(stack);z=returning?returnPoint.getZ():compass.getFoundStructureZ(stack);
            legacyHeight=!returning && !stack.getTag().contains(SearchService.FOUND_Y);sourceY=returning?returnPoint.getY():stack.getTag().getInt(SearchService.FOUND_Y);
            treasure=!returning && !biome && key!=null && key.toString().equals("minecraft:buried_treasure");
            mode=modes.getOrDefault(player.getUUID(),LandingMode.DEFAULT);fallback=mode==LandingMode.RELAXED_ONLY;
            heights=fallback?relaxedHeights:strictHeights;
            origin=new BlockPos(x,legacyHeight?player.blockPosition().getY():sourceY,z);center=new ChunkPos(origin);
            expires=player.getServer().getTickCount()+100;
        }
        boolean valid() {
            if(external!=null)return player.serverLevel()==level && external.valid() && ConfigHandler.GENERAL.allowTeleport.get();
            if(returning)return allowedTravel(player,stack) && player.serverLevel()==level;
            var compass=ExplorersCompass.explorersCompass;
            return eligible(player,stack,token) && player.serverLevel()==level && key.equals(target(stack))
                && x==compass.getFoundStructureX(stack) && z==compass.getFoundStructureZ(stack)
                && (legacyHeight || sourceY==stack.getTag().getInt(SearchService.FOUND_Y));
        }
    }
    private TeleportService() {}
    /** 生物模块复用同一落点扫描、票据、二次确认与最终保护，不复制传送算法。 */
    public static boolean requestLocator(ServerPlayer player,ItemStack stack,UUID token,LocatorTarget target,boolean fallback) {
        if(!target.valid() || !ConfigHandler.GENERAL.allowTeleport.get())return false;
        Task task=tasks.get(player.getUUID());
        if(task==null || task.external==null || !task.token.equals(token) || !task.valid() || task.handoffUntil>0) {
            cancel(player.getUUID());task=new Task(player,stack,token,target.position());task.external=target;
            task.returnYaw=player.getYRot();task.returnPitch=player.getXRot();tasks.put(player.getUUID(),task);
        }
        if(task.clicked)return true;
        if(task.mode==LandingMode.SAFE_ONLY)fallback=false;
        if(task.mode==LandingMode.RELAXED_ONLY)fallback=true;
        if(task.mode==LandingMode.DEFAULT && task.waiting && System.nanoTime()>task.confirmationUntil){task.waiting=false;fallback=false;}
        if(task.mode==LandingMode.DEFAULT && fallback && !task.waiting)return false;
        if(task.mode==LandingMode.DEFAULT && task.waiting && !fallback){task.external.state("confirm",true);return true;}
        task.clicked=true;task.waiting=false;task.fallback=fallback;task.phase="";task.column=0;
        task.heights=fallback?task.relaxedHeights:task.strictHeights;task.landing=fallback?task.relaxedLanding:task.strictLanding;
        long now=System.nanoTime();task.attemptStarted=now;task.waitStarted=now;task.checkNanos=0;task.waitNanos=0;
        task.requestDeadline=now+REQUEST_NANOS;task.centerDeadline=now+WAIT_NANOS;task.expires=player.getServer().getTickCount()+200;
        phase(task,"waiting_chunks");return true;
    }
    public static LandingMode mode(UUID player) {return modes.getOrDefault(player,LandingMode.DEFAULT);}
    public static void setMode(UUID player,LandingMode mode) {
        if(modes.put(player,mode)==mode)return;
        Task task=tasks.get(player);if(task==null || task.handoffUntil>0)return;
        // 不丢弃已就绪区块和候选，但旧的确认授权立即失效。
        task.mode=mode;
        if(task.travel) {beginTravelStage(task,mode==LandingMode.RELAXED_ONLY);return;}
        task.waiting=false;task.clicked=false;task.fallback=mode==LandingMode.RELAXED_ONLY;
        task.heights=task.fallback?task.relaxedHeights:task.strictHeights;task.column=0;
        task.landing=task.fallback?task.relaxedLanding:task.strictLanding;
        task.expires=task.player.getServer().getTickCount()+100;
        task.requestDeadline=0;task.checkNanos=0;
        status(task,TeleportStatusPacket.State.CANCELLED,"mode_changed");
    }
    private static ResourceLocation target(ItemStack stack) {
        return SearchService.isBiome(stack)?ResourceLocation.tryParse(stack.getTag().getString(SearchService.BIOME_KEY)):ExplorersCompass.explorersCompass.getStructureKey(stack);
    }
    private static boolean baseEligible(ServerPlayer player,ItemStack stack) {
        if(player.isRemoved() || !player.isAlive() || stack.isEmpty() || stack.getItem()!=ExplorersCompass.explorersCompass
            || (player.getMainHandItem()!=stack && player.getOffhandItem()!=stack)
            || ExplorersCompass.explorersCompass.getState(stack)!=CompassState.FOUND
            || !ConfigHandler.GENERAL.allowTeleport.get()) return false;
        if(stack.getTag()==null || target(stack)==null) return false;
        String dimension=stack.getTag().getString(SearchService.TARGET_DIMENSION);
        return (dimension.isEmpty() && !SearchService.isBiome(stack)) || dimension.equals(player.level().dimension().location().toString());
    }
    public static boolean eligible(ServerPlayer player,ItemStack stack,UUID token) {
        return token!=null && baseEligible(player,stack) && token.equals(SearchService.token(stack));
    }
    public static void prepare(ServerPlayer player,ItemStack stack,UUID token) {
        if(!eligible(player,stack,token))return;
        cancel(player.getUUID());tasks.put(player.getUUID(),new Task(player,stack,token));
    }
    public static boolean start(ServerPlayer player,ItemStack stack,UUID token) {
        return request(player,stack,token,UUID.randomUUID(),false);
    }
    public static boolean request(ServerPlayer player,ItemStack stack,UUID supplied,UUID requestId,boolean fallback) {
        if(!baseEligible(player,stack)) {message(player,"filters.teleport_unavailable");return false;}
        UUID actual=SearchService.token(stack);
        if(supplied!=null && !supplied.equals(actual) || supplied==null && actual!=null) return false;
        // 旧结构目标首次请求由服务端赋标识；客户端不能把第一次点击伪装成降级。
        if(actual==null) {
            if(SearchService.isBiome(stack) || fallback && mode(player.getUUID())!=LandingMode.RELAXED_ONLY)return false;
            actual=UUID.randomUUID();stack.getOrCreateTag().putUUID("ECFiltersSearchToken",actual);
        }
        if(!stack.getTag().contains(SearchService.TARGET_DIMENSION))stack.getTag().putString(SearchService.TARGET_DIMENSION,player.level().dimension().location().toString());
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
        if(fallback && task.mode==LandingMode.DEFAULT && !task.waiting) {status(task,TeleportStatusPacket.State.CANCELLED,"confirmation_required");return false;}
        if(task.waiting && !fallback && task.mode==LandingMode.DEFAULT) {status(task,TeleportStatusPacket.State.NEEDS_CONFIRMATION,"confirm");return true;}
        boolean retry=task.waiting;
        task.waiting=false;task.clicked=true;task.fallback=fallback;task.phase="";
        if(retry && !fallback && Arrays.stream(task.strictHeights).allMatch(h->h>=33)) {
            Arrays.fill(task.strictHeights,0);task.strictLanding=null;task.landing=null;
        }
        if(retry && fallback && Arrays.stream(task.relaxedHeights).allMatch(h->h>=129)
                && Arrays.stream(task.surfaceHeights).allMatch(h->h>=1)) {
            Arrays.fill(task.relaxedHeights,0);Arrays.fill(task.surfaceHeights,0);task.relaxedLanding=null;
        }
        task.heights=fallback?task.relaxedHeights:task.strictHeights;task.column=0;
        task.requestDeadline=now+REQUEST_NANOS;task.attemptStarted=now;task.waitStarted=now;task.waitNanos=0;task.checkNanos=0;task.centerDeadline=now+WAIT_NANOS;
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
        UUID token=SearchService.token(stack);
        if(!eligible(player,stack,token))return false;
        Task prepared=tasks.get(player.getUUID());
        if(prepared==null || !prepared.valid() || !prepared.token.equals(token) || prepared.handoffUntil>0)prepare(player,stack,token);
        Task task=tasks.get(player.getUUID());boolean resumed=task.travel;
        task.travel=true;task.completion=completion;task.detailedCompletion=detailed;
        if(task.budgetPaused){
            // 同一逻辑落点任务继续下一段CPU预算；不重置总期限、中心等待或高度游标。
            task.budgetPaused=false;task.clicked=true;task.waiting=false;task.checkNanos=0;
            phase(task,"height_probe");return true;
        }
        beginTravelStage(task,resumed?task.fallback:task.mode==LandingMode.RELAXED_ONLY);return true;
    }
    public static boolean allowedTravel(ServerPlayer player,ItemStack stack) {
        return !player.isRemoved() && player.isAlive() && stack.getItem()==ExplorersCompass.explorersCompass
                && (player.getMainHandItem()==stack || player.getOffhandItem()==stack)
                && ConfigHandler.GENERAL.allowTeleport.get();
    }
    /** 返回也复用同一票据、落点检查与调度预算，但不写目标和不累计次数。 */
    public static boolean returnTravel(ServerPlayer player,ItemStack stack,Vec3 origin,float yaw,float pitch,
                                        java.util.function.Consumer<Boolean> completion) {
        if(!allowedTravel(player,stack))return false;
        cancel(player.getUUID());
        UUID token=SearchService.token(stack);
        Task task=new Task(player,stack,token==null?UUID.randomUUID():token,BlockPos.containing(origin));
        task.travel=true;
        task.clicked=true;task.completion=result->completion.accept(result==TravelResult.MOVED);task.returnYaw=yaw;task.returnPitch=pitch;
        task.requestDeadline=System.nanoTime()+REQUEST_NANOS;task.attemptStarted=System.nanoTime();
        task.waitStarted=System.nanoTime();task.expires=player.getServer().getTickCount()+200;
        if(LandingChecks.ready(player,task.level,BlockPos.containing(origin),false)) {
            if(LandingChecks.standing(player,task.level,ItemStack.EMPTY,origin,false))task.strictLanding=origin;
            // 降级返回也先复用原坐标，避免切换阶段时丢失已验证的出发位置。
            if(LandingChecks.standing(player,task.level,ItemStack.EMPTY,origin,true)
                    || LandingChecks.waterStanding(player,task.level,ItemStack.EMPTY,origin))task.relaxedLanding=origin;
        }
        tasks.put(player.getUUID(),task);beginTravelStage(task,task.mode==LandingMode.RELAXED_ONLY);return true;
    }
    private static void beginTravelStage(Task task,boolean relaxed) {
        task.fallback=relaxed;task.clicked=true;task.waiting=false;task.phase="";
        task.heights=relaxed?task.relaxedHeights:task.strictHeights;task.column=0;
        task.landing=relaxed?task.relaxedLanding:task.strictLanding;
        long now=System.nanoTime();task.requestDeadline=now+REQUEST_NANOS;
        task.centerDeadline=now+(task.travel?60_000_000_000L:WAIT_NANOS);task.attemptStarted=now;task.checkNanos=0;
        if(task.travel)task.requestDeadline=now+70_000_000_000L;
        task.waitStarted=now;task.waitNanos=0;task.expires=task.player.getServer().getTickCount()+200;
        phase(task,task.ticket?"waiting_chunks":"waiting_slots");
    }
    private static ItemStack landingStack(Task task){return task.returning?ItemStack.EMPTY:task.stack;}
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
    private static boolean aetherBiome(Task task){return task.biome && task.level.dimension().location().toString().equals("aether:the_aether");}
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
            if(task.clicked && System.nanoTime()>=task.requestDeadline) {
                failed(task,!task.ticket?"waiting_slots":!windowReady(task)?"chunks_not_ready":"timeout");
            } else if(!task.clicked && task.player.getServer().getTickCount()>=task.expires) finish(task,"timeout",0);
        }
        for(Task task:tasks.values().stream().filter(t->!t.waiting && t.handoffUntil==0).sorted(Comparator.comparing(t->!t.clicked)).toList()) {
            if(task.ticket)continue;
            if(ticketCount()>=4 && task.clicked)tasks.values().stream().filter(t->t.ticket && !t.clicked && t.handoffUntil==0).findFirst().ifPresent(TeleportService::release);
            if(ticketCount()>=4)break;
            acquire(task,task.center);
        }
    }
    private static void acquire(Task task,ChunkPos center) {
        release(task);task.center=center;task.centerDeadline=System.nanoTime()+(task.travel?60_000_000_000L:WAIT_NANOS);task.chunksReady=0;
        if(task.clicked && task.waitStarted==0)task.waitStarted=System.nanoTime();
        if(task.clicked)phase(task,"waiting_chunks");
        task.lastCenterTick=task.player.getServer().getTickCount();task.level.getChunkSource().addRegionTicket(PREPARE,center,1,task.ticketId);task.ticket=true;
    }
    private static void release(Task task) {
        if(task.ticket){task.level.getChunkSource().removeRegionTicket(PREPARE,task.center,1,task.ticketId);task.ticket=false;}
    }
    private static boolean windowReady(Task task) {
        for(int x=task.center.x-1;x<=task.center.x+1;x++)
            for(int z=task.center.z-1;z<=task.center.z+1;z++)
                if(task.level.getChunkSource().getChunkNow(x,z)==null)return false;
        return true;
    }
    public static boolean step() {
        List<Task> choices=tasks.values().stream().filter(t->!t.waiting && t.handoffUntil==0 && t.clicked).toList();
        if(choices.isEmpty())choices=tasks.values().stream().filter(t->!t.waiting && t.handoffUntil==0 && t.landing==null).toList();
        if(choices.isEmpty())return false;
        Task task=choices.get(Math.floorMod(cursor++,choices.size()));
        if(!task.valid()){finish(task,"cancelled",0);return true;}
        if(!task.ticket)return false;
        // 等待中心固定，不能在生成线程尚未推进前反复搬走同一组票据。
        if(!windowReady(task)) {
            if(task.clicked && System.nanoTime()>=task.centerDeadline)failed(task,"chunks_not_ready");
            return false;
        }
        if(task.chunksReady==0) {
            task.chunksReady=System.nanoTime();
            if(task.waitStarted!=0){task.waitNanos+=task.chunksReady-task.waitStarted;task.waitStarted=0;}
            if(task.clicked)phase(task,"checking");
        }
        long quantum=System.nanoTime()+250_000L;
        try {
            if(task.treasure)return treasureStep(task);
            if(task.legacyHeight && !task.heightResolved) {
                var chunk=task.level.getChunkSource().getChunkNow(task.x>>4,task.z>>4);
                if(chunk==null)return false;
                task.origin=task.origin.atY(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,task.x&15,task.z&15)+1);task.heightResolved=true;
            }
            if(aetherBiome(task)) {
                if(task.heightProbe==null)task.heightProbe=new AetherHeightProbe(task.player,task.stack,task.key,task.origin,task.fallback);
                if(task.heightProbe.pending(task.fallback)) {
                    phase(task,"height_probe");long before=System.nanoTime();
                    Vec3 found=task.heightProbe.step(quantum,task.fallback);task.checkNanos+=System.nanoTime()-before;
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
                    if(task.clicked && task.checkNanos>=(task.fallback?40_000_000L:80_000_000L)){
                        if(task.travel){
                            summary(task,"probe_budget_yield");task.clicked=false;task.waiting=true;task.budgetPaused=true;
                            task.confirmationUntil=Long.MAX_VALUE;task.expires=task.player.getServer().getTickCount()+1200;
                            completed(task,TravelResult.BUDGET_EXHAUSTED);
                        }else failed(task,"scan_budget");
                    }
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
                if(!LandingChecks.ready(task.player,task.level,point,!task.fallback && task.biome)) {
                    if(missing==null)missing=new ChunkPos(point);
                    continue;
                }
                int y=surface?task.level.getChunkSource().getChunkNow(x>>4,z>>4)
                        .getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x&15,z&15)+1
                        :task.origin.getY()+LandingColumns.vertical(progress[index]);
                long before=System.nanoTime();Vec3 landing;
                try {
                    point=point.atY(y);progress[index]++;
                    landing=LandingChecks.find(task.player,task.level,landingStack(task),point,task.fallback);
                    if(landing==null && surface && task.fallback)landing=LandingChecks.waterSurface(task.player,task.level,landingStack(task),point);
                    if(landing!=null && (!travelBiome(task,landing) || !preferredTravelLanding(task,landing)))landing=null;
                    if(!task.fallback && task.relaxedLanding==null)
                        task.relaxedLanding=LandingChecks.find(task.player,task.level,task.stack,point,true);
                } finally {task.checkNanos+=System.nanoTime()-before;}
                work=true;
                if(landing!=null) {
                    task.landing=landing;
                    if(task.fallback)task.relaxedLanding=landing;else task.strictLanding=landing;
                    if(task.clicked)move(task);return true;
                }
                // 在一列内连续检查上下高度，不轮转四千列后才查看第二个 Y。
                if(!surface && progress[index]<count)task.column=index;
                if(task.clicked && task.checkNanos>=(task.fallback?40_000_000L:80_000_000L)) {
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
            if(!work && missing!=null && !missing.equals(task.center)
                    && task.lastCenterTick!=task.player.getServer().getTickCount()) {
                if(resumeReady(task,columns))return true;
                acquire(task,missing);return false;
            }
            return work;
        } catch(RuntimeException error) {
            ExplorersCompass.LOGGER.warn("落点检查失败",error);
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
    /** 宝藏只检查真实箱子列的地表，绝不进入外围列或地下落点。 */
    private static boolean treasureStep(Task task) {
        var chunk=task.level.getChunkSource().getChunkNow(task.x>>4,task.z>>4);
        if(chunk==null)return false;
        int y=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,task.x&15,task.z&15)+1;
        BlockPos point=new BlockPos(task.x,y,task.z);
        if(!LandingChecks.ready(task.player,task.level,point,false))return false;
        if(task.chunksReady==0)task.chunksReady=System.nanoTime();
        Vec3 landing=LandingChecks.find(task.player,task.level,landingStack(task),point,task.fallback);
        if(landing==null) {if(task.clicked)failed(task,"no_position");else finish(task,"no_position",0);return true;}
        task.landing=landing;if(task.fallback)task.relaxedLanding=landing;else task.strictLanding=landing;
        if(task.clicked)move(task);return true;
    }
    private static boolean recheck(Task task,Vec3 point,boolean relaxed) {
        return (task.external==null || task.external.accepts(point)) && travelBiome(task,point) && (LandingChecks.standing(task.player,task.level,landingStack(task),point,relaxed)
                || relaxed && LandingChecks.waterStanding(task.player,task.level,landingStack(task),point));
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
        summary(task,reason);task.clicked=false;task.waiting=true;task.confirmationUntil=System.nanoTime()+CONFIRM_NANOS;task.expires=task.player.getServer().getTickCount()+100;
        boolean confirm=!task.fallback && task.mode==LandingMode.DEFAULT;
        status(task,confirm?TeleportStatusPacket.State.NEEDS_CONFIRMATION:TeleportStatusPacket.State.FAILED_RETRYABLE,reason);
        if(task.player.connection!=null)task.player.displayClientMessage(Component.translatable(confirm?"filters.teleport_confirm":"filters.teleport_retry")
                .append(" ").append(Component.translatable("filters.teleport_reason_"+reason)),true);
    }
    private static void phase(Task task,String phase) {
        if(task.phase.equals(phase))return;task.phase=phase;
        status(task,task.fallback?TeleportStatusPacket.State.FALLBACK_PREPARING:TeleportStatusPacket.State.PREPARING,phase);
    }
    private static void status(Task task,TeleportStatusPacket.State state,String reason) {
        if(task.external!=null){task.external.state(reason,state==TeleportStatusPacket.State.NEEDS_CONFIRMATION);return;}
        UnifiedNetwork.send(task.player,new TeleportStatusPacket(task.token,state,++revision,reason));
    }
    private static void move(Task task) {
        if(!task.valid() || task.player.connection==null){finish(task,"cancelled",0);return;}
        LandingSafety.invalidate(task.level);
        if(!recheck(task,task.landing,task.fallback)){task.landing=null;return;}
        Vec3 point=task.landing;task.player.stopRiding();task.player.connection.teleport(point.x,point.y,point.z,task.returning?task.returnYaw:task.player.getYRot(),task.returning?task.returnPitch:task.player.getXRot());
        task.player.setDeltaMovement(0,0,0);task.player.fallDistance=0;
        if(aetherBiome(task) && !task.key.equals(loadedBiome(task.player))) {
            // 其它模组可能改写实际落点。先保留同一逻辑请求做高度复核，不发成功计数或旧回调。
            task.origin=task.player.blockPosition().immutable();task.heightProbe=new AetherHeightProbe(task.player,task.stack,task.key,task.origin,task.fallback);task.landing=null;
            phase(task,"height_probe");return;
        }
        task.level.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT,new ChunkPos(BlockPos.containing(point)),1,task.player.getId());
        if(!task.returning)TeleportEvents.succeeded(task.player,task.biome?TeleportSucceededPacket.Kind.BIOME:TeleportSucceededPacket.Kind.STRUCTURE,task.key,task.level.dimension().location(),point.x,point.y,point.z);
        if(task.external!=null)task.external.moved();
        boolean water=LandingChecks.waterStanding(task.player,task.level,task.stack,point);
        if(water)message(task.player,"filters.teleport_water_surface");
        status(task,TeleportStatusPacket.State.SUCCEEDED,water?"water_moved":task.fallback?"fallback_moved":"moved");
        summary(task,water?"water_moved":"moved");
        if(task.heightProbe!=null)ExplorersCompass.LOGGER.info("天境高度复核 {} biome={} {}",task.token,task.key,task.heightProbe.summary());
        task.clicked=false;task.waiting=true;task.handoffUntil=task.player.getServer().getTickCount()+40;
        completed(task,TravelResult.MOVED);
    }
    private static void summary(Task task,String result) {
        long now=System.nanoTime(),waiting=task.waitNanos+(task.waitStarted==0?0:now-task.waitStarted);
        ExplorersCompass.LOGGER.info("传送 {} 阶段摘要：result={}, phase={}, elapsed_ms={}, waiting_ms={}, checking_ms={}",
                task.token,result,task.phase,(now-task.attemptStarted)/1e6,waiting/1e6,task.checkNanos/1e6);
        if(task.heightProbe!=null)ExplorersCompass.LOGGER.info("天境高度复核诊断 {} biome={} {}",task.token,task.key,task.heightProbe.summary());
    }
    public static boolean safe(ServerPlayer player,ServerLevel level,ItemStack stack,BlockPos point) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("落点检查必须在服务端线程");
        return LandingChecks.find(player,level,stack,point,false)!=null;
    }
    public static Vec3 relaxed(ServerPlayer player,ServerLevel level,ItemStack stack,BlockPos point) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("落点检查必须在服务端线程");
        return LandingChecks.find(player,level,stack,point,true);
    }
    public static boolean safeAt(ServerPlayer player,ServerLevel level,ItemStack stack,Vec3 point) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("落点检查必须在服务端线程");
        return LandingChecks.standing(player,level,stack,point,false);
    }
    private static void finish(Task task,String reason,long moved) {
        if(tasks.remove(task.player.getUUID(),task)) {
            release(task);
            if(moved==0 && task.handoffUntil==0)status(task,TeleportStatusPacket.State.CANCELLED,reason);
            completed(task,reason.equals("cancelled")?TravelResult.CANCELLED:reason.equals("error")?TravelResult.ERROR:TravelResult.NO_POSITION);
        }
    }
    public static void cancel(UUID player){Task t=tasks.get(player);if(t!=null)finish(t,"cancelled",0);}
    public static void forget(UUID player){cancel(player);requests.remove(player);modes.remove(player);}
    public static void shutdown(){clear();modes.clear();}
    public static void clear(){for(Task t:List.copyOf(tasks.values()))finish(t,"cancelled",0);requests.clear();cursor=0;}
    private static void message(ServerPlayer player,String key){if(player.connection!=null)player.displayClientMessage(Component.translatable(key),true);}
}
