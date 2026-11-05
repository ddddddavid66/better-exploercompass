package com.chaosthedude.explorerscompass.unified.creature.network;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

/** 生物产品独立握手和消息注册，不依赖探险者网络。 */
public final class CreatureNetwork {
    public static final String VERSION = "creature-1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("creaturecompass", "main"), () -> VERSION,
            VERSION::equals, VERSION::equals);
    private CreatureNetwork() {}
    public static void register() {
        CHANNEL.registerMessage(0, CreatureRequest.class, CreatureRequest::encode, CreatureRequest::decode,
                CreatureRequest::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, CreatureUpdate.class, CreatureUpdate::encode, CreatureUpdate::decode,
                CreatureUpdate::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, CreatureWorldCatalogPacket.class, CreatureWorldCatalogPacket::encode,
                CreatureWorldCatalogPacket::decode, CreatureWorldCatalogPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public static void send(ServerPlayer player, Object message) {
        if (player.connection != null) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
    public static void invalidateWorldCatalog(net.minecraft.server.MinecraftServer server) {
        for (var player:server.getPlayerList().getPlayers()) {
            var tag=new net.minecraft.nbt.CompoundTag();
            tag.putString("action","world_catalog_invalidated"); tag.putUUID("request",java.util.UUID.randomUUID());
            send(player,new CreatureUpdate(tag));
        }
    }
}
