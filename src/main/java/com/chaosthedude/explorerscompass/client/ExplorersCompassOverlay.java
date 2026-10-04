package com.chaosthedude.explorerscompass.client;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraft.client.gui.GuiGraphics;
@OnlyIn(Dist.CLIENT)
public final class ExplorersCompassOverlay implements IGuiOverlay {
    @Override public void render(ForgeGui gui, GuiGraphics graphics, float partial, int width, int height) {
        com.chaosthedude.explorerscompass.unified.client.CompassHud.render(graphics);
    }
}
