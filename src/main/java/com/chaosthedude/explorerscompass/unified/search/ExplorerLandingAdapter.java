package com.chaosthedude.explorerscompass.unified.search;

import com.david.compass.runtime.game.search.TeleportService;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.unified.network.TeleportStatusPacket;
import com.chaosthedude.explorerscompass.unified.network.TeleportSucceededPacket;
import com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork;
import com.chaosthedude.explorerscompass.util.CompassState;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** 探险者产品持有旧物品 NBT、传送配置、网络及历史计数。 */
public final class ExplorerLandingAdapter implements TeleportService.RecordedCompass {
    private ExplorerLandingAdapter() {}
    public static void register(){TeleportService.bindRecordedCompass(new ExplorerLandingAdapter());}

    @Override public UUID token(ItemStack stack){return SearchService.token(stack);}
    @Override public ResourceLocation biome(ItemStack stack) {
        return SearchService.isBiome(stack)?ResourceLocation.tryParse(stack.getTag().getString(SearchService.BIOME_KEY)):null;
    }
    @Override public boolean allowedReturn(ServerPlayer player,ItemStack stack) {
        return !player.isRemoved() && player.isAlive() && stack.getItem()==ExplorersCompass.explorersCompass
                && (player.getMainHandItem()==stack || player.getOffhandItem()==stack)
                && ConfigHandler.GENERAL.allowTeleport.get();
    }
    @Override public TeleportService.RecordedTarget read(ServerPlayer player,ItemStack stack) {
        if (stack.isEmpty() || !allowedReturn(player,stack) || stack.getTag()==null) return null;
        var compass=ExplorersCompass.explorersCompass;
        if (compass.getState(stack)!=CompassState.FOUND) return null;
        boolean biome=SearchService.isBiome(stack);
        var key=biome?biome(stack):compass.getStructureKey(stack);
        String dimension=stack.getTag().getString(SearchService.TARGET_DIMENSION);
        if (key==null || !(dimension.isEmpty() && !biome) && !dimension.equals(player.level().dimension().location().toString())) return null;
        Integer y=stack.getTag().contains(SearchService.FOUND_Y)?stack.getTag().getInt(SearchService.FOUND_Y):null;
        return new TeleportService.RecordedTarget(SearchService.token(stack),key,biome,
                compass.getFoundStructureX(stack),y,compass.getFoundStructureZ(stack));
    }
    @Override public com.david.compass.runtime.game.search.PortalHint portal(ItemStack stack) {
        return stack.getTag()==null?null:StrongholdPortal.read(stack.getTag());
    }
    @Override public boolean portalRequired(ResourceLocation key) {return StrongholdPortal.applies(key);}
    @Override public void bindLegacy(ServerPlayer player,ItemStack stack,UUID token) {
        var tag=stack.getOrCreateTag();
        if (SearchService.token(stack)==null) tag.putUUID("ECFiltersSearchToken",token);
        if (!tag.contains(SearchService.TARGET_DIMENSION)) tag.putString(SearchService.TARGET_DIMENSION,player.level().dimension().location().toString());
    }
    @Override public void status(ServerPlayer player,UUID token,TeleportService.State state,long revision,String reason) {
        UnifiedNetwork.send(player,new TeleportStatusPacket(token,TeleportStatusPacket.State.valueOf(state.name()),revision,reason));
    }
    @Override public void preparationFailed(ServerPlayer player,ResourceLocation key,int x,int y,int z,String reason) {
        var dimension=player.level().dimension().location();
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "filters.teleport_repeated_chunk_failure",key.toString(),dimension.toString(),x,y,z,
                net.minecraft.network.chat.Component.translatable("filters.teleport_reason_"+reason))
                .withStyle(net.minecraft.ChatFormatting.RED),false);
    }

    @Override public void moved(ServerPlayer player,boolean biome,ResourceLocation key,ResourceLocation dimension,Vec3 point) {
        TeleportEvents.succeeded(player,biome?TeleportSucceededPacket.Kind.BIOME:TeleportSucceededPacket.Kind.STRUCTURE,
                key,dimension,point.x,point.y,point.z);
    }
}
