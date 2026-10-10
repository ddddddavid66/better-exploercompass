package com.chaosthedude.explorerscompass.unified.mixin;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.unified.search.SearchService;
import java.util.Set;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 仅保护统一版六群系定位器，不取消 Alex 对其他定位物品的限制。 */
@Pseudo
@Mixin(targets="com.github.alexmodguy.alexscaves.server.event.CommonEvents",remap=false)
public abstract class AlexLocatorProtectionMixin {
    @Inject(method="checkAndDestroyExploitItem",at=@At("HEAD"),cancellable=true,remap=false,require=0)
    private static void unified$keepCaveCompass(Player player,EquipmentSlot slot,CallbackInfo callback) {
        var stack=player.getItemBySlot(slot);
        if(stack.getItem()!=ExplorersCompass.explorersCompass || !SearchService.isBiome(stack) || stack.getTag()==null)return;
        String key=stack.getTag().getString(SearchService.BIOME_KEY);
        if(Set.of("alexscaves:magnetic_caves","alexscaves:primordial_caves","alexscaves:toxic_caves",
                "alexscaves:abyssal_chasm","alexscaves:forlorn_hollows","alexscaves:candy_cavity").contains(key))callback.cancel();
    }
}
