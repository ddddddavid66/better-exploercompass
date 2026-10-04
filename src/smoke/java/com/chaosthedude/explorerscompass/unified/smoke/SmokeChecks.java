package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.unified.network.BiomeCatalogPacket;
import com.chaosthedude.explorerscompass.unified.network.CatalogService;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 仅 -Psmoke 开发运行加载，不进入交付 jar。 */
@Mod("filterschecks")
public final class SmokeChecks {
    public SmokeChecks() { MinecraftForge.EVENT_BUS.register(this); }

    @SubscribeEvent
    public void serverStarted(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()) return;
        if (Boolean.getBoolean("unified.dungeons103") || Boolean.getBoolean("unified.end105")) return;
        String result;
        try {
            check(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new ResourceLocation("explorerscompass:explorerscompass")) == com.chaosthedude.explorerscompass.ExplorersCompass.explorersCompass, "original item ID registered once");
            check(com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork.VERSION.equals("unified-2"), "unified protocol");
            for (String version : List.of("1", "5", "6", "ABSENT", "ACCEPTVANILLA", "unified-1"))
                check(!com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork.acceptsVersion(version), "reject old/missing protocol: " + version);
            check(com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork.acceptsVersion("unified-2"), "matching protocol accepted");
            BiomeCatalogPacket catalog = CatalogService.snapshot(event.getServer().getLevel(Level.OVERWORLD), UUID.randomUUID());
            ResourceLocation plains = new ResourceLocation("minecraft:plains");
            check(catalog.structures().get(new ResourceLocation("minecraft:village_plains")).contains(plains), "plains village generation");
            check(!catalog.structures().get(new ResourceLocation("minecraft:desert_pyramid")).contains(plains), "desert temple excluded");
            FriendlyByteBuf catalogBuffer = new FriendlyByteBuf(Unpooled.buffer());
            BiomeCatalogPacket.encode(catalog, catalogBuffer);
            check(catalog.equals(BiomeCatalogPacket.decode(catalogBuffer)), "catalog network round trip");
            catalogBuffer.release();
            ServerFeatureChecks.verify(event.getServer());
            OriginalCompatibilityChecks.verify(event.getServer());
            TwilightChecks.verify(event.getServer());
            DungeonsChecks.verify(event.getServer());
            NewFeatureChecks.verify(event.getServer());
            result = "PASS: dedicated server boot; actual dimension/biome/structure relationships; unified-2 codec and old protocol rejection; direct item ordinary/next APIs, legacy NBT recovery, XP/durability, clear cache and group filter; surface/cave/nether landing; fluid/collision/fire/magma/biome rejection; border/ceiling; permissions; legacy/wrong dimension/stale token rejection; 40/20 tick deadlines; confirmation; water/slab/short drop; "
                    + (net.minecraftforge.fml.ModList.get().isLoaded("twilightforest")
                       ? "actual Twilight labyrinth/hydra/knight/dark tower; " : "Twilight absent: optional checks skipped; ")
                    + "new search cancels teleport; no safe landing leaves player unchanged.\n";
        } catch (Throwable exception) {
            result = "FAIL: " + exception + "\n";
            exception.printStackTrace();
        }
        try { Files.writeString(Path.of("smoke-server-results.txt"), result); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
        event.getServer().halt(false);
    }

    /** 启动事件中的测试阻止正常 tick；只推进 MC 调度，不能阻塞等待或重入整轮服务器 tick。 */
    public static boolean pump(net.minecraft.server.MinecraftServer server) {
        check(server.isSameThread(), "test scheduling pump runs on server thread");
        boolean progressed = false;
        for (var level : server.getAllLevels()) {
            var chunks = level.getChunkSource();
            // 此公开入口同时更新 ticket 距离管理与 holder，然后处理区块/光照主线程回调。
            for (int count = 0; count < 64 && chunks.pollTask(); count++) progressed = true;
        }
        for (int count = 0; count < 16 && server.pollTask(); count++) progressed = true;
        return progressed;
    }

    public static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
