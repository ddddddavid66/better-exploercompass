package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.unified.entertainment.AdventureRequirements;
import com.chaosthedude.explorerscompass.unified.search.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/** 真实tick下仅验收两个垂直重叠目标；不授予条件、不放置方块、不运行完整成就旅行。 */
@Mod.EventBusSubscriber(modid="filterschecks",value=Dist.CLIENT)
public final class Aether121Checks {
    private enum Phase { WAIT, PREPARE, NORMAL, NORMAL_WAIT, TRAVEL_WAIT, LAND, ARRIVAL, DIAGNOSE, RELEASE, RETURN, RETURN_WAIT, DONE }
    private static final ResourceLocation AETHER=new ResourceLocation("aether:the_aether");
    private static final ResourceLocation ADVANCEMENT=new ResourceLocation("ancient_aether:aether_biomes");
    private static final List<ResourceLocation> TARGETS=List.of(new ResourceLocation("ancient_aether:wyndcap_highland"),new ResourceLocation("ancient_aether:frozen_caverns"));
    private static final List<BlockPos> STARTS=List.of(new BlockPos(11584,96,1862),new BlockPos(40,108,-24));
    private static final TicketType<UUID> SETUP=TicketType.create("unified_aether121_setup",Comparator.<UUID>naturalOrder());
    private static final UUID setupId=UUID.randomUUID();
    private static final List<String> notes=new ArrayList<>();
    private static final List<String> failedTargets=new ArrayList<>();
    private static final java.util.Map<ResourceLocation,Set<String>> baseline=new java.util.HashMap<>();
    private static volatile UUID playerId;
    private static volatile int selectedSlot=-1;
    private static volatile String finished;
    private static volatile Throwable failure;
    private static boolean opened,closed,setupTicket;
    private static Phase phase=Phase.WAIT;
    private static int targetIndex,startIndex,column,height,arrivalTicks,releaseTicks;
    private static long deadline=System.nanoTime()+900_000_000_000L,stageStarted;
    private static ServerLevel level;
    private static ChunkPos center;
    private static Vec3 departure;
    private static BlockPos queryOrigin,normalResult;
    private static BiomeQueryFactory.Plan frozen;
    private static SearchService.BiomeRequest request;
    private static List<AdventureRequirements.Visit> visits;
    private static int candidateAttempts;
    private static final Set<BlockPos> failedCandidates=new java.util.LinkedHashSet<>(),attemptedCandidates=new java.util.LinkedHashSet<>();
    private static ColumnDiagnosis diagnosis;

