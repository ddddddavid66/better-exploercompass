package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.gui.ExplorersCompassScreen;
import com.chaosthedude.explorerscompass.unified.client.ClientSession;
import com.chaosthedude.explorerscompass.unified.client.FilterScreenAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import static com.chaosthedude.explorerscompass.unified.smoke.SmokeChecks.check;

/** 在新建的隔离世界中驱动真实指南针界面，截图使用 Minecraft 自身截图接口。 */
@Mod.EventBusSubscriber(modid = "filterschecks", value = Dist.CLIENT)
public final class ClientSmokeChecks {
    private static final ResourceLocation PLAINS = new ResourceLocation("minecraft:plains");
    private static final ResourceLocation VILLAGE = new ResourceLocation("minecraft:village_plains");
    private static int stage;
    private static int tick;
    private static int waitUntil;
    private static long deadline = System.currentTimeMillis() + 420_000;
    private static long initial;
    private static int teleportX, teleportZ;
    private static long initialBiome;
    private static net.minecraft.core.BlockPos biomeTarget;
    private static volatile Boolean biomeTeleportVerified;
    private static boolean biomeVerificationRequested;
    private static volatile Throwable extraFailure;
    private static volatile boolean reloadDone;
    private static long catalogRevision, countFilterRevision;
    private static java.util.UUID dungeonPreviousToken;
    private ClientSmokeChecks() {}

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stage == 100) return;
        tick++;
        Minecraft mc = Minecraft.getInstance();
        if (tick < waitUntil) return;
        try {
            if (extraFailure != null) throw new AssertionError("Server check failed", extraFailure);
            if (System.currentTimeMillis() > deadline) throw new AssertionError("Client smoke timed out at stage " + stage);
            switch (stage) {
                case 0 -> {
                    if (mc.screen instanceof net.minecraftforge.client.gui.LoadingErrorScreen loading) {
                        var field = loading.getClass().getDeclaredField("modLoadErrors");
                        field.setAccessible(true);
                        check(((List<?>) field.get(loading)).isEmpty(), "Forge loading errors: " + field.get(loading));
                        System.out.println("Smoke continuing past Forge warnings");
                        mc.setScreen(new TitleScreen());
                    }
                    if (tick % 100 == 0) System.out.println("Smoke waiting for title: " + (mc.screen == null ? "null" : mc.screen.getClass().getName()));
                    if (mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) {
                        mc.setScreen(new TitleScreen());
                    }
                    if (!(mc.screen instanceof TitleScreen)) return;
                    com.chaosthedude.explorerscompass.config.ConfigHandler.CLIENT.landingMode.set(com.chaosthedude.explorerscompass.unified.data.LandingMode.DEFAULT);
                    com.chaosthedude.explorerscompass.config.ConfigHandler.CLIENT.searchLogEnabled.set(false);
                    mc.options.guiScale().set(2);
                    mc.resizeDisplay();
                    mc.createWorldOpenFlows().createFreshLevel("filters-smoke-client-" + System.currentTimeMillis(),
                            new LevelSettings("Filters isolated check", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                                    true, new GameRules(), WorldDataConfiguration.DEFAULT),
                            new WorldOptions(789123L, true, false), WorldPresets::createNormalWorldDimensions);
                    advance(1, 20);
                }
                case 1 -> {
                    if (mc.player == null || mc.getSingleplayerServer() == null) return;
                    var server = mc.getSingleplayerServer();
                    var id = mc.player.getUUID();
                    server.execute(() -> server.getPlayerList().getPlayer(id).setItemInHand(InteractionHand.MAIN_HAND,
                            new ItemStack(ExplorersCompass.explorersCompass)));
                    advance(2, 20);
                }
                case 2 -> {
                    if (mc.player.getMainHandItem().getItem() != ExplorersCompass.explorersCompass) return;
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    advance(3, 10);
                }
                case 3 -> {
                    if (!(mc.screen instanceof ExplorersCompassScreen) || !ClientSession.ready()) return;
                    check(!com.chaosthedude.explorerscompass.config.ConfigHandler.CLIENT.searchLogEnabled.get(),"log initially disabled");
                    check(net.minecraftforge.client.ClientCommandHandler.runCommand("david log open"),"capture actual regression request lifecycles");
                    if (!(mc.screen instanceof ExplorersCompassScreen screen) || !ClientSession.ready()) return;
                    check(screen.sortStructures().contains(VILLAGE), "default list includes plains village");
                    var endFeature=new ResourceLocation("stalwart_dungeons:end_dungeon");
                    if(net.minecraftforge.fml.ModList.get().isLoaded("stalwart_dungeons")) {
                        check(screen.sortStructures().contains(endFeature),"End Dungeon appears in normal directory");
                        check(com.chaosthedude.explorerscompass.util.StructureUtils.getPrettyStructureName(endFeature).equals("末地地牢"),"feature Chinese translation");
                        ClientSession.selectDimension(new ResourceLocation("minecraft:the_end"));
                        check(screen.sortStructures().contains(endFeature),"End Dungeon survives dimension filter");
                        ClientSession.selectDimension(new ResourceLocation("minecraft:overworld"));
                        check(!screen.sortStructures().contains(endFeature),"End Dungeon excluded from overworld");
                        ClientSession.selectDimension(null);
                    } else check(!screen.sortStructures().contains(endFeature),"optional feature absent without Stalwart");
                    click(screen, "biomeFilter");
                    check(((FilterScreenAccess) screen).popupOpen(), "biome button opens popup");
                    screen.charTyped('p', 0);
                    screen.charTyped('y', 0);
                    advance(4, 5);
                }
                case 4 -> {
                    screenshot(mc, "filters-biome-popup.png");
                    ExplorersCompassScreen screen = (ExplorersCompassScreen) mc.screen;
                    screen.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
                    screen.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
                    for (char c : "minecraft:plains".toCharArray()) screen.charTyped(c, 0);
                    screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
                    check(PLAINS.equals(ClientSession.selected()), "keyboard picks exact biome ID");
                    check(screen.sortStructures().contains(VILLAGE), "filtered list includes plains village");
                    check(!screen.sortStructures().contains(new ResourceLocation("minecraft:desert_pyramid")), "filtered list excludes desert pyramid");
                    for (int index = 0; index < 5; index++) {
                        var category = screen.getClass().getDeclaredField("sortingCategory");
                        category.setAccessible(true);
                        Object before = category.get(screen);
                        click(screen, "sortByButton");
                        check(before != category.get(screen), "sort button actually changes category");
                        List<ResourceLocation> filtered = screen.sortStructures();
                        ClientSession.select(null);
                        screen.processSearchTerm();
                        List<ResourceLocation> defaults = screen.sortStructures();
                        ClientSession.select(PLAINS);
                        screen.processSearchTerm();
                        check(filtered.equals(ClientSession.filter(defaults)), "sorting composes with filter " + index);
                    }
                    initial = ClientSession.count(VILLAGE);
                    screen.searchForStructure(VILLAGE);
                    advance(5, 10);
                }
                case 5 -> {
                    if (ExplorersCompass.explorersCompass.getState(mc.player.getMainHandItem()) != com.chaosthedude.explorerscompass.util.CompassState.FOUND) return;
                    check(ClientSession.count(VILLAGE) == initial, "search request never increments counter");
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    advance(6, 10);
                }
                case 6 -> {
                    if (!(mc.screen instanceof ExplorersCompassScreen screen) || !ClientSession.ready()) return;
                    screen.searchForGroup(ExplorersCompass.structureKeysToTypeKeys.get(VILLAGE));
                    advance(7, 10);
                }
                case 7 -> {
                    if (ExplorersCompass.explorersCompass.getState(mc.player.getMainHandItem()) != com.chaosthedude.explorerscompass.util.CompassState.FOUND) return;
                    check(ClientSession.count(VILLAGE) == initial, "search request never increments counter");
                    check(ClientSession.count(new ResourceLocation("minecraft:village_desert")) == 0,
                            "filtered group did not search desert village");
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    advance(35, 10);
                }
                case 35 -> {
                    if(!(mc.screen instanceof ExplorersCompassScreen screen))return;
                    screen.teleport(); advance(36,5);
                }
                case 36 -> {
                    if(ClientSession.count(VILLAGE)!=initial+1)return;
                    check(ClientSession.count(new ResourceLocation("minecraft:village_desert"))==0,"group teleport counts only hit structure");
                    mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);advance(8,10);
                }
                case 8 -> {
                    if (!(mc.screen instanceof ExplorersCompassScreen)) return;
                    screenshot(mc, "filters-counts-scale2.png");
                    mc.options.guiScale().set(1);
                    mc.resizeDisplay();
                    advance(9, 10);
                }
                case 9 -> {
                    screenshot(mc, "filters-counts-scale1.png");
                    ExplorersCompassScreen screen = (ExplorersCompassScreen) mc.screen;
                    click(screen, "biomeFilter");
                    check(((FilterScreenAccess) screen).popupOpen(), "popup after resize");
                    screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                    check(mc.screen == screen && !((FilterScreenAccess) screen).popupOpen(), "escape closes only popup");
                    var listField = screen.getClass().getDeclaredField("selectionList");
                    listField.setAccessible(true);
                    var list = (com.chaosthedude.explorerscompass.gui.StructureSearchList) listField.get(screen);
                    var entry = list.children().stream().filter(item -> {
                        try {
                            var keyField = item.getClass().getDeclaredField("structureKey");
                            keyField.setAccessible(true);
                            return VILLAGE.equals(keyField.get(item));
                        } catch (Exception exception) { throw new IllegalStateException(exception); }
                    }).findFirst().orElseThrow();
                    entry.mouseClicked(list.getRowLeft() + 5, 45, 0);
                    entry.mouseClicked(list.getRowLeft() + 5, 45, 0);
                    advance(10, 10);
                }
                case 10 -> {
                    if (ExplorersCompass.explorersCompass.getState(mc.player.getMainHandItem()) != com.chaosthedude.explorerscompass.util.CompassState.FOUND) return;
                    check(ClientSession.count(VILLAGE) == initial+1, "search request never increments counter");
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    advance(11, 10);
                }
                case 11 -> {
                    if (!(mc.screen instanceof ExplorersCompassScreen screen) || !ClientSession.ready()) return;
                    ClientSession.select(PLAINS); screen.processSearchTerm();
                    initialBiome = ClientSession.biomeCount(PLAINS);
                    var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
                    mc.setScreen(null);
                    server.execute(()->{
                        try {
                        var player=server.getPlayerList().getPlayer(id);
                        var boat=net.minecraft.world.entity.EntityType.BOAT.create(player.serverLevel());
                        boat.setPos(player.getX(),player.getY(),player.getZ());player.serverLevel().addFreshEntity(boat);
                        check(player.startRiding(boat,true),"mounted teleport fixture enters boat");
                        } catch(Throwable error) { extraFailure=error; }
                    });advance(39,10);
                }
                case 39 -> {
                    if(!mc.player.isPassenger())return;
                    mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);
                    advance(40,10);
                }
                case 40 -> {
                    if(!(mc.screen instanceof ExplorersCompassScreen))return;
                    advance(12,1);
                }
                case 12 -> {
                    click((ExplorersCompassScreen)mc.screen, "locateBiomeButton");
                    check(mc.screen == null, "real locate biome button sends request and closes GUI");
                    advance(13, 10);
                }
                case 13 -> {
                    var stack = mc.player.getMainHandItem();
                    if (!com.chaosthedude.explorerscompass.unified.search.SearchService.isBiome(stack)
                            || ExplorersCompass.explorersCompass.getState(stack) != com.chaosthedude.explorerscompass.util.CompassState.FOUND) return;
                    check(stack.getTag().contains(com.chaosthedude.explorerscompass.unified.search.SearchService.FOUND_Y), "biome Y is synced to client");
                    check(ClientSession.count(VILLAGE) == initial+1, "biome request does not count as structure request");
                    check(ClientSession.biomeCount(PLAINS) == initialBiome, "biome search never increments counter");
                    screenshot(mc, "filters-biome-found.png");
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    advance(14, 10);
                }
                case 14 -> {
                    if (!(mc.screen instanceof ExplorersCompassScreen screen)) return;
                    var teleport = screen.getClass().getDeclaredField("teleportButton"); teleport.setAccessible(true);
                    check(((net.minecraft.client.gui.components.Button)teleport.get(screen)).active, "biome teleport is enabled with permissions");
                    screenshot(mc, "filters-locate-button.png");
                    var stack = mc.player.getMainHandItem();
                    biomeTarget = new net.minecraft.core.BlockPos(ExplorersCompass.explorersCompass.getFoundStructureX(stack),
                            stack.getTag().getInt(com.chaosthedude.explorerscompass.unified.search.SearchService.FOUND_Y), ExplorersCompass.explorersCompass.getFoundStructureZ(stack));
                    ((net.minecraft.client.gui.components.Button)teleport.get(screen)).onPress();
                    advance(21, 1);
                }
                case 15 -> {
                    mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(mc.player,
                            net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
                    mc.player.setShiftKeyDown(true);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(mc.player,
                            net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                    mc.player.setShiftKeyDown(false);
                    advance(16, 20);
                }
                case 16 -> {
                    check(ExplorersCompass.explorersCompass.getState(mc.player.getMainHandItem())
                            == com.chaosthedude.explorerscompass.util.CompassState.INACTIVE, "shift-right-click stops real search");
                    check(!com.chaosthedude.explorerscompass.unified.search.SearchService.isBiome(mc.player.getMainHandItem()), "structure request clears biome marker");
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    advance(17, 10);
                }
                case 17 -> {
                    if (!(mc.screen instanceof ExplorersCompassScreen screen)) return;
                    screen.searchForStructure(VILLAGE);
                    advance(18, 10);
                }
                case 18 -> {
                    var stack = mc.player.getMainHandItem();
                    if (ExplorersCompass.explorersCompass.getState(stack) != com.chaosthedude.explorerscompass.util.CompassState.FOUND) return;
                    teleportX = ExplorersCompass.explorersCompass.getFoundStructureX(stack);
                    teleportZ = ExplorersCompass.explorersCompass.getFoundStructureZ(stack);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    advance(19, 10);
                }
                case 19 -> {
                    if (!(mc.screen instanceof ExplorersCompassScreen screen)) return;
                    var field = screen.getClass().getDeclaredField("teleportButton"); field.setAccessible(true);
                    var button = (net.minecraft.client.gui.components.Button)field.get(screen);
                    check(button.active, "original structure teleport remains enabled");
                    button.onPress();
                    advance(20, 40);
                }
                case 20 -> {
                    check(Math.abs(mc.player.getX()-teleportX)<9&&Math.abs(mc.player.getZ()-teleportZ)<9,"actual structure teleport coordinates");
                    check(ClientSession.count(VILLAGE)==initial+2,"successful ordinary structure teleport counts once");
                    var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
                    server.execute(()->server.getPlayerList().getPlayer(id).getMainHandItem().getTag().remove("ECFiltersSearchToken"));
                    mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);advance(37,10);
                }
                case 37 -> {
                    if(!(mc.screen instanceof ExplorersCompassScreen screen))return;
                    screen.teleport();advance(38,10);
                }
                case 38 -> {
                    if(ClientSession.count(VILLAGE)!=initial+3)return;
                    var server = mc.getSingleplayerServer(); var id = mc.player.getUUID();
                    server.execute(() -> {
                        try {
                            var player = server.getPlayerList().getPlayer(id);
                            check(com.chaosthedude.explorerscompass.unified.search.SearchService.startBiome(player,player.getMainHandItem(),PLAINS),"accept cancellation fixture");
                            com.chaosthedude.explorerscompass.unified.search.SearchService.cancel(player,true);
                        } catch (Throwable error) { extraFailure = error; }
                    });
                    advance(26,10);
                }
                case 21 -> {
                    if(ClientSession.biomeCount(PLAINS)!=initialBiome+1)return;
                    if (Math.abs(mc.player.getX() - biomeTarget.getX()) > 9 || Math.abs(mc.player.getZ() - biomeTarget.getZ()) > 9
                            || Math.abs(mc.player.getY() - biomeTarget.getY()) > 16) return;
                    if (!biomeVerificationRequested) {
                        var server = mc.getSingleplayerServer(); var id = mc.player.getUUID();
                        biomeVerificationRequested = true;
                        server.execute(() -> {
                            var player = server.getPlayerList().getPlayer(id);
                            biomeTeleportVerified = com.chaosthedude.explorerscompass.unified.search.TeleportService.safeAt(player, player.serverLevel(), player.getMainHandItem(), player.position());
                            System.out.println("CLIENT actual landing="+player.position()+" strict="+biomeTeleportVerified);
                        });
                        return;
                    }
                    if (biomeTeleportVerified == null) return;
                    check(biomeTeleportVerified, "actual biome teleport landing is safe and in selected biome");
                    check(!mc.player.isPassenger(),"biome teleport dismounts without landing inside own boat");
                    check(ClientSession.biomeCount(PLAINS) == initialBiome + 1, "successful biome teleport increments once");
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); advance(32, 10);
                }
                case 32 -> {
                    if (!(mc.screen instanceof ExplorersCompassScreen screen)) return;
                    click(screen, "biomeFilter"); countFilterRevision=ClientSession.revision();
                    screen.charTyped('p',0);screen.charTyped('y',0);
                    var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
                    server.execute(()->{
                        var packet=com.chaosthedude.explorerscompass.unified.search.TeleportEvents.latest(id);
                        com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork.send(server.getPlayerList().getPlayer(id),packet);
                        com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork.send(server.getPlayerList().getPlayer(id),packet);
                    }); advance(33,10);
                }
                case 33 -> {
                    var screen=(ExplorersCompassScreen)mc.screen;
                    check(ClientSession.biomeCount(PLAINS)==initialBiome+1,"duplicate receipt does not count");
                    check(ClientSession.revision()==countFilterRevision && ((FilterScreenAccess)screen).popupOpen(),"receipt leaves filtering and popup intact");
                    screenshot(mc,"filters-biome-teleport-count.png");
                    screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0); screen.teleport(); advance(34,2);
                }
                case 34 -> {
                    if(ClientSession.biomeCount(PLAINS)!=initialBiome+2)return;
                    mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND); advance(22,10);
                }
                case 22 -> {
                    if (!(mc.screen instanceof ExplorersCompassScreen screen) || !ClientSession.ready()) return;
                    check(com.chaosthedude.explorerscompass.unified.client.DimensionNames.sorted(ClientSession.dimensions()).get(0).equals(ClientSession.currentDimension()), "current dimension first");
                    check(com.chaosthedude.explorerscompass.unified.client.DimensionNames.name(ClientSession.currentDimension()).equals("主世界"), "Chinese dimension name");
                    click(screen, "dimensionFilter"); screenshot(mc,"filters-dimension-popup.png");
                    screen.charTyped('x',0); screen.charTyped('j',0); advance(23,5);
                }
                case 23 -> {
                    var screen = (ExplorersCompassScreen)mc.screen;
                    screenshot(mc,"filters-dimension-pinyin.png"); screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);
                    check(new ResourceLocation("minecraft:the_nether").equals(ClientSession.selectedDimension()), "pinyin selects nether");
                    check(ClientSession.selected() == null, "invalid biome selection reset on dimension change");
                    check(ClientSession.biomes().contains(new ResourceLocation("minecraft:crimson_forest")) && !ClientSession.biomes().contains(PLAINS), "biome list updates with dimension");
                    advance(24,5);
                }
                case 24 -> {
                    var screen = (ExplorersCompassScreen)mc.screen;
                    check(!ClientSession.canSearch(), "other dimension cannot search");
                    check(screen.sortStructures().contains(new ResourceLocation("minecraft:fortress")) && !screen.sortStructures().contains(VILLAGE), "dimension filters structure placement");
                    screen.searchForStructure(VILLAGE); check(mc.screen == screen, "other dimension structure request prevented");
                    screenshot(mc,"filters-nether-filter.png"); click(screen, "dimensionFilter");
                    for (char c : "zsj".toCharArray()) screen.charTyped(c,0);
                    screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0); advance(25,5);
                }
                case 25 -> {
                    check(ClientSession.canSearch(), "current dimension enables searching");
                    ((ExplorersCompassScreen)mc.screen).searchForStructure(new ResourceLocation("minecraft:mansion")); advance(15,2);
                }
                case 26 -> {
                    if (ClientSession.biomeCount(PLAINS) != initialBiome + 2) return;
                    check(ExplorersCompass.explorersCompass.getState(mc.player.getMainHandItem()) == com.chaosthedude.explorerscompass.util.CompassState.INACTIVE,"cancelled biome does not count");
                    var server=mc.getSingleplayerServer(); var id=mc.player.getUUID();
                    server.execute(() -> {
                        
                        try {
                            
                            var player=server.getPlayerList().getPlayer(id);
                            check(com.chaosthedude.explorerscompass.unified.search.SearchService.startBiome(player,player.getMainHandItem(),PLAINS,0),"valid zero sample fixture");
                        } catch(Throwable error) { extraFailure=error; }
                        
                    });
                    advance(27,10);
                }
                case 27 -> {
                    if (ClientSession.biomeCount(PLAINS) != initialBiome+2 || ExplorersCompass.explorersCompass.getState(mc.player.getMainHandItem())
                            != com.chaosthedude.explorerscompass.util.CompassState.NOT_FOUND) return;
                    var server=mc.getSingleplayerServer(); var id=mc.player.getUUID();
                    server.execute(() -> {
                        try {
                            var player=server.getPlayerList().getPlayer(id);
                            check(!com.chaosthedude.explorerscompass.unified.search.SearchService.startBiome(player,player.getMainHandItem(),new ResourceLocation("minecraft:the_end")),"reject impossible biome");
                        } catch(Throwable error) { extraFailure=error; }
                    });
                    com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork.CHANNEL.sendToServer(new com.chaosthedude.explorerscompass.unified.network.BiomeSearchPacket(PLAINS,new ResourceLocation("minecraft:the_nether")));
                    advance(28,20);
                }
                case 28 -> {
                    check(ClientSession.biomeCount(PLAINS)==initialBiome+2 && ClientSession.biomeCount(new ResourceLocation("minecraft:the_end"))==0,"rejected requests do not count");
                    var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
                    server.execute(()->{
                        try {
                            var player=server.getPlayerList().getPlayer(id);var held=player.getMainHandItem();
                            check(com.chaosthedude.explorerscompass.unified.search.SearchService.startBiome(player,held,PLAINS),"fallback fixture valid biome");
                            com.chaosthedude.explorerscompass.unified.search.SearchService.cancel(player,false);
                            ExplorersCompass.explorersCompass.setFound(held,PLAINS,biomeTarget.getX(),biomeTarget.getZ(),1);
                            held.getTag().putInt(com.chaosthedude.explorerscompass.unified.search.SearchService.FOUND_Y,250);
                            com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork.send(player,new com.chaosthedude.explorerscompass.unified.network.SearchStatusPacket(
                                    held.getTag().getUUID("ECFiltersSearchToken"),held.getTag().copy()));
                        } catch(Throwable error){extraFailure=error;}
                    });advance(41,10);
                }
                case 41 -> {
                    if(mc.player.getMainHandItem().getTag().getInt(com.chaosthedude.explorerscompass.unified.search.SearchService.FOUND_Y)!=250)return;
                    mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);advance(42,10);
                }
                case 42 -> {
                    if(!(mc.screen instanceof ExplorersCompassScreen screen))return;
                    screen.teleport();check(mc.screen==screen,"first teleport retains GUI");advance(43,1);
                }
                case 43 -> {
                    if(!com.chaosthedude.explorerscompass.unified.client.LandingClientState.fallback(mc.player.getMainHandItem()))return;
                    check(ClientSession.biomeCount(PLAINS)==initialBiome+2,"strict failure never counts");
                    screenshot(mc,"filters-fallback-confirmation.png");
                    mc.setScreen(null);mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);advance(44,10);
                }
                case 44 -> {
                    if(!(mc.screen instanceof ExplorersCompassScreen screen))return;
                    check(com.chaosthedude.explorerscompass.unified.client.LandingClientState.fallback(mc.player.getMainHandItem()),"reopen preserves confirmation");
                    screen.teleport();advance(45,1);
                }
                case 45 -> {
                    if(ClientSession.biomeCount(PLAINS)!=initialBiome+3)return;
                    check(mc.player.getY()<180,"second click reaches nearby surface from high sample");
                    check(mc.screen==null,"GUI closes only after successful fallback");
                    screenshot(mc,"filters-fallback-success.png");advance(46,5);
                }
                case 46 -> {
                    ClientSession.selectDimension(new ResourceLocation("minecraft:the_nether"));
                    var server=mc.getSingleplayerServer();var id=mc.player.getUUID();
                    server.execute(()->server.getPlayerList().getPlayer(id).teleportTo(server.getLevel(net.minecraft.world.level.Level.NETHER),0,70,0,0,0));
                    advance(29,20);
                }
                case 29 -> {
                    if (tick % 100 == 0) System.out.println("Dimension check: " + mc.level.dimension() + " ready=" + ClientSession.ready());
                    if (!mc.level.dimension().equals(net.minecraft.world.level.Level.NETHER) || !ClientSession.ready()) return;
                    check(ClientSession.canSearch(),"enter selected dimension restores searching");
                    check(ClientSession.biomes().contains(new ResourceLocation("minecraft:crimson_forest")),"catalog remains valid after actual dimension change");
                    check(com.chaosthedude.explorerscompass.unified.client.DimensionNames.sorted(ClientSession.dimensions()).get(0).equals(mc.level.dimension().location()),"nether now first");
                    var property=net.minecraft.client.renderer.item.ItemProperties.getProperty(ExplorersCompass.explorersCompass,new ResourceLocation("angle"));
                    check(property.call(mc.player.getMainHandItem(),mc.level,mc.player,0)==0F,"wrong dimension needle is suspended");
                    screenshot(mc,"filters-return-dimension.png");
                    mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND); advance(30,10);
                }
                case 30 -> {
                    if (!(mc.screen instanceof ExplorersCompassScreen screen)) return;
                    var field=screen.getClass().getDeclaredField("teleportButton");field.setAccessible(true);
                    check(!((net.minecraft.client.gui.components.Button)field.get(screen)).active,"wrong dimension biome teleport disabled");
                    screenshot(mc,"filters-nether-current.png");
                    catalogRevision=ClientSession.revision();
                    mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().reloadResources(mc.getSingleplayerServer().getPackRepository().getSelectedIds())
                            .whenComplete((unused,error)->{ extraFailure=error;reloadDone=true; }));
                    advance(31,20);
                }
                case 31 -> {
                    if (!reloadDone || !ClientSession.ready() || ClientSession.revision()<=catalogRevision) return;
                    check(ClientSession.canSearch(),"directory resynchronized after real datapack reload");
                    check(ClientSession.biomeCount(PLAINS)==initialBiome+3,"dimension change and reload preserve counts");
                    var env=ClientSession.class.getDeclaredField("environment");env.setAccessible(true);
                    var persisted=new com.chaosthedude.explorerscompass.unified.data.StateStore(Path.of("config/explorerscompass/client-state.json"),System.out::println);
                    check(persisted.count((String)env.get(null),VILLAGE.toString())==initial+3&&persisted.biomeCount((String)env.get(null),PLAINS.toString())==initialBiome+3,"fresh state reader preserves successful teleport counts");
                    if (net.minecraftforge.fml.ModList.get().isLoaded("dungeons_plus")) {
                        ClientSession.selectDimension(null); ClientSession.select(null);
                        mc.setScreen(null);
                        var server=mc.getSingleplayerServer(); var playerId=mc.player.getUUID();
                        server.execute(() -> server.getPlayerList().getPlayer(playerId).teleportTo(server.overworld(),0,70,0,0,0));
                        advance(47,20); break;
                    }
                    advance(60,5);
                }
                case 47 -> {
                    if(!mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD) || !ClientSession.ready())return;
                    mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND); advance(48,10);
                }
                case 48 -> {
                    if(!(mc.screen instanceof ExplorersCompassScreen screen))return;
                    var key=new ResourceLocation("dungeons_plus:leviathan");
                    check(screen.sortStructures().contains(key),"Dungeons Leviathan appears in real GUI");
                    dungeonPreviousToken=com.chaosthedude.explorerscompass.unified.search.SearchService.token(mc.player.getMainHandItem());
                    screen.searchForStructure(key);advance(49,5);
                }
                case 49 -> {
                    var stack=mc.player.getMainHandItem();
                    if(java.util.Objects.equals(dungeonPreviousToken,com.chaosthedude.explorerscompass.unified.search.SearchService.token(stack))
                            || !new ResourceLocation("dungeons_plus:leviathan").equals(ExplorersCompass.explorersCompass.getStructureKey(stack))
                            || !stack.getTag().getString("ECFiltersSearchStage").equals("found")
                            || !stack.getTag().getString("ECFiltersTargetDimension").equals("minecraft:overworld"))return;
                    if(ExplorersCompass.explorersCompass.getState(stack)!=com.chaosthedude.explorerscompass.util.CompassState.FOUND)return;
                    check(stack.getTag().getString("ECFiltersSearchMode").equals("async"),"Leviathan real network HUD uses async mode");
                    check(stack.getTag().getLong("ECFiltersSamples")>0,"Leviathan actual region samples synchronized");
                    check(ClientSession.count(new ResourceLocation("dungeons_plus:leviathan"))==0,"Leviathan search does not increment successful-teleport count");
                    screenshot(mc,"unified-dungeons-leviathan.png");
                    mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);advance(50,10);
                }
                case 50 -> {
                    if(!(mc.screen instanceof ExplorersCompassScreen screen))return;
                    dungeonPreviousToken=com.chaosthedude.explorerscompass.unified.search.SearchService.token(mc.player.getMainHandItem());
                    screen.searchForStructure(new ResourceLocation("dungeons_plus:snowy_temple"));advance(51,5);
                }
                case 51 -> {
                    var stack=mc.player.getMainHandItem();
                    if(java.util.Objects.equals(dungeonPreviousToken,com.chaosthedude.explorerscompass.unified.search.SearchService.token(stack))
                            || !new ResourceLocation("dungeons_plus:snowy_temple").equals(ExplorersCompass.explorersCompass.getStructureKey(stack))
                            || !stack.getTag().getString("ECFiltersSearchStage").equals("found")
                            || !stack.getTag().getString("ECFiltersTargetDimension").equals("minecraft:overworld"))return;
                    if(ExplorersCompass.explorersCompass.getState(stack)!=com.chaosthedude.explorerscompass.util.CompassState.FOUND)return;
                    check(stack.getTag().getString("ECFiltersSearchMode").equals("async"),"Snowy Temple actual HUD uses async mode");
                    check(stack.getTag().getLong("ECFiltersSamples")>0,"Snowy Temple actual region samples synchronized");
                    advance(52,5);
                }
                case 52 -> {
                    screenshot(mc,"unified-dungeons-snowy-temple.png");
                    advance(60,5);
                }
                case 60,61,62,63,64,65 -> { int next=Client102Checks.step(mc,stage);if(next==100)finish(mc,"PASS: Unified 1.0.2 existing GUI/search/teleport/count/reload regressions plus three landing modes, twelve dimension aliases, real local log commands/lifecycle/sort, exact buried treasure actual teleport and small-window sidebar.\n");else if(next!=stage)advance(next,10); }
                default -> throw new AssertionError("Unknown client stage");
            }
        } catch (Throwable exception) {
            screenshot(mc, "filters-smoke-failure.png");
            exception.printStackTrace();
            finish(mc, "FAIL stage " + stage + ": " + exception + "\n");
        }
    }

    private static void click(ExplorersCompassScreen screen, String fieldName) throws Exception {
        var field=screen.getClass().getDeclaredField(fieldName);field.setAccessible(true);
        Object value=field.get(screen);
        var button=value instanceof com.chaosthedude.explorerscompass.unified.client.FilterUi ui ? ui.button() : (net.minecraft.client.gui.components.Button)value;
        screen.mouseClicked(button.getX()+5,button.getY()+5,0);
    }
    private static void advance(int next, int delay) {
        System.out.println("CLIENT check stage " + stage + " -> " + next);
        stage = next; waitUntil = tick + delay;
    }
    private static void screenshot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), ignored -> {});
    }
    private static void finish(Minecraft mc, String result) {
        stage = 100;
        try { Files.writeString(Path.of("smoke-client-results.txt"), result); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
        mc.stop();
    }
}
