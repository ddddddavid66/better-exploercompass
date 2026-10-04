package com.chaosthedude.explorerscompass.unified.client;

import com.chaosthedude.explorerscompass.util.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** 使用扩展 long 字段，原物品 int 采样字段只用于兼容旧渲染器。 */
public final class ProgressHud {
    private ProgressHud() {}
    public static void render(GuiGraphics graphics, ItemStack stack, int row) {
        var tag = stack.getTag();
        if (tag == null || !tag.contains("ECFiltersSearchMode")) return;
        draw(graphics, Component.translatable("filters.mode." + tag.getString("ECFiltersSearchMode")).getString(), row);
        draw(graphics, Component.translatable("filters.sample_progress", String.format("%,d", tag.getLong("ECFiltersSamples")),
                String.format("%,d", tag.getLong("ECFiltersSampleLimit"))).getString(), row + 1);
        draw(graphics, Component.translatable("filters.stage." + tag.getString("ECFiltersSearchStage")).getString(), row + 2);
        if ("feature".equals(tag.getString("ECFiltersTargetKind")) && "found".equals(tag.getString("ECFiltersSearchStage")))
            draw(graphics, Component.translatable(tag.getBoolean("ECUnifiedFeatureExpanded")
                    ? "filters.end_expanded" : "filters.end_pending").getString(), row + 3);
    }
    private static void draw(GuiGraphics graphics, String text, int row) {
        RenderUtils.drawConfiguredStringOnHUD(graphics, text, 5, 5, 0xAAAAAA, row);
    }
}
