package com.chaosthedude.explorerscompass.unified.smoke;

import static com.chaosthedude.explorerscompass.unified.smoke.SmokeChecks.check;
import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.gui.ExplorersCompassScreen;
import com.chaosthedude.explorerscompass.unified.client.*;
import com.chaosthedude.explorerscompass.unified.data.LandingMode;
import com.chaosthedude.explorerscompass.unified.search.SearchService;
import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

final class Client102Checks {
    private static final ResourceLocation TREASURE=new ResourceLocation("minecraft:buried_treasure");
    private static boolean started,platform;
    private static volatile boolean platformReady;
    private static volatile Throwable error;
    private static UUID previous,request;
    private static int x,z;
    private static long count;
    private static CompletableFuture<Long> flushed;
    static int step(Minecraft mc,int stage) throws Exception {
        if(error!=null)throw new AssertionError(error);
        switch(stage) {
            case 60 -> {
                if(!started) {
                    started=true;ClientSession.selectDimension(null);ClientSession.select(null);mc.setScreen(null);
                    var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
                    server.execute(()->server.getPlayerList().getPlayer(id).teleportTo(server.overworld(),0,70,0,0,0));
                }
                if(!mc.level.dimension().equals(Level.OVERWORLD) || !ClientSession.ready())return stage;
                mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);return 61;
            }
            case 61 -> {
                if(!(mc.screen instanceof ExplorersCompassScreen screen))return stage;
                var aliases=Map.ofEntries(Map.entry("binah:endlessdesert","异界沙漠"),Map.entry("callfromthedepth_:depth","幽匿暗域"),
                        Map.entry("distant_worlds:garhenna","迦赫纳"),Map.entry("faded_conquest_2:the_abyss","死寂之海"),Map.entry("faded_conquest_2:the_mirror_realm","众神万神殿"),Map.entry("graveyard:past","往昔"),
                        Map.entry("infinite_abyss:first_layer","阿比斯之渊（第1层）"),Map.entry("infinite_abyss:second_layer","无间之炼狱（第2层）"),Map.entry("infinite_abyss:fourth_layer","诱惑之森（第4层）"),
                        Map.entry("infinite_abyss:fifth_layer","亡骸之海（第5层）"),Map.entry("infinite_abyss:sixth_layer","来无回之都（第6层）"),Map.entry("infinite_abyss:seventh_layer","最终极之涡（第7层）"));
                aliases.forEach((key,name)->check(DimensionNames.name(new ResourceLocation(key)).equals(name),"dimension alias "+key));
                check(!DimensionNames.name(new ResourceLocation("minecraft:")).contains("minecraft:"),"invalid dimension hidden");
                mc.options.guiScale().set(4);mc.resizeDisplay();screen=(ExplorersCompassScreen)mc.screen;
                screen.mouseScrolled(20,100,-30);
                var field=screen.getClass().getDeclaredField("landingModeButton");field.setAccessible(true);var button=(Button)field.get(screen);
                check(button.visible && button.getY()+20<screen.height-30,"small sidebar scroll exposes mode without covering cancel");
                screen.mouseClicked(button.getX()+5,button.getY()+5,0);check(ConfigHandler.CLIENT.landingMode.get()==LandingMode.SAFE_ONLY,"mode click strict");
                screen.mouseClicked(button.getX()+5,button.getY()+5,0);check(ConfigHandler.CLIENT.landingMode.get()==LandingMode.RELAXED_ONLY,"mode click relaxed");
                screen.mouseClicked(button.getX()+5,button.getY()+5,0);check(ConfigHandler.CLIENT.landingMode.get()==LandingMode.DEFAULT,"mode cycle default");
                mc.options.guiScale().set(2);mc.resizeDisplay();screen=(ExplorersCompassScreen)mc.screen;
                check(net.minecraftforge.client.ClientCommandHandler.runCommand("david log open"),"registered local log open command");
                check(ConfigHandler.CLIENT.searchLogEnabled.get(),"log enabled");
                previous=SearchService.token(mc.player.getMainHandItem());screen.searchForStructure(TREASURE);return 62;
            }
            case 62 -> {
                var stack=mc.player.getMainHandItem();
                if(Objects.equals(previous,SearchService.token(stack)) || !TREASURE.equals(ExplorersCompass.explorersCompass.getStructureKey(stack))
                        || !stack.getOrCreateTag().getString("ECFiltersSearchStage").equals("found"))return stage;
                request=SearchService.token(stack);x=stack.getTag().getInt("FoundX");z=stack.getTag().getInt("FoundZ");
                check((x&15)==9 && (z&15)==9,"actual treasure piece XZ reaches client");
                if(!platform) {
                    platform=true;var server=mc.getSingleplayerServer();
                    server.execute(()->{
                        try {
                            var world=server.overworld();for(int cx=(x>>4)-1;cx<=(x>>4)+1;cx++)for(int cz=(z>>4)-1;cz<=(z>>4)+1;cz++)world.getChunk(cx,cz);
                            int top=world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
                            var feet=new BlockPos(x,top+3,z);world.setBlockAndUpdate(feet.below(),Blocks.STONE.defaultBlockState());
                            world.setBlockAndUpdate(feet,Blocks.AIR.defaultBlockState());world.setBlockAndUpdate(feet.above(),Blocks.AIR.defaultBlockState());platformReady=true;
                        }catch(Throwable failure){error=failure;}
                    });
                }
                if(!platformReady)return stage;
                flushed=SearchLogClient.writer().flushAsync();mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);return 63;
            }
            case 63 -> {
                if(!(mc.screen instanceof ExplorersCompassScreen screen) || !flushed.isDone())return stage;
                // 取已经渲染的帧；resizeDisplay 后同 tick 抓取会得到空白渲染目标。
                Screenshot.grab(mc.gameDirectory,"unified-102-modes.png",mc.getMainRenderTarget(),unused->{});
                var record=Files.readAllLines(SearchLogClient.writer().file()).stream().map(line->JsonParser.parseString(line).getAsJsonObject())
                        .filter(row->row.get("requestId").getAsString().equals(request.toString())).findFirst().orElseThrow();
                check(record.get("dimension").getAsString().equals("minecraft:overworld") && record.get("dimensionName").getAsString().equals("主世界"),"log dimension comes from accepted search");
                check(record.get("durationNanos").getAsLong()>=0 && record.get("result").getAsString().equals("found"),"server measured duration and found outcome");
                var field=screen.getClass().getDeclaredField("landingModeButton");field.setAccessible(true);var button=(Button)field.get(screen);
                button.onPress();button.onPress();check(ConfigHandler.CLIENT.landingMode.get()==LandingMode.RELAXED_ONLY,"direct relaxed configured");
                count=ClientSession.count(TREASURE);screen.teleport();return 64;
            }
            case 64 -> {
                if(ClientSession.count(TREASURE)!=count+1)return stage;
                check(Math.abs(mc.player.getX()-(x+.5))<.01 && Math.abs(mc.player.getZ()-(z+.5))<.01,"actual treasure teleport stands exactly above box column");
                check(mc.screen==null,"successful move closes compass UI");
                verifyLifecycleFixtures(mc);
                check(net.minecraftforge.client.ClientCommandHandler.runCommand("david log clsoe"),"legacy spelling closes log");
                check(net.minecraftforge.client.ClientCommandHandler.runCommand("david log close"),"close command registered");
                check(!ConfigHandler.CLIENT.searchLogEnabled.get(),"close persists disabled setting");
                check(net.minecraftforge.client.ClientCommandHandler.runCommand("david log"),"status command registered");
                check(net.minecraftforge.client.ClientCommandHandler.runCommand("david log sort"),"sort command registered");return 65;
            }
            case 65 -> {
                Path sorted=SearchLogClient.writer().file().resolveSibling("search-history.sorted.jsonl");if(!Files.exists(sorted))return stage;
                var records=Files.readAllLines(sorted).stream().map(line->JsonParser.parseString(line).getAsJsonObject()).toList();
                if(records.stream().noneMatch(row->row.get("requestId").getAsString().equals(request.toString())))return stage;
                long duration=Long.MAX_VALUE;for(var row:records)if(row.get("completed").getAsBoolean()){long next=row.get("durationNanos").getAsLong();check(next<=duration,"local command export sorted by descending duration");duration=next;}
                check(records.stream().filter(row->row.get("requestId").getAsString().equals(request.toString())).count()==1,"one accepted search one record");
                var own=records.stream().filter(row->row.get("environment").getAsString().equals(mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().toString())).toList();
                check(own.stream().anyMatch(row->row.has("requestKind") && row.get("requestKind").getAsString().equals("group")),"actual group requests logged");
                check(own.stream().anyMatch(row->row.get("result").getAsString().equals("cancelled")),"actual cancelled requests logged");
                check(own.stream().anyMatch(row->row.get("result").getAsString().equals("sample_limit")),"actual failed requests logged");
                check(own.stream().anyMatch(row->!row.get("completed").getAsBoolean()),"disconnected request recorded incomplete");
                ConfigHandler.CLIENT.landingMode.set(LandingMode.DEFAULT);ConfigHandler.CLIENT.landingMode.save();
                System.out.println("UNIFIED102 client aliases, mode UI, treasure actual teleport, log lifecycle and commands ALL PASS");return 100;
            }
            default -> throw new AssertionError(stage);
        }
    }
    private static void verifyLifecycleFixtures(Minecraft mc) {
        var source=mc.getConnection().getConnection();var data=new net.minecraft.nbt.CompoundTag();
        data.putUUID("player",mc.player.getUUID());data.putString("category","minecraft:plains");data.putString("kind","biome");
        data.putString("dimension","minecraft:overworld");data.putLong("startedAt",System.currentTimeMillis());
        data.putString("requestKind","normal");data.putString("mode","async");data.putIntArray("origin",new int[]{0,70,0});
        var id=UUID.randomUUID();SearchLogClient.accept(source,new com.chaosthedude.explorerscompass.unified.network.SearchLifecyclePacket(id,false,data));
        SearchLogClient.enabled(false);data.putString("result","found");data.putLong("durationNanos",77_000_000);
        var terminal=new com.chaosthedude.explorerscompass.unified.network.SearchLifecyclePacket(id,true,data);
        SearchLogClient.accept(source,terminal);SearchLogClient.accept(source,terminal);
        // 开始后关闭仍补全；关闭状态的新请求不收录；失去终态不编造服务端耗时。
        var disabled=UUID.randomUUID();SearchLogClient.accept(source,new com.chaosthedude.explorerscompass.unified.network.SearchLifecyclePacket(disabled,false,data));
        SearchLogClient.accept(source,new com.chaosthedude.explorerscompass.unified.network.SearchLifecyclePacket(disabled,true,data));
        SearchLogClient.enabled(true);var unfinished=UUID.randomUUID();
        SearchLogClient.accept(source,new com.chaosthedude.explorerscompass.unified.network.SearchLifecyclePacket(unfinished,false,data));SearchLogClient.disconnect();
    }
}
