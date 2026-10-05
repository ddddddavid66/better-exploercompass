package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.PlayerUtils;
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
    private static final TicketType<UUID> PREPARE=TicketType.create("compassfilters_landing",Comparator.<UUID>naturalOrder());
    private static final Map<UUID,Task> tasks=new LinkedHashMap<>();
    private static final Map<UUID,LinkedHashMap<UUID,Long>> requests=new HashMap<>();
    private static final Map<UUID,LandingMode> modes=new HashMap<>();
    private static long revision;
    private static int cursor;
    private static final long CONFIRM_NANOS=30_000_000_000L;
    private static final class Task {
        final ServerPlayer player; final ServerLevel level; final ItemStack stack;
        final UUID token,ticketId=UUID.randomUUID(); final ResourceLocation key;
        final boolean biome,legacyHeight,treasure; LandingMode mode; final int x,z,sourceY;
        BlockPos origin; ChunkPos center;
        final int[] strictHeights=new int[LandingColumns.STRICT.size()], relaxedHeights=new int[LandingColumns.RELAXED.size()];
        int[] heights=strictHeights;
        int expires,column,offset,localUntil; boolean ticket,clicked,fallback,waiting,surface;
        long chunksReady,confirmationUntil;
        Vec3 landing,strictLanding,relaxedLanding;
        Task(ServerPlayer player,ItemStack stack,UUID token) {
            this.player=player;level=player.serverLevel();this.stack=stack;this.token=token;
            var compass=ExplorersCompass.explorersCompass;
            biome=SearchService.isBiome(stack);key=target(stack);x=compass.getFoundStructureX(stack);z=compass.getFoundStructureZ(stack);
            legacyHeight=!stack.getTag().contains(SearchService.FOUND_Y);sourceY=stack.getTag().getInt(SearchService.FOUND_Y);
            treasure=!biome && key.toString().equals("minecraft:buried_treasure");
            mode=modes.getOrDefault(player.getUUID(),LandingMode.DEFAULT);fallback=mode==LandingMode.RELAXED_ONLY;
            heights=fallback?relaxedHeights:strictHeights;
            origin=new BlockPos(x,legacyHeight?player.blockPosition().getY():sourceY,z);center=new ChunkPos(origin);
            expires=player.getServer().getTickCount()+100;localUntil=player.getServer().getTickCount()+8;
        }
        boolean valid() {
            var compass=ExplorersCompass.explorersCompass;
            return eligible(player,stack,token) && player.serverLevel()==level && key.equals(target(stack))
                && x==compass.getFoundStructureX(stack) && z==compass.getFoundStructureZ(stack)
                && (legacyHeight || sourceY==stack.getTag().getInt(SearchService.FOUND_Y));
        }
    }
    private TeleportService() {}
    public static LandingMode mode(UUID player) {return modes.getOrDefault(player,LandingMode.DEFAULT);}
    public static void setMode(UUID player,LandingMode mode) {
        if(modes.put(player,mode)==mode)return;
        Task task=tasks.get(player);if(task==null)return;
        // 不丢弃已就绪区块和候选，但旧的确认授权立即失效。
        task.mode=mode;task.waiting=false;task.clicked=false;task.fallback=mode==LandingMode.RELAXED_ONLY;
        task.heights=task.fallback?task.relaxedHeights:task.strictHeights;task.column=0;task.surface=false;
        task.landing=task.fallback?task.relaxedLanding:task.strictLanding;
        task.expires=task.player.getServer().getTickCount()+100;task.localUntil=task.player.getServer().getTickCount()+8;
        status(task,TeleportStatusPacket.State.CANCELLED,"mode_changed");
    }
    private static ResourceLocation target(ItemStack stack) {
        return SearchService.isBiome(stack)?ResourceLocation.tryParse(stack.getTag().getString(SearchService.BIOME_KEY)):ExplorersCompass.explorersCompass.getStructureKey(stack);
    }
    private static boolean baseEligible(ServerPlayer player,ItemStack stack) {
        if(player.isRemoved() || !player.isAlive() || stack.isEmpty() || stack.getItem()!=ExplorersCompass.explorersCompass
            || (player.getMainHandItem()!=stack && player.getOffhandItem()!=stack)
            || ExplorersCompass.explorersCompass.getState(stack)!=CompassState.FOUND
            || !ConfigHandler.GENERAL.allowTeleport.get() || !PlayerUtils.canTeleport(player.getServer(),player)) return false;
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
        if(task.clicked)return true;
        if(task.waiting && now>=task.confirmationUntil) {finish(task,"expired",0);prepare(player,stack,actual);task=tasks.get(player.getUUID());}
        if(task.mode==LandingMode.SAFE_ONLY)fallback=false;
        if(task.mode==LandingMode.RELAXED_ONLY)fallback=true;
        if(fallback && task.mode==LandingMode.DEFAULT && !task.waiting) {status(task,TeleportStatusPacket.State.CANCELLED,"confirmation_required");return false;}
        if(task.waiting && !fallback && task.mode==LandingMode.DEFAULT) {status(task,TeleportStatusPacket.State.NEEDS_CONFIRMATION,"confirm");return true;}
        boolean retry=task.waiting;
        task.waiting=false;task.clicked=true;task.fallback=fallback;
        if(retry && !fallback) {Arrays.fill(task.strictHeights,0);task.strictLanding=null;task.landing=null;}
        task.heights=fallback?task.relaxedHeights:task.strictHeights;task.column=0;
        task.expires=player.getServer().getTickCount()+(fallback?20:40);task.localUntil=player.getServer().getTickCount()+8;
        task.landing=fallback?task.relaxedLanding:task.strictLanding;
        status(task,fallback?TeleportStatusPacket.State.FALLBACK_PREPARING:TeleportStatusPacket.State.PREPARING,"checking");
        message(player,"filters.teleport_checking");return true;
    }
    public static boolean active() {return tasks.values().stream().anyMatch(t->!t.waiting && (t.clicked || t.landing==null));}
    public static boolean awaitingConfirmation(UUID player) {Task t=tasks.get(player);return t!=null && t.waiting;}
    public static BlockPos prepared(UUID player) {Task t=tasks.get(player);return t==null || t.landing==null?null:BlockPos.containing(t.landing);}
    public static int ticketCount() {return (int)tasks.values().stream().filter(t->t.ticket).count();}
    public static void maintain() {
        for(Task task:List.copyOf(tasks.values())) {
            if(!task.valid()) {finish(task,"cancelled",0);continue;}
            if(task.waiting) {
                if(System.nanoTime()>=task.confirmationUntil)finish(task,"expired",0);
                else if(task.player.getServer().getTickCount()>=task.expires)release(task);
                continue;
            }
            if(task.player.getServer().getTickCount()>=task.expires) {
                if(task.clicked)failed(task,task.chunksReady==0?"chunks_not_ready":"timeout");else finish(task,"timeout",0);
            }
        }
        for(Task task:tasks.values().stream().filter(t->!t.waiting).sorted(Comparator.comparing(t->!t.clicked)).toList()) {
            if(task.ticket)continue;
            if(ticketCount()>=4 && task.clicked)tasks.values().stream().filter(t->t.ticket && !t.clicked).findFirst().ifPresent(TeleportService::release);
            if(ticketCount()>=4)break;
            acquire(task,task.center);
        }
    }
    private static void acquire(Task task,ChunkPos center) {
        release(task);task.center=center;task.level.getChunkSource().addRegionTicket(PREPARE,center,1,task.ticketId);task.ticket=true;
    }
    private static void release(Task task) {
        if(task.ticket){task.level.getChunkSource().removeRegionTicket(PREPARE,task.center,1,task.ticketId);task.ticket=false;}
    }
    public static boolean step() {
        List<Task> choices=tasks.values().stream().filter(t->!t.waiting && t.clicked).toList();
        if(choices.isEmpty())choices=tasks.values().stream().filter(t->!t.waiting && t.landing==null).toList();
        if(choices.isEmpty())return false;
        Task task=choices.get(Math.floorMod(cursor++,choices.size()));
        if(!task.valid()){finish(task,"cancelled",0);return true;}
        if(!task.ticket)return false;
        try {
            if(task.treasure) return treasureStep(task);
            if(task.legacyHeight && task.origin.getY()==task.sourceY)task.origin=task.origin.atY(task.player.blockPosition().getY());
            if(task.legacyHeight) {
                var chunk=task.level.getChunkSource().getChunkNow(task.x>>4,task.z>>4);
                if(chunk==null)return false;
                task.origin=task.origin.atY(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,task.x&15,task.z&15)+1);
            }
            if(task.landing!=null) {
                if(recheck(task,task.landing,task.fallback)){if(task.clicked)move(task);return false;}
                task.landing=null;
            }
            if(task.fallback && task.relaxedLanding!=null) {
                if(recheck(task,task.relaxedLanding,true)){task.landing=task.relaxedLanding;if(task.clicked)move(task);return true;}
                task.relaxedLanding=null;
            }
            if(task.fallback && !task.surface && task.player.getServer().getTickCount()>=task.localUntil) {
                task.surface=true;Arrays.fill(task.heights,0);task.column=0;
            }
            var columns=task.fallback?LandingColumns.RELAXED:LandingColumns.STRICT;
            int count=task.surface?1:task.fallback?129:33;
            ChunkPos missing=null;
            for(int attempt=0;attempt<Math.min(128,columns.size());attempt++) {
                int index=task.column;task.column=(task.column+1)%columns.size();
                if(task.heights[index]>=count)continue;
                var column=columns.get(index);int x=task.x+column.x(),z=task.z+column.z();
                BlockPos point=new BlockPos(x,task.origin.getY(),z);
                if(!LandingChecks.ready(task.player,task.level,point,!task.fallback && task.biome)) {if(missing==null)missing=new ChunkPos(point);continue;}
                if(task.chunksReady==0)task.chunksReady=System.nanoTime();
                int indexY=task.heights[index]++;
                int y=task.surface?task.level.getChunkSource().getChunkNow(x>>4,z>>4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x&15,z&15)+1:task.origin.getY()+LandingColumns.vertical(indexY);
                point=point.atY(y);task.offset++;
                Vec3 landing=LandingChecks.find(task.player,task.level,task.stack,point,task.fallback);
                if(landing!=null) {
                    task.landing=landing;
                    if(task.fallback)task.relaxedLanding=landing;else task.strictLanding=landing;
                    if(task.clicked)move(task);
                } else if(!task.fallback && task.relaxedLanding==null)task.relaxedLanding=LandingChecks.find(task.player,task.level,task.stack,point,true);
                return true;
            }
            if(Arrays.stream(task.heights).allMatch(h->h>=count)) {
                if(task.fallback && !task.surface){task.surface=true;Arrays.fill(task.heights,0);task.column=0;}
                else if(task.clicked)failed(task,"no_position");else finish(task,"no_position",0);
                return true;
            }
            // 外围按需移动同一个 3x3 ticket，不同步等待，也不同时准备整个搜索范围。
            if(task.fallback && missing!=null && !missing.equals(task.center)){acquire(task,missing);return true;}
            return false;
        } catch(RuntimeException error) {
            com.chaosthedude.explorerscompass.unified.CompassUnified.LOGGER.warn("落点检查失败",error);
            if(task.clicked)failed(task,"error");else finish(task,"error",0);return true;
        }
    }
    /** 宝藏只检查真实箱子列的地表，绝不进入外围列或地下落点。 */
    private static boolean treasureStep(Task task) {
        var chunk=task.level.getChunkSource().getChunkNow(task.x>>4,task.z>>4);
        if(chunk==null)return false;
        int y=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,task.x&15,task.z&15)+1;
        BlockPos point=new BlockPos(task.x,y,task.z);
        if(!LandingChecks.ready(task.player,task.level,point,false))return false;
        if(task.chunksReady==0)task.chunksReady=System.nanoTime();
        Vec3 landing=LandingChecks.find(task.player,task.level,task.stack,point,task.fallback);
        if(landing==null) {if(task.clicked)failed(task,"no_position");else finish(task,"no_position",0);return true;}
        task.landing=landing;if(task.fallback)task.relaxedLanding=landing;else task.strictLanding=landing;
        if(task.clicked)move(task);return true;
    }
    private static boolean recheck(Task task,Vec3 point,boolean relaxed) {
        return LandingChecks.standing(task.player,task.level,task.stack,point,relaxed);
    }
    private static void failed(Task task,String reason) {
        task.clicked=false;task.waiting=true;task.confirmationUntil=System.nanoTime()+CONFIRM_NANOS;task.expires=task.player.getServer().getTickCount()+100;
        boolean confirm=!task.fallback && task.mode==LandingMode.DEFAULT;
        status(task,confirm?TeleportStatusPacket.State.NEEDS_CONFIRMATION:TeleportStatusPacket.State.FAILED_RETRYABLE,reason);
        if(task.player.connection!=null)task.player.displayClientMessage(Component.translatable(confirm?"filters.teleport_confirm":"filters.teleport_retry")
                .append(" ").append(Component.translatable("filters.teleport_reason_"+reason)),true);
    }
    private static void status(Task task,TeleportStatusPacket.State state,String reason) {
        UnifiedNetwork.send(task.player,new TeleportStatusPacket(task.token,state,++revision,reason));
    }
    private static void move(Task task) {
        if(!task.valid() || task.player.connection==null){finish(task,"cancelled",0);return;}
        if(!recheck(task,task.landing,task.fallback)){task.landing=null;return;}
        Vec3 point=task.landing;task.player.stopRiding();task.player.connection.teleport(point.x,point.y,point.z,task.player.getYRot(),task.player.getXRot());
        task.player.setDeltaMovement(0,0,0);task.player.fallDistance=0;
        task.level.getChunkSource().addRegionTicket(TicketType.POST_TELEPORT,new ChunkPos(BlockPos.containing(point)),1,task.player.getId());
        TeleportEvents.succeeded(task.player,task.biome?TeleportSucceededPacket.Kind.BIOME:TeleportSucceededPacket.Kind.STRUCTURE,task.key,task.level.dimension().location(),point.x,point.y,point.z);
        status(task,TeleportStatusPacket.State.SUCCEEDED,task.fallback?"fallback_moved":"moved");finish(task,"moved",System.nanoTime());
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
            if(moved==0)status(task,TeleportStatusPacket.State.CANCELLED,reason);
        }
    }
    public static void cancel(UUID player){Task t=tasks.get(player);if(t!=null)finish(t,"cancelled",0);}
    public static void forget(UUID player){cancel(player);requests.remove(player);modes.remove(player);}
    public static void shutdown(){clear();modes.clear();}
    public static void clear(){for(Task t:List.copyOf(tasks.values()))finish(t,"cancelled",0);requests.clear();cursor=0;}
    private static void message(ServerPlayer player,String key){if(player.connection!=null)player.displayClientMessage(Component.translatable(key),true);}
}
