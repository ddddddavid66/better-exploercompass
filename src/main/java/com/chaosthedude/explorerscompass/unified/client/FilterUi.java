package com.chaosthedude.explorerscompass.unified.client;

import com.chaosthedude.explorerscompass.gui.TransparentButton;
import com.chaosthedude.explorerscompass.unified.data.SearchText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/** 浮层独立接管输入，不能让点击、滚轮和拼音输入穿透到结构列表。 */
public final class FilterUi {
    private final int rowHeight;
    private final boolean dimension;
    private Runnable onOpen = () -> {};
    private final Screen screen;
    private final Font font;
    private final Runnable refreshStructures;
    private final Button button;
    private final long requestedAt = System.currentTimeMillis();
    private final List<BiomeOption> options = new ArrayList<>();
    private List<BiomeOption> matches = List.of();
    private EditBox input;
    private boolean open;
    private boolean dragging;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int scroll;
    private int cursor;
    private long indexedRevision = -1;
    private Language indexedLanguage;

    public FilterUi(Screen screen, int x, int y, Runnable refreshStructures) {
        this(screen, x, y, refreshStructures, false);
    }
    public FilterUi(Screen screen, int x, int y, Runnable refreshStructures, boolean dimension) {
        this.dimension = dimension;
        rowHeight = dimension ? 20 : 28;
        this.screen = screen;
        this.font = Minecraft.getInstance().font;
        this.refreshStructures = refreshStructures;
        button = new TransparentButton(x, y, 110, 20, Component.empty(), ignored -> toggle());
        updateButton();
    }

    public Button button() { return button; }
    public boolean isOpen() { return open; }
    public void onOpen(Runnable action) { onOpen = action; }
    private ResourceLocation selected() { return dimension ? ClientSession.selectedDimension() : ClientSession.selected(); }
    private String name(ResourceLocation key) { return dimension ? DimensionNames.name(key) : biomeName(key); }

    public void tick() {
        if (indexedRevision != ClientSession.revision() || indexedLanguage != Language.getInstance()) {
            rebuildOptions();
            updateButton();
        }
        if (!ClientSession.ready() && System.currentTimeMillis() - requestedAt > 20_000) updateButton();
        if (input != null && open) input.tick();
    }

    private void updateButton() {
        String name = selected() == null ? Component.translatable("filters.default").getString() : name(selected());
        Component full = Component.translatable(dimension ? "filters.dimension" : "filters.biome", name);
        String text = full.getString();
        button.setMessage(Component.literal(clip(text, button.getWidth() - 8)));
        button.active = ClientSession.ready();
        Component tip = !ClientSession.ready()
                ? Component.translatable(System.currentTimeMillis() - requestedAt > 20_000
                    ? "filters.unavailable" : "filters.loading")
                : full.copy().append("\n").append(Component.translatable(dimension ? "filters.dimension_tip" : "filters.biome_tip"));
        button.setTooltip(Tooltip.create(tip));
    }

    private void rebuildOptions() {
        options.clear();
        for (ResourceLocation key : dimension ? DimensionNames.sorted(ClientSession.dimensions()) : ClientSession.biomes()) {
            String name = name(key);
            options.add(new BiomeOption(key, name, SearchText.of(name, key.toString())));
        }
        options.sort(Comparator.<BiomeOption, Boolean>comparing(option -> dimension && !option.key().equals(ClientSession.currentDimension()))
                .thenComparing(BiomeOption::name).thenComparing(option -> option.key().toString()));
        indexedRevision = ClientSession.revision();
        indexedLanguage = Language.getInstance();
        updateMatches();
    }

