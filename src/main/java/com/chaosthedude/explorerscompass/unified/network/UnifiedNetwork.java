package com.chaosthedude.explorerscompass.unified.network;

import com.chaosthedude.explorerscompass.unified.CompassUnified;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class UnifiedNetwork {
    public static final String VERSION = "unified-2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CompassUnified.MOD_ID, "unified"), () -> VERSION,
            UnifiedNetwork::acceptsVersion, UnifiedNetwork::acceptsVersion);

    private UnifiedNetwork() {}
    /** 缺少通道、旧扩展数字协议和原版客户端均不能绕过版本验证。 */
    public static boolean acceptsVersion(String remote) { return VERSION.equals(remote); }

    public static void register() {
        CHANNEL.registerMessage(12, LandingModePacket.class, LandingModePacket::encode, LandingModePacket::decode, LandingModePacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(13, SearchLifecyclePacket.class, SearchLifecyclePacket::encode, SearchLifecyclePacket::decode, SearchLifecyclePacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(10, StructureSearchPacket.class, StructureSearchPacket::encode, StructureSearchPacket::decode,
                StructureSearchPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(11, ClearCachePacket.class, ClearCachePacket::encode, ClearCachePacket::decode,
                ClearCachePacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(0, CatalogRequestPacket.class, CatalogRequestPacket::encode,
                CatalogRequestPacket::decode, CatalogRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, BiomeCatalogPacket.class, BiomeCatalogPacket::encode,
                BiomeCatalogPacket::decode, BiomeCatalogPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        CHANNEL.registerMessage(3, BiomeSearchPacket.class, BiomeSearchPacket::encode,
                BiomeSearchPacket::decode, BiomeSearchPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(4, SearchStatusPacket.class, SearchStatusPacket::encode,
                SearchStatusPacket::decode, SearchStatusPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        CHANNEL.registerMessage(6, TeleportRequestPacket.class, TeleportRequestPacket::encode,
                TeleportRequestPacket::decode, TeleportRequestPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(8, TeleportSucceededPacket.class, TeleportSucceededPacket::encode,
                TeleportSucceededPacket::decode, TeleportSucceededPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(9, TeleportStatusPacket.class, TeleportStatusPacket::encode, TeleportStatusPacket::decode, TeleportStatusPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(7, CatalogInvalidatedPacket.class, CatalogInvalidatedPacket::encode,
                CatalogInvalidatedPacket::decode, CatalogInvalidatedPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void send(ServerPlayer player, Object message) {
        if (player.connection == null) return; // Forge FakePlayer 没有客户端连接。
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