    private Aether121Checks() {}
    @SubscribeEvent public static void client(TickEvent.ClientTickEvent event) {
        if(!Boolean.getBoolean("unified.aether121") || event.phase!=TickEvent.Phase.END || closed)return;
        Minecraft mc=Minecraft.getInstance();
        try {
            if(failure!=null)throw new AssertionError("server test failed in "+phase,failure);
            if(System.nanoTime()>deadline)throw new AssertionError("minimal test deadline; phase="+phase);
            if(finished!=null){close(mc,finished);return;}
            if(!opened) {
                if(mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)mc.setScreen(new TitleScreen());
                if(!(mc.screen instanceof TitleScreen))return;
                mc.options.renderDistance().set(3);mc.options.simulationDistance().set(5);
                mc.options.pauseOnLostFocus=false;
                mc.options.guiScale().set(2);mc.resizeDisplay();
                mc.createWorldOpenFlows().loadLevel(mc.screen,"aether121-save");opened=true;return;
            }
            if(mc.player!=null && mc.getSingleplayerServer()!=null)playerId=mc.player.getUUID();
            if(mc.player!=null && selectedSlot>=0 && mc.player.getInventory().selected!=selectedSlot) {
                mc.player.getInventory().selected=selectedSlot;
                if(mc.getConnection()!=null)mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(selectedSlot));
            }
            if(mc.screen!=null && mc.player!=null)mc.setScreen(null);
        }catch(Throwable error){error.printStackTrace();close(mc,"FAIL: "+error+"\n"+String.join("\n",notes));}
    }

    @SubscribeEvent public static void server(TickEvent.ServerTickEvent event) {
        if(!Boolean.getBoolean("unified.aether121") || event.phase!=TickEvent.Phase.END || failure!=null || finished!=null)return;
        var server=ServerLifecycleHooks.getCurrentServer();if(server==null)return;
        if(playerId==null && server.getPlayerList().getPlayers().size()==1)playerId=server.getPlayerList().getPlayers().get(0).getUUID();
        var player=playerId==null?null:server.getPlayerList().getPlayer(playerId);
        if(player==null && server.getPlayerList().getPlayers().size()==1)player=server.getPlayerList().getPlayers().get(0);
        if(player==null)return;
        try {step(player);}catch(Throwable error){failure=error;error.printStackTrace();cleanup(player);}
    }

    private static void step(ServerPlayer player)throws Exception {
        switch(phase) {
            case WAIT->setup(player);
            case PREPARE->prepareOrigin(player);
            case NORMAL->startNormal(player);
            case NORMAL_WAIT->normalState(player);
            case TRAVEL_WAIT->travelState(player);
            case LAND->land(player);
            case ARRIVAL->arrival(player);
            case DIAGNOSE->{
                if(!diagnosis.step(System.nanoTime()+2_000_000L))return;
                if(candidateAttempts>=5){
                    failedTargets.add(TARGETS.get(targetIndex)+": five genuinely different candidates lacked a usable landing");
                    note("FAIL target_landing "+failedTargets.get(failedTargets.size()-1));
                    SearchService.cancel(player,true);releaseTicks=0;phase=Phase.RELEASE;return;
                }
                BlockPos old=request.candidate().position();
                // 与生产旅行相同，仅排除失败的精确XYZ；不把只读诊断提升为整列不存在的证明。
                failedCandidates.add(old);
                note("candidate_retry previous="+old+"; exclusion=exact_xyz; next="+(candidateAttempts+1)+"/5; genuine_timeouts=0");
                startTravelRequest(player);
            }
            case RELEASE->{
                if(++releaseTicks<3)return;
                check(TeleportService.ticketCount()==0,"target landing tickets released");
                check(SearchService.activeCount()==0,"target search session released");
                if(++targetIndex<TARGETS.size())phase=Phase.NORMAL;else phase=Phase.RETURN;
            }
            case RETURN->{
                phase=Phase.RETURN_WAIT;
                check(TeleportService.returnTravel(player,player.getMainHandItem(),departure,0,0,success->{
                    if(!success){failure=new AssertionError("return to prepared departure failed");return;}
                    try {
                        check(player.position().distanceTo(departure)<4,"departure restored");
                        SearchService.cancel(player,true);
                        check(TeleportService.ticketCount()==0,"all final landing tickets released");
                        note("PASS return "+player.position()+"; tickets=0; only two target journeys");
                        phase=Phase.DONE;finished=(failedTargets.isEmpty()?"PASS":"PARTIAL_FAILURE "+failedTargets)+"\n"+String.join("\n",notes);
                    }catch(Throwable error){failure=error;}
                }),"return accepted");
            }
            default->{ }
        }
    }

    private static void setup(ServerPlayer player)throws Exception {
        check(player.gameMode.getGameModeForPlayer()==GameType.SURVIVAL,"survival player");
        ensureCompass(player);
        check(TeleportService.allowedTravel(player,player.getMainHandItem()),"normal travel permitted");
        level=player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,AETHER));check(level!=null,"full-pack Aether dimension");
        var advancement=player.getServer().getAdvancements().getAdvancement(ADVANCEMENT);check(advancement!=null,"real Ancient Aether advancement");
        visits=AdventureRequirements.read(advancement);
        for(var target:TARGETS) {
            var criteria=visits.stream().filter(visit->visit.biome().equals(target)).toList();
            check(!criteria.isEmpty(),"real criterion for "+target);
            var completed=criteria.stream().filter(visit->AdventureRequirements.visited(player,advancement,visit))
                    .map(AdventureRequirements.Visit::criterion).collect(java.util.stream.Collectors.toSet());
            baseline.put(target,Set.copyOf(completed));
            note("advancement_baseline target="+target+"; completed="+completed.size()+"/"+criteria.size()+"; criteria="+completed);
            check(level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream().anyMatch(holder->holder.unwrapKey().map(key->key.location().equals(target)).orElse(false)),"target participates in actual source "+target);
        }
        TeleportService.setMode(player.getUUID(),ConfigHandler.CLIENT.landingMode.get());
        note("setup player="+playerId+"; seed="+level.getSeed()+"; mode="+TeleportService.mode(playerId)+"; commands="+player.getServer().getWorldData().getAllowCommands()+"; source="+level.getChunkSource().getGenerator().getBiomeSource().getClass().getName());
        acquireSetup();phase=Phase.PREPARE;
    }
    /** 测试只准备合法手持输入；不绕过生产服务的手持、耐久或维度检查。 */
    private static void ensureCompass(ServerPlayer player) {
        if(player.getMainHandItem().getItem()!=ExplorersCompass.explorersCompass) {
            int slot=-1;
            for(int i=0;i<player.getInventory().items.size();i++)if(player.getInventory().items.get(i).getItem()==ExplorersCompass.explorersCompass){slot=i;break;}
            if(slot>=0) {
                var old=player.getMainHandItem();player.setItemInHand(InteractionHand.MAIN_HAND,player.getInventory().items.get(slot));player.getInventory().items.set(slot,old);
            } else player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(ExplorersCompass.explorersCompass));
        }
        selectedSlot=player.getInventory().selected;
    }

    private static void acquireSetup() {
        center=new ChunkPos(STARTS.get(startIndex));
        level.getChunkSource().addRegionTicket(SETUP,center,1,setupId);setupTicket=true;
        stageStarted=System.nanoTime();column=0;height=Math.min(140,level.getMaxBuildHeight()-3);
    }
    private static void prepareOrigin(ServerPlayer player)throws Exception {
        boolean ready=true;
        for(int x=center.x-1;x<=center.x+1;x++)for(int z=center.z-1;z<=center.z+1;z++)if(level.getChunkSource().getChunkNow(x,z)==null)ready=false;
        check(System.nanoTime()-stageStarted<60_000_000_000L,"setup nonblocking chunk readiness");if(!ready)return;
        long until=System.nanoTime()+2_000_000L;BlockPos start=STARTS.get(startIndex);
        // 起点也从真实地形取安全地面；没有同步getChunk，也不搭建人工平台。
        while(column<289 && System.nanoTime()<until) {
            int x=start.getX()+column%17-8,z=start.getZ()+column/17-8,y=height--;
            if(height<level.getMinBuildHeight()+4){column++;height=Math.min(140,level.getMaxBuildHeight()-3);}
            var floor=new BlockPos(x,y-1,z);
            if(level.getBlockState(floor).getCollisionShape(level,floor).isEmpty())continue;
            var point=new BlockPos(x,y,z);
            if(!TeleportService.safe(player,level,ItemStack.EMPTY,point))continue;
            Vec3 expected=new Vec3(x+.5,y,z+.5);player.teleportTo(level,expected.x,expected.y,expected.z,0,0);
            player.setDeltaMovement(Vec3.ZERO);player.fallDistance=0;
            releaseSetup();note("setup_move expected_dimension="+AETHER+"; expected_xyz="+expected
                    +"; actual_dimension="+player.serverLevel().dimension().location()+"; actual_xyz="+player.position());
            check(player.serverLevel()==level,"测试前置被整合包维度门禁拒绝：期望 "+AETHER+"，实际 "+player.serverLevel().dimension().location());
            check(player.position().distanceTo(expected)<2,"测试前置落点被整合包改写：期望 "+expected+"，实际 "+player.position());
            departure=player.position();note("low safe departure="+departure+"; biome="+level.getBiome(player.blockPosition()).unwrapKey().map(key->key.location().toString()).orElse("unknown"));
            phase=Phase.NORMAL;return;
        }
        if(column<289)return;
        releaseSetup();check(++startIndex<STARTS.size(),"a low naturally safe departure exists");acquireSetup();
    }

    private static void startNormal(ServerPlayer player)throws Exception {
        queryOrigin=player.blockPosition().immutable();ResourceLocation target=TARGETS.get(targetIndex);
        candidateAttempts=0;failedCandidates.clear();attemptedCandidates.clear();normalResult=null;
        var before=player.getMainHandItem();
        boolean possible=player.serverLevel().getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream()
                .anyMatch(holder->holder.unwrapKey().map(key->key.location().equals(target)).orElse(false));
        note("ordinary_preflight target="+target+"; held="+net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(before.getItem())
                +"; count="+before.getCount()+"; empty="+before.isEmpty()+"; broken="+SearchRules.broken(before)
                +"; selected="+player.getInventory().selected+"; current_dimension="+player.serverLevel().dimension().location()+"; possible="+possible);
        check(player.serverLevel()==level && possible,"actual player entered target dimension before search");
        ensureCompass(player);
        check(!SearchRules.broken(player.getMainHandItem()),"test compass usable");
        frozen=BiomeQueryFactory.prepare(level,queryOrigin,target);
        check(frozen.profile().aether(),"shared Aether profile "+target);
        if(targetIndex==0 && Boolean.getBoolean("unified.aether121.resume")) {
            check(Files.exists(Path.of("launch121-aether-3.log")),"preserved earlier ordinary/travel comparison evidence");
            note("ordinary_comparison_reused target="+target+"; source=launch121-aether-3.log; rerun_only_failed_landing=true; frozen="+frozen.profile().description());
            startTravelRequest(player);return;
        }
        check(SearchService.startBiome(player,player.getMainHandItem(),target),"ordinary search accepted "+target);
        request=SearchService.observeBiome(player);stageStarted=System.nanoTime();
        note("ordinary_start target="+target+"; origin="+queryOrigin+"; plan="+request.plan()+"; frozen="+frozen.profile().description());phase=Phase.NORMAL_WAIT;
    }
    private static void normalState(ServerPlayer player)throws Exception {
        if(request.state()==SearchService.BiomeRequest.State.RUNNING){check(!request.stalled(),"ordinary genuine no-progress timeout");return;}
        note("ordinary_terminal target="+TARGETS.get(targetIndex)+"; state="+request.state()+"; samples="+request.samples()+"; elapsed_ms="+(System.nanoTime()-stageStarted)/1e6+"; candidate="+request.candidate());
        check(request.state()==SearchService.BiomeRequest.State.FOUND,"ordinary target found "+TARGETS.get(targetIndex));normalResult=request.candidate().position();
        var observed=request.queryPlan();if(observed!=null)frozen=observed;
        startTravelRequest(player);
    }
    private static void startTravelRequest(ServerPlayer player)throws Exception {
        check(candidateAttempts<5,"no sixth landing candidate request");candidateAttempts++;
        request=SearchService.startTravelBiome(player,player.getMainHandItem(),TARGETS.get(targetIndex),failedCandidates,queryOrigin,
                new SearchService.SearchWindow(SearchConfig.RADIUS.get(),-1,SearchConfig.BIOME_SAMPLES.get(),frozen));
        stageStarted=System.nanoTime();note("travel_start target="+TARGETS.get(targetIndex)+"; candidate_attempt="+candidateAttempts+"/5; plan="+request.plan());phase=Phase.TRAVEL_WAIT;
    }
    private static void travelState(ServerPlayer player)throws Exception {
        if(request.state()==SearchService.BiomeRequest.State.RUNNING){check(!request.stalled(),"travel genuine no-progress timeout");return;}
        note("travel_terminal target="+TARGETS.get(targetIndex)+"; state="+request.state()+"; samples="+request.samples()+"; elapsed_ms="+(System.nanoTime()-stageStarted)/1e6+"; candidate="+request.candidate());
        check(request.state()==SearchService.BiomeRequest.State.FOUND,"travel target found "+TARGETS.get(targetIndex));
        if(candidateAttempts==1 && normalResult!=null)check(normalResult.equals(request.candidate().position()),"ordinary/travel same frozen-grid nearest XYZ");
        check(attemptedCandidates.add(request.candidate().position()),"candidate XYZ never repeated");
        check(request.queryPlan()==frozen,"travel reuses frozen profile identity");phase=Phase.LAND;
    }
    private static void land(ServerPlayer player)throws Exception {
        phase=Phase.ARRIVAL;arrivalTicks=-1;stageStarted=System.nanoTime();
        check(TeleportService.travelDetailed(player,player.getMainHandItem(),outcome->{
            try {
                var result=outcome.result();
                note("teleport_terminal target="+TARGETS.get(targetIndex)+"; result="+result+"; elapsed_ms="+(System.nanoTime()-stageStarted)/1e6+"; actual_xyz="+player.position()+"; actual_biome="+TeleportService.loadedBiome(player));
                if(result==TeleportService.TravelResult.BUDGET_EXHAUSTED){phase=Phase.LAND;return;}
                if(result==TeleportService.TravelResult.NO_POSITION) {
                    failedCandidates.addAll(outcome.provenUnusable());
                    note("proven_failed_column_samples="+outcome.provenUnusable().size()+"; budget_yield_is_not_absence=true");
                    diagnosis=new ColumnDiagnosis(player,request.candidate().position(),TARGETS.get(targetIndex));phase=Phase.DIAGNOSE;return;
                }
                if(result==TeleportService.TravelResult.TIMED_OUT){
                    failedTargets.add(TARGETS.get(targetIndex)+": real landing/chunk timeout");note("FAIL target_landing "+failedTargets.get(failedTargets.size()-1));
                    SearchService.cancel(player,true);releaseTicks=0;phase=Phase.RELEASE;return;
                }
                check(result==TeleportService.TravelResult.MOVED,"actual target teleport moved");arrivalTicks=0;
            }catch(Throwable error){failure=error;}
        }),"travel landing accepted");
    }
    private static void arrival(ServerPlayer player)throws Exception {
        if(arrivalTicks<0)return;
        check(player.serverLevel()==level,"never dropped into another dimension");
        ResourceLocation actual=TeleportService.loadedBiome(player);if(actual==null)return;
        check(actual.equals(TARGETS.get(targetIndex)),"actual loaded player biome matches after height correction: "+actual);
        check(player.getY()>level.getMinBuildHeight()+3,"above Aether falling transition");
        var advancement=player.getServer().getAdvancements().getAdvancement(ADVANCEMENT);
        var criteria=visits.stream().filter(visit->visit.biome().equals(TARGETS.get(targetIndex))).toList();
        if(criteria.stream().allMatch(visit->AdventureRequirements.visited(player,advancement,visit))) {
            var prior=baseline.getOrDefault(TARGETS.get(targetIndex),Set.of());
            var newly=criteria.stream().map(AdventureRequirements.Visit::criterion).filter(criterion->!prior.contains(criterion)).toList();
            note((newly.isEmpty()?"PASS actual visit and existing progress preserved":"PASS actual visit and natural criterion update")
                    +" target="+actual+"; criteria="+criteria.stream().map(AdventureRequirements.Visit::criterion).toList()
                    +"; baseline_completed="+prior+"; naturally_completed_this_run="+newly
                    +"; xyz="+player.position()+"; confirmation_ticks="+arrivalTicks);
            SearchService.cancel(player,true);releaseTicks=0;phase=Phase.RELEASE;return;
        }
        check(++arrivalTicks<=200,"natural advancement criterion update deadline");
    }

    private static void cleanup(ServerPlayer player) {releaseSetup();SearchService.cancel(player,true);}
    private static void releaseSetup(){if(setupTicket){level.getChunkSource().removeRegionTicket(SETUP,center,1,setupId);setupTicket=false;}}
    private static void note(String line)throws Exception {
        notes.add(line);ExplorersCompass.LOGGER.info("AETHER121 {}",line);
        Files.writeString(Path.of("aether121-results.txt"),"RUNNING\n"+String.join("\n",notes),StandardCharsets.UTF_8);
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void close(Minecraft mc,String report) {
        closed=true;
        try{Files.writeString(Path.of("aether121-results.txt"),report,StandardCharsets.UTF_8);}catch(Exception error){error.printStackTrace();}
        mc.stop();
    }

    /** 失败诊断只读取就绪柱，按tick分批记录真实目标高度及支撑；不主动加载额外区块。 */
    private static final class ColumnDiagnosis {
        private final ServerPlayer player;
        private final BlockPos anchor;
        private final ResourceLocation target;
        private final List<BlockPos> columns=new ArrayList<>();
        private int at,y,span=-1,targetCount,supportCount,waterCount,strictCount,relaxedCount;
        private final List<String> ranges=new ArrayList<>(),examples=new ArrayList<>();
        ColumnDiagnosis(ServerPlayer player,BlockPos anchor,ResourceLocation target) {
            this.player=player;this.anchor=anchor;this.target=target;
            var unique=new java.util.LinkedHashSet<BlockPos>();
            for(int dx:new int[]{0,-4,4})for(int dz:new int[]{0,-4,4})unique.add(anchor.offset(dx,0,dz));
            for(int gap:new int[]{8,16,32})for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                unique.add(anchor.offset(direction[0]*gap,0,direction[1]*gap));
            columns.addAll(unique);y=level.getMinBuildHeight()+4;
        }
        boolean step(long until)throws Exception {
            while(at<columns.size() && System.nanoTime()<until) {
                BlockPos column=columns.get(at);
                if(!ready(column)) {note("landing_column_diagnostic target="+target+"; xz="+column.getX()+","+column.getZ()+"; skipped=not_ready; scanned_until_y="+y);next();continue;}
                if(y>level.getMaxBuildHeight()-2) {if(span>=0)ranges.add(span+"-"+(y-1));
                    note("landing_column_diagnostic target="+target+"; xz="+column.getX()+","+column.getZ()+"; target_y="+ranges+"; target_blocks="+targetCount
                            +"; support_shape="+supportCount+"; water="+waterCount+"; strict_safe="+strictCount+"; relaxed_safe="+relaxedCount+"; examples="+examples);next();continue;}
                BlockPos point=column.atY(y++);
                boolean matches=level.getBiome(point).unwrapKey().map(key->key.location().equals(target)).orElse(false);
                if(!matches){if(span>=0){ranges.add(span+"-"+(point.getY()-1));span=-1;}continue;}
                if(span<0)span=point.getY();targetCount++;
                var floor=level.getBlockState(point.below());
                boolean support=!floor.getCollisionShape(level,point.below()).isEmpty();if(support)supportCount++;
                boolean water=level.getFluidState(point).is(net.minecraft.tags.FluidTags.WATER);if(water)waterCount++;
                boolean strict=TeleportService.safe(player,level,player.getMainHandItem(),point);if(strict)strictCount++;
                Vec3 relaxed=TeleportService.relaxed(player,level,player.getMainHandItem(),point);
                if(relaxed!=null && level.getBiome(BlockPos.containing(relaxed)).unwrapKey().map(key->key.location().equals(target)).orElse(false))relaxedCount++;
                if(examples.size()<8 && (support || water))examples.add("y="+point.getY()+",floor="+net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(floor.getBlock())
                        +",feet="+net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(level.getBlockState(point).getBlock())+",water="+water+",strict="+strict+",relaxed="+(relaxed!=null));
            }
            return at>=columns.size();
        }
        private boolean ready(BlockPos point) {
            for(int x=(point.getX()-4)>>4;x<=(point.getX()+4)>>4;x++)for(int z=(point.getZ()-4)>>4;z<=(point.getZ()+4)>>4;z++)
                if(level.getChunkSource().getChunkNow(x,z)==null)return false;
            return true;
        }
        private void next(){at++;y=level.getMinBuildHeight()+4;span=-1;targetCount=0;supportCount=0;waterCount=0;strictCount=0;relaxedCount=0;ranges.clear();examples.clear();}
    }
}
