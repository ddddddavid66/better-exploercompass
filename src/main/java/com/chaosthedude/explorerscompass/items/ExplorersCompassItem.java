package com.chaosthedude.explorerscompass.items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.gui.GuiWrapper;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.PlayerUtils;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.chaosthedude.explorerscompass.unified.search.SearchService;
import com.chaosthedude.explorerscompass.unified.network.CatalogService;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.network.NetworkDirection;

public class ExplorersCompassItem extends Item {

	public static final String NAME = "explorerscompass";


	public ExplorersCompassItem() {
		super(new Properties().stacksTo(1));
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) restoreState(serverPlayer, stack);
        // 按键状态在 PRESS_SHIFT_KEY 后立即生效；蹲伏姿态可能要到下一 tick 才更新。
        if (player.isShiftKeyDown()) {
            if (player instanceof ServerPlayer serverPlayer) { SearchService.cancel(serverPlayer, true); setInactive(stack, player); }
        } else if (!isBroken(stack)) {
            if (level.isClientSide()) GuiWrapper.openGUI(level, player, stack);
            // 目录由客户端请求一次，服务端只发送统一目录，不再发送旧 SyncPacket。
        }
        return new InteractionResultHolder<>(InteractionResult.PASS, stack);
    }

    @Override public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
        if (entity instanceof ServerPlayer player) restoreState(player, stack);
    }
    private void restoreState(ServerPlayer player, ItemStack stack) {
        if (getState(stack) == CompassState.SEARCHING && !SearchService.owns(player, stack)) setInactive(stack, player);
    }

	@Override
 	public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
 		if (getState(oldStack) == getState(newStack)) {
 			return false;
 		}
 		return super.shouldCauseReequipAnimation(oldStack, newStack, slotChanged);
 	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		int max = ConfigHandler.GENERAL.compassDurability.get();
		if (max > 0) {
			int damage = getDamage(stack);
			return damage > 0;
		}
		return false;
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		int max = ConfigHandler.GENERAL.compassDurability.get();
		if (max > 0) {
			int damage = getDamage(stack);
			return Math.max(0, Math.min(13, Math.round(13.0f * (1.0f - (float) damage / max))));
		}
		return 13;
	}

	@Override
	public int getBarColor(ItemStack stack) {
		int max = ConfigHandler.GENERAL.compassDurability.get();
		int damage = getDamage(stack);
		float f = max > 0 ? (float) damage / max : 0.0f;
		return Mth.hsvToRgb(Math.max(0.0F, (1.0F - f) / 3.0F), 1.0F, 1.0F);
	}

	public void searchForStructure(ServerLevel level, Player player, BlockPos pos, ResourceLocation key, boolean group, ItemStack stack) {
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.serverLevel() == level)
            SearchService.requestStructures(serverPlayer, stack, key, group, false, null);
    }

	public void searchForNextStructure(ServerLevel level, Player player, BlockPos pos, ItemStack stack) {
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.serverLevel() == level)
            SearchService.requestStructures(serverPlayer, stack, getStructureKey(stack), false, true, null);
    }

	

	

	public boolean isActive(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			return getState(stack) != CompassState.INACTIVE;
		}

		return false;
	}

	public void setSearching(ItemStack stack, ResourceLocation structureKey, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putString("StructureKey", structureKey.toString());
			stack.getTag().putInt("State", CompassState.SEARCHING.getID());
		}
	}

	public void setFound(ItemStack stack, ResourceLocation structureKey, int x, int z, int samples) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("State", CompassState.FOUND.getID());
			stack.getTag().putString("StructureKey", structureKey.toString());
			stack.getTag().putInt("FoundX", x);
			stack.getTag().putInt("FoundZ", z);
			stack.getTag().putInt("Samples", samples);
		}
	}

	public void setIsGroup(ItemStack stack, boolean isGroup) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putBoolean("IsGroup", isGroup);
		}
	}

	public void setNotFound(ItemStack stack, int searchRadius, int samples) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("State", CompassState.NOT_FOUND.getID());
			stack.getTag().putInt("SearchRadius", searchRadius);
			stack.getTag().putInt("Samples", samples);
		}
	}

	public void setInactive(ItemStack stack, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("State", CompassState.INACTIVE.getID());
			stack.getTag().remove("PrevPos");
		}
	}

	public void setState(ItemStack stack, BlockPos pos, CompassState state, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("State", state.getID());
		}
	}

	public void setFoundStructureX(ItemStack stack, int x, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("FoundX", x);
		}
	}

	public void setFoundStructureZ(ItemStack stack, int z, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("FoundZ", z);
		}
	}

	public void setStructureKey(ItemStack stack, ResourceLocation structureKey, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putString("StructureKey", structureKey.toString());
		}
	}

	public void setSearchRadius(ItemStack stack, int searchRadius, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("SearchRadius", searchRadius);
		}
	}

	public void setSamples(ItemStack stack, int samples, Player player) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putInt("Samples", samples);
		}
	}

	public void setDisplayCoordinates(ItemStack stack, boolean displayPosition) {
		if (ItemUtils.verifyNBT(stack)) {
			stack.getTag().putBoolean("DisplayCoordinates", displayPosition);
		}
	}

	public CompassState getState(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			return CompassState.fromID(stack.getTag().getInt("State"));
		}

		return null;
	}

	public boolean getIsGroup(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack) && stack.getTag().contains("IsGroup")) {
			return stack.getTag().getBoolean("IsGroup");
		}
		return false;
	}

    

    

	public int getFoundStructureX(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			return stack.getTag().getInt("FoundX");
		}

		return 0;
	}

	public int getFoundStructureZ(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			return stack.getTag().getInt("FoundZ");
		}

		return 0;
	}

	public ResourceLocation getStructureKey(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack) && stack.getTag().contains("StructureKey")) {
			return new ResourceLocation(stack.getTag().getString("StructureKey"));
		}

		return null;
	}

	public int getSearchRadius(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			return stack.getTag().getInt("SearchRadius");
		}

		return -1;
	}

	public int getSamples(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack)) {
			return stack.getTag().getInt("Samples");
		}

		return -1;
	}

	public int getDistanceToBiome(Player player, ItemStack stack) {
		return StructureUtils.getHorizontalDistanceToLocation(player, getFoundStructureX(stack), getFoundStructureZ(stack));
	}

	public boolean shouldDisplayCoordinates(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack) && stack.getTag().contains("DisplayCoordinates")) {
			return stack.getTag().getBoolean("DisplayCoordinates");
		}

		return true;
	}

	public int getDamage(ItemStack stack) {
		if (ItemUtils.verifyNBT(stack) && stack.getTag().contains("CompassDamage")) {
			return stack.getTag().getInt("CompassDamage");
		}
		return 0;
	}

	

	public boolean isBroken(ItemStack stack) {
		int max = ConfigHandler.GENERAL.compassDurability.get();
		if (max > 0) {
			return getDamage(stack) >= max;
		}
		return false;
	}

}
