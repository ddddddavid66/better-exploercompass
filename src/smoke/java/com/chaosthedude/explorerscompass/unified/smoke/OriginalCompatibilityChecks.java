package com.chaosthedude.explorerscompass.unified.smoke;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.mojang.authlib.GameProfile;
import com.chaosthedude.explorerscompass.unified.network.StructureSearchPacket;
import com.chaosthedude.explorerscompass.unified.search.SearchService;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.util.FakePlayerFactory;
import static com.chaosthedude.explorerscompass.unified.smoke.SmokeChecks.check;

/** 在正式统一 jar 上验证保留的原物品入口和旧 NBT，避免只测内部搜索服务。 */
public final class OriginalCompatibilityChecks {
    private OriginalCompatibilityChecks() {}
    public static void verify(MinecraftServer server) throws Exception {
        var packet = new StructureSearchPacket(new ResourceLocation("minecraft:village_plains"), true, true,
                new ResourceLocation("minecraft:plains"), Level.OVERWORLD.location());
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try { StructureSearchPacket.encode(packet, buffer); check(packet.equals(StructureSearchPacket.decode(buffer)), "protocol 6 structure request roundtrip"); }
        finally { buffer.release(); }
        var cost = config("defaultXpLevels"); var durability = config("compassDurability"); var limit = config("maxNextSearches");
        int oldCost = cost.get(), oldDurability = durability.get(), oldLimit = limit.get();
        var player = FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.fromString("a930f13e-1111-1111-1111-111111111132"), "OriginalCheck"));
        var stack = new ItemStack(ExplorersCompass.explorersCompass);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack); player.setGameMode(GameType.SURVIVAL);
        ExplorersCompass.explorersCompass.setSearching(stack, new ResourceLocation("minecraft:village_plains"), player);
        ExplorersCompass.explorersCompass.inventoryTick(stack, server.overworld(), player, 0, true);
        check(ExplorersCompass.explorersCompass.getState(stack) == CompassState.INACTIVE, "orphan legacy searching target resets");
        var key = new ResourceLocation("minecraft:village_plains");
        var start = ExplorersCompass.explorersCompass.getClass().getMethod("searchForStructure", ServerLevel.class, Player.class,
                BlockPos.class, ResourceLocation.class, boolean.class, ItemStack.class);
        var next = ExplorersCompass.explorersCompass.getClass().getMethod("searchForNextStructure", ServerLevel.class, Player.class, BlockPos.class, ItemStack.class);
        try {
            cost.set(2); durability.set(2); limit.set(1);
            player.experienceLevel = 0;
            start.invoke(ExplorersCompass.explorersCompass, server.overworld(), player, BlockPos.ZERO, key, false, stack);
            check(SearchService.token(stack) == null && SearchService.activeCount() == 0, "native search with insufficient XP rejected");
            player.experienceLevel = 10;
            start.invoke(ExplorersCompass.explorersCompass, server.overworld(), player, BlockPos.ZERO, key, false, stack);
            check(player.experienceLevel == 8 && SearchService.activeCount() == 1, "native ordinary search uses async service and charges once");
            SearchService.cancel(player, true);
            check(player.experienceLevel == 8 && stack.getOrCreateTag().getInt("CompassDamage") == 0, "cancel is charged once without durability damage");
            start.invoke(ExplorersCompass.explorersCompass, server.overworld(), player, BlockPos.ZERO, key, false, stack);
            await(stack, server);
            check(player.experienceLevel == 6 && stack.getTag().getInt("CompassDamage") == 1, "success damages once without extra XP charge");
            int x = ExplorersCompass.explorersCompass.getFoundStructureX(stack), z = ExplorersCompass.explorersCompass.getFoundStructureZ(stack);
            check(stack.getTag().getList("PrevPos", 10).size() == 1, "native next-result history recorded");
            next.invoke(ExplorersCompass.explorersCompass, server.overworld(), player, BlockPos.ZERO, stack);
            await(stack, server);
            check(player.experienceLevel == 4 && stack.getTag().getInt("CompassDamage") == 2, "native next uses same engine and charges/damages once");
            check(x != ExplorersCompass.explorersCompass.getFoundStructureX(stack) || z != ExplorersCompass.explorersCompass.getFoundStructureZ(stack), "next excludes previous actual structure");
            UUID token = SearchService.token(stack);
            next.invoke(ExplorersCompass.explorersCompass, server.overworld(), player, BlockPos.ZERO, stack);
            check(token.equals(SearchService.token(stack)) && player.experienceLevel == 4 && SearchService.activeCount() == 0, "next limit rejects without changing existing target or charging");
            start.invoke(ExplorersCompass.explorersCompass, server.overworld(), player, BlockPos.ZERO, key, false, stack);
            check(token.equals(SearchService.token(stack)) && SearchService.activeCount() == 0, "broken compass cannot bypass native checks");
            check(!stack.getTag().contains("CustomModelData"), "original adapter never adds Edited model override");
            durability.set(0); cost.set(0);
            SearchService.clearCache(player.getUUID());
            start.invoke(ExplorersCompass.explorersCompass, server.overworld(), player, BlockPos.ZERO, key, false, stack);
            await(stack, server);
            check(x == ExplorersCompass.explorersCompass.getFoundStructureX(stack) && z == ExplorersCompass.explorersCompass.getFoundStructureZ(stack), "clear cache restores nearest structure");
            var group = com.chaosthedude.explorerscompass.util.StructureUtils.getStructureKeysToTypeKeys(server.overworld()).get(key);
            var plains = new ResourceLocation("minecraft:plains");
            check(SearchService.requestStructures(player, stack, group, true, false, plains), "server accepts filtered group request");
            await(stack, server);
            var found = com.chaosthedude.explorerscompass.util.StructureUtils.getStructureForKey(server.overworld(), ExplorersCompass.explorersCompass.getStructureKey(stack));
            check(found.biomes().stream().anyMatch(holder -> holder.unwrapKey().orElseThrow().location().equals(plains)), "server group result obeys biome generation filter");
            check(stack.getTag().getBoolean("IsGroup") && group.toString().equals(stack.getTag().getString("ECFiltersStructureCategory")), "group identity retained for next query");
            System.out.println("ORIGINAL COMPAT PASS: native ordinary/next APIs; XP rejection/cancel/once; durability; clear cache; server filtered group; limit; no model override");
        } finally {
            SearchService.cancel(player, true); SearchService.clearCache(player.getUUID());
            cost.set(oldCost); durability.set(oldDurability); limit.set(oldLimit);
        }
    }
    private static ForgeConfigSpec.IntValue config(String name) throws Exception {
        return (ForgeConfigSpec.IntValue) ConfigHandler.GENERAL.getClass().getField(name).get(ConfigHandler.GENERAL);
    }
    private static void await(ItemStack stack, MinecraftServer server) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (SearchService.activeCount() > 0 && System.nanoTime() < deadline) { SearchService.tick(); SmokeChecks.pump(server); Thread.sleep(1); }
        check(ExplorersCompass.explorersCompass.getState(stack) == CompassState.FOUND, "native request finds actual structure");
    }
}
