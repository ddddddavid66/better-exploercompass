package com.chaosthedude.explorerscompass.gui;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import com.chaosthedude.explorerscompass.unified.client.ClientSession;
import com.chaosthedude.explorerscompass.unified.client.FilterScreenAccess;
import net.minecraft.client.gui.Font;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class StructureSearchEntry extends ObjectSelectionList.Entry<StructureSearchEntry> {

	private final Minecraft mc;
	private final ExplorersCompassScreen parentScreen;
	private final Player player;
	private final ResourceLocation structureKey;
	private final StructureSearchList structuresList;
	private final int xpLevels;
	private long lastClickTime;

	public StructureSearchEntry(StructureSearchList structuresList, ResourceLocation structureKey, Player player) {
		this.structuresList = structuresList;
		this.structureKey = structureKey;
		this.player = player;
		parentScreen = structuresList.getParentScreen();
		mc = Minecraft.getInstance();

		int levels = 0;
		if (ExplorersCompass.xpLevelsForAllowedStructureKeys != null && ExplorersCompass.xpLevelsForAllowedStructureKeys.containsKey(structureKey)) {
			levels = ExplorersCompass.xpLevelsForAllowedStructureKeys.get(structureKey);
			if (levels > 3) {
				levels = 3;
			}
		}
		this.xpLevels = levels;
	}

	@Override
	public void render(GuiGraphics graphics, int index, int y, int x, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        String name = StructureUtils.getPrettyStructureName(structureKey);
        String count = Component.translatable("filters.count", ClientSession.count(structureKey)).getString();
        float scale = 0.8F;
        int countWidth = (int) Math.ceil(font.width(count) * scale);
        int nameWidth = Math.max(0, width - countWidth - 12);
        String shown = font.width(name) <= nameWidth ? name
                : font.plainSubstrByWidth(name, Math.max(0, nameWidth - font.width("…"))) + "…";
        int cost = com.chaosthedude.explorerscompass.unified.search.SearchRules.clientXp(structureKey);
        boolean enabled = minecraft.player == null || minecraft.player.getAbilities().instabuild || minecraft.player.experienceLevel >= cost;
        graphics.drawString(font, shown, x + 1, y + 1, enabled ? 0xFFFFFF : 0x808080);
        graphics.pose().pushPose();
        graphics.pose().translate(x + width - countWidth - 2, y + 2, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, count, 0, 0, 0xAAAAAA);
        graphics.pose().popPose();

        // 保留原条目的来源、组和维度三行，只为标题预留右上角的统计区域。
        graphics.drawString(font, Component.translatable("string.explorerscompass.source")
                .append(": " + StructureUtils.getPrettyStructureSource(structureKey)), x + 1, y + font.lineHeight + 3, 0x808080);
        ResourceLocation group = ExplorersCompass.structureKeysToTypeKeys.get(structureKey);
        graphics.drawString(font, Component.translatable("string.explorerscompass.group").append(": ")
                .append(group == null ? "" : StructureUtils.getPrettyStructureName(group)), x + 1, y + font.lineHeight + 14, 0x808080);
        graphics.drawString(font, Component.translatable("string.explorerscompass.dimension")
                .append(": " + StructureUtils.dimensionKeysToString(ExplorersCompass.dimensionKeysForAllowedStructureKeys.get(structureKey))),
                x + 1, y + font.lineHeight + 25, 0x808080);
        if (cost > 0) {
            String xp = Component.translatable("filters.xp_cost", cost).getString();
            graphics.drawString(font, xp, x + width - font.width(xp) - 2, y + font.lineHeight + 3, enabled ? 0x80CC80 : 0xCC8080);
        }
        boolean popup = minecraft.screen instanceof FilterScreenAccess access && access.popupOpen();
        if (!popup && mouseX >= x && mouseX < x + nameWidth && mouseY >= y && mouseY < y + font.lineHeight
                && (!shown.equals(name) || structureKey.equals(new ResourceLocation("stalwart_dungeons:end_dungeon")))) {
            if (structureKey.equals(new ResourceLocation("stalwart_dungeons:end_dungeon"))) graphics.renderTooltip(font,
                    java.util.List.of(Component.literal(name),Component.literal(structureKey.toString()),Component.translatable("filters.end_feature_hint")),java.util.Optional.empty(),mouseX,mouseY);
            else graphics.renderTooltip(font, Component.literal(name), mouseX, mouseY);
        }
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0 && isEnabled()) {
			structuresList.setSelected(this);
			if (Util.getMillis() - lastClickTime < 250L) {
				parentScreen.searchForStructure(structureKey);
				return true;
			} else {
				lastClickTime = Util.getMillis();
				return false;
			}
		}
		return false;
	}

	@Override
	public Component getNarration() {
		return Component.literal(StructureUtils.getPrettyStructureName(structureKey));
	}

	public boolean isEnabled() {
		return ExplorersCompass.infiniteXp || player.experienceLevel >= xpLevels;
	}

	public ResourceLocation getStructureKey() {
		return structureKey;
	}

	public ResourceLocation getGroupKey() {
		return ExplorersCompass.structureKeysToTypeKeys.get(structureKey);
	}

}
