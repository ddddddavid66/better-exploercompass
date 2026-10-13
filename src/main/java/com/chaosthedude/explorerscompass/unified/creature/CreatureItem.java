package com.chaosthedude.explorerscompass.unified.creature;

import net.minecraft.world.item.*;
import net.minecraft.world.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

public final class CreatureItem extends Item {
    public static Item INSTANCE;
    public CreatureItem(){super(new Properties().stacksTo(1));}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){
        ItemStack stack=player.getItemInHand(hand);
        if(player.isShiftKeyDown()){if(player instanceof ServerPlayer p)com.chaosthedude.explorerscompass.unified.creature.search.CreatureSearch.cancel(p.getUUID());}
        else if(level.isClientSide())DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->com.chaosthedude.explorerscompass.unified.creature.client.CreatureClient.open());
        return InteractionResultHolder.sidedSuccess(stack,level.isClientSide());
    }
}
