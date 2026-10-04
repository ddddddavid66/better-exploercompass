package com.chaosthedude.explorerscompass.unified.client;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.*;
import com.chaosthedude.explorerscompass.unified.search.SearchService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 结构与群系共用一套 HUD；目标维度不符时停止显示方向性的距离和坐标。 */
public final class CompassHud {
    private CompassHud() {}
    public static void render(GuiGraphics graphics) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || mc.options.renderDebug
                || mc.screen != null && !(mc.screen instanceof ChatScreen && ConfigHandler.CLIENT.displayWithChatOpen.get())) return;
        var compass = ExplorersCompass.explorersCompass;
        var stack = ItemUtils.getHeldItem(mc.player, compass);
        if (stack.isEmpty()) return;
        var state = compass.getState(stack);
        if (state == null || state == CompassState.INACTIVE) return;
        boolean biome = SearchService.isBiome(stack);
        var tag = stack.getOrCreateTag();
        ResourceLocation key = biome ? ResourceLocation.tryParse(tag.getString(SearchService.BIOME_KEY)) : compass.getStructureKey(stack);
        line(graphics, text("string.explorerscompass.status"), 0, 0xffffff);
        String status = state == CompassState.FOUND ? "found" : state == CompassState.SEARCHING ? "searching" : "notFound";
        line(graphics, text("string.explorerscompass." + status), 1, 0xaaaaaa);
        line(graphics, text(biome ? "filters.biome_label" : "string.explorerscompass.structure"), 3, 0xffffff);
        line(graphics, key == null ? "" : biome ? FilterUi.biomeName(key) : StructureUtils.getPrettyStructureName(key), 4, 0xaaaaaa);
        String dimensionId = tag.getString(SearchService.TARGET_DIMENSION);
        var dimension = com.chaosthedude.explorerscompass.unified.data.TargetDimension.parse(dimensionId);
        if (dimension == null && state == CompassState.SEARCHING) dimension = mc.level.dimension().location();
        line(graphics, text("filters.dimension_label"), 6, 0xffffff);
        line(graphics, DimensionNames.name(dimension), 7, 0xaaaaaa);
        if (biome && dimension == null && state == CompassState.FOUND) { line(graphics, text("filters.relocate_legacy"), 9, 0xffffaa); return; }
        if (state == CompassState.FOUND && dimension != null && !dimension.equals(mc.level.dimension().location())) {
            line(graphics, Component.translatable("filters.return_dimension", DimensionNames.name(dimension)).getString(), 9, 0xffffaa); return;
        }
        int progress = 9;
        if (state == CompassState.FOUND && compass.shouldDisplayCoordinates(stack)) {
            int x = compass.getFoundStructureX(stack), z = compass.getFoundStructureZ(stack);
            String coordinates = tag.contains(SearchService.FOUND_Y) ? x + ", " + tag.getInt(SearchService.FOUND_Y) + ", " + z : x + ", " + z;
            line(graphics, text("string.explorerscompass.coordinates"), 9, 0xffffff); line(graphics, coordinates, 10, 0xaaaaaa);
            line(graphics, text("string.explorerscompass.distance"), 12, 0xffffff);
            line(graphics, Integer.toString(compass.getDistanceToBiome(mc.player, stack)), 13, 0xaaaaaa); progress = 15;
        } else if (!biome) {
            line(graphics, text("string.explorerscompass.radius"), 9, 0xffffff);
            line(graphics, Integer.toString(compass.getSearchRadius(stack)), 10, 0xaaaaaa); progress = 12;
        }
        ProgressHud.render(graphics, stack, progress);
    }
    private static String text(String key) { return Component.translatable(key).getString(); }
    private static void line(GuiGraphics graphics, String text, int row, int color) { RenderUtils.drawConfiguredStringOnHUD(graphics, text, 5, 5, color, row); }
}