    private void toggle() {
        if (open) { close(); return; }
        if (!ClientSession.ready()) return;
        onOpen.run();
        panelWidth = Math.min(240, Math.max(100, screen.width - 10));
        panelHeight = Math.min(220, Math.max(80, screen.height - 10));
        left = Math.max(5, Math.min(button.getX() + button.getWidth() + 5, screen.width - panelWidth - 5));
        top = Math.max(5, Math.min(button.getY(), screen.height - panelHeight - 5));
        input = new EditBox(font, left + 7, top + 7, panelWidth - 14, 18,
                Component.translatable("filters.search"));
        input.setMaxLength(256);
        input.setHint(Component.translatable(dimension ? "filters.search_dimension" : "filters.search"));
        input.setResponder(ignored -> updateMatches());
        input.setFocused(true);
        input.setCanLoseFocus(false);
        scroll = 0;
        cursor = 0;
        open = true;
        rebuildOptions();
    }

    public void close() { open = false; dragging = false; }

    private void updateMatches() {
        String query = input == null ? "" : input.getValue();
        matches = options.stream().filter(option -> option.search().matches(query)).toList();
        scroll = 0;
        cursor = query.isBlank() || matches.isEmpty() ? 0 : 1;
    }

    private void choose(ResourceLocation biome) {
        if (dimension) ClientSession.selectDimension(biome); else ClientSession.select(biome);
        close();
        updateButton();
        refreshStructures.run();
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!open) return;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xEF101010);
        graphics.renderOutline(left, top, panelWidth, panelHeight, 0xFF999999);
        input.render(graphics, mouseX, mouseY, partialTick);
        boolean defaultHovered = inside(mouseX, mouseY, left + 3, top + 29, panelWidth - 6, 20);
        if (defaultHovered || cursor == 0) {
            graphics.fill(left + 3, top + 29, left + panelWidth - 3, top + 49, 0x60404040);
        }
        graphics.drawString(font, Component.translatable("filters.default"), left + 8, top + 35,
                selected() == null ? 0xFFFFAA : 0xFFFFFF);
        graphics.fill(left + 4, top + 50, left + panelWidth - 4, top + 51, 0xFF555555);

        graphics.enableScissor(left + 3, listTop(), left + panelWidth - 8, listBottom());
        int hovered = -1;
        for (int index = scroll; index < Math.min(matches.size(), scroll + visibleRows()); index++) {
            int y = listTop() + (index - scroll) * rowHeight;
            BiomeOption option = matches.get(index);
            boolean hover = inside(mouseX, mouseY, left + 3, y, panelWidth - 12, rowHeight);
            if (hover) hovered = index;
            if (hover || cursor == index + 1) {
                graphics.fill(left + 3, y, left + panelWidth - 9, y + rowHeight, 0x60404040);
            }
            String displayed = option.name() + (dimension && option.key().equals(ClientSession.currentDimension()) ? Component.translatable("filters.current_suffix").getString() : "");
            graphics.drawString(font, clip(displayed, panelWidth - 22), left + 8, y + (dimension ? 6 : 3),
                    option.key().equals(selected()) ? 0xFFFFAA : 0xFFFFFF);
            if (!dimension) {
                String count = Component.translatable("filters.count", ClientSession.biomeCount(option.key())).getString();
                graphics.drawString(font, count, left + panelWidth - 12 - font.width(count), y + 15, 0xAAAAAA);
            }
        }
        if (matches.isEmpty()) {
            graphics.drawString(font, Component.translatable("filters.no_biomes"), left + 8, listTop() + 6, 0xAAAAAA);
        }
        graphics.disableScissor();
        if (maxScroll() > 0) {
            int trackHeight = listBottom() - listTop();
            int thumbHeight = Math.max(12, trackHeight * visibleRows() / matches.size());
            int thumbTop = listTop() + scroll * (trackHeight - thumbHeight) / maxScroll();
            graphics.fill(left + panelWidth - 6, listTop(), left + panelWidth - 3, listBottom(), 0xFF303030);
            graphics.fill(left + panelWidth - 6, thumbTop, left + panelWidth - 3, thumbTop + thumbHeight, 0xFF999999);
        }
        if (hovered >= 0) {
            BiomeOption option = matches.get(hovered);
            List<net.minecraft.util.FormattedCharSequence> tip = new ArrayList<>();
            tip.add(Component.literal(option.name()).getVisualOrderText());
            tip.add(Component.literal(option.key().toString()).getVisualOrderText());
            if (!dimension) {
                var dimensions = DimensionNames.sorted(ClientSession.biomeDimensions(option.key()));
                Component text = dimensions.isEmpty() ? Component.translatable("filters.biome_unused")
                        : Component.translatable("filters.biome_dimensions", String.join("、", dimensions.stream().map(DimensionNames::name).toList()));
                tip.addAll(font.split(text, Math.min(260, screen.width - 20)));
            }
            graphics.renderTooltip(font, tip, mouseX, mouseY);
        }
        graphics.pose().popPose();
    }

    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        // 原结构列表的点击区域可能覆盖左侧控件；新增按钮必须先接收点击。
        if (!open) return button.mouseClicked(mouseX, mouseY, mouseButton);
        if (!inside(mouseX, mouseY, left, top, panelWidth, panelHeight)) { close(); return true; }
        if (mouseButton != 0) return true;
        if (inside(mouseX, mouseY, left + 7, top + 7, panelWidth - 14, 18)) {
            input.mouseClicked(mouseX, mouseY, mouseButton);
            return true;
        }
        if (inside(mouseX, mouseY, left + 3, top + 29, panelWidth - 6, 20)) {
            choose(null);
            return true;
        }
        if (maxScroll() > 0 && inside(mouseX, mouseY, left + panelWidth - 8, listTop(), 8, listBottom() - listTop())) {
            dragging = true;
            scrollTo(mouseY);
            return true;
        }
        if (inside(mouseX, mouseY, left + 3, listTop(), panelWidth - 12, listBottom() - listTop())) {
            int index = scroll + (int) ((mouseY - listTop()) / rowHeight);
            if (index < matches.size()) choose(matches.get(index).key());
        }
        return true;
    }

    public boolean mouseScrolled(double delta) {
        if (!open) return false;
        scroll = clamp(scroll - (int) Math.signum(delta) * 3, 0, maxScroll());
        return true;
    }

    public boolean mouseDragged(double mouseY) {
        if (!open) return false;
        if (dragging) scrollTo(mouseY);
        return true;
    }

    public boolean mouseReleased() {
        if (!open) return false;
        dragging = false;
        return true;
    }

    private void scrollTo(double mouseY) {
        double fraction = (mouseY - listTop()) / Math.max(1, listBottom() - listTop());
        scroll = clamp((int) Math.round(fraction * maxScroll()), 0, maxScroll());
    }

    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (!open) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            cursor = clamp(cursor + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), 0, matches.size());
            if (cursor > 0) {
                if (cursor - 1 < scroll) scroll = cursor - 1;
                if (cursor - 1 >= scroll + visibleRows()) scroll = cursor - visibleRows();
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            choose(cursor == 0 ? null : matches.get(cursor - 1).key());
            return true;
        }
        input.keyPressed(key, scanCode, modifiers);
        return true;
    }

    public boolean charTyped(char character, int modifiers) {
        if (!open) return false;
        input.charTyped(character, modifiers);
        return true;
    }

    private int listTop() { return top + 53; }
    private int listBottom() { return top + panelHeight - 5; }
    private int visibleRows() { return Math.max(1, (listBottom() - listTop()) / rowHeight); }
    private int maxScroll() { return Math.max(0, matches.size() - visibleRows()); }

    private String clip(String text, int width) {
        return font.width(text) <= width ? text : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }

    public static String biomeName(ResourceLocation key) {
        String translation = Util.makeDescriptionId("biome", key);
        return I18n.exists(translation) ? I18n.get(translation) : key.toString();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean inside(double x, double y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private record BiomeOption(ResourceLocation key, String name, SearchText search) {}
}
