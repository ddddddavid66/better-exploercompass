package com.chaosthedude.explorerscompass.unified.search;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.unified.network.CatalogService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 费用只在接受请求后扣除一次；结构找到后扣耐久，传送次数由成功事件记录。 */
public final class SearchRules {
    private SearchRules() {}
    public static int xp(ServerLevel level, ResourceLocation key) { return CatalogService.xp(level, key); }
    public static boolean broken(ItemStack stack) { return ExplorersCompass.explorersCompass.isBroken(stack); }
    public static boolean affordable(ServerPlayer player, ItemStack stack, ResourceLocation costKey) {
        if (broken(stack)) { message(player, "filters.compass_broken"); return false; }
        if (!player.getAbilities().instabuild && player.experienceLevel < xp(player.serverLevel(), costKey)) {
            message(player, "filters.insufficient_xp"); return false;
        }
        return true;
    }
    public static void charge(ServerPlayer player, ResourceLocation key) {
        if (!player.getAbilities().instabuild) player.giveExperienceLevels(-xp(player.serverLevel(), key));
    }
    public static void found(ItemStack stack) {
        if (ConfigHandler.GENERAL.compassDurability.get() > 0)
            stack.getOrCreateTag().putInt("CompassDamage", stack.getOrCreateTag().getInt("CompassDamage") + 1);
    }
    public static int nextLimit() { return ConfigHandler.GENERAL.maxNextSearches.get(); }
    public static int clientNextLimit() { return ExplorersCompass.maxNextSearches; }
    public static int clientXp(ResourceLocation key) { return ExplorersCompass.xpLevelsForAllowedStructureKeys.getOrDefault(key, 0); }
    public static void message(ServerPlayer player, String key) {
        if (player.connection != null) player.displayClientMessage(net.minecraft.network.chat.Component.translatable(key), true);
    }
}
