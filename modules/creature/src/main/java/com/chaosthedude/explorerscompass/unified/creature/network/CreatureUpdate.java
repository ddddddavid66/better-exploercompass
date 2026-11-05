package com.chaosthedude.explorerscompass.unified.creature.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public record CreatureUpdate(CompoundTag data) {
    public static void encode(CreatureUpdate p,FriendlyByteBuf b){b.writeNbt(p.data);}
    public static CreatureUpdate decode(FriendlyByteBuf b){return new CreatureUpdate(b.readNbt());}
    public static void handle(CreatureUpdate p,Supplier<NetworkEvent.Context> c){c.get().enqueueWork(()->com.chaosthedude.explorerscompass.unified.creature.client.CreatureClient.accept(p.data));c.get().setPacketHandled(true);}
}
