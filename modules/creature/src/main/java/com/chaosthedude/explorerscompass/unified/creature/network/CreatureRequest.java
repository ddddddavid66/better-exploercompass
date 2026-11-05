package com.chaosthedude.explorerscompass.unified.creature.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

/** 客户端只提交类型、筛选和UUID，绝不提交传送坐标。 */
public record CreatureRequest(CompoundTag data) {
    public static void encode(CreatureRequest p,FriendlyByteBuf b){b.writeNbt(p.data);}
    public static CreatureRequest decode(FriendlyByteBuf b){return new CreatureRequest(b.readNbt());}
    public static void handle(CreatureRequest p,Supplier<NetworkEvent.Context> c){var ctx=c.get();ctx.enqueueWork(()->{if(ctx.getSender()!=null && p.data!=null){if(p.data.getString("action").startsWith("preexplore_"))com.chaosthedude.explorerscompass.unified.creature.search.CreaturePreExplore.request(ctx.getSender(),p.data);else com.chaosthedude.explorerscompass.unified.creature.search.CreatureSearch.request(ctx.getSender(),p.data);}});ctx.setPacketHandled(true);}
}
