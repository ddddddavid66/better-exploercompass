package com.chaosthedude.explorerscompass.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.sorting.ISorting;
import com.chaosthedude.explorerscompass.sorting.NameSorting;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.StructureUtils;

import com.chaosthedude.explorerscompass.unified.client.*;
import com.chaosthedude.explorerscompass.unified.network.*;
import com.chaosthedude.explorerscompass.unified.search.SearchService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ExplorersCompassScreen extends Screen implements com.chaosthedude.explorerscompass.unified.client.FilterScreenAccess {

	private Level level;
	private Player player;
	private List<ResourceLocation> allowedStructureKeys;
	private List<ResourceLocation> structureKeysMatchingSearch;
	private ItemStack stack;
	private ExplorersCompassItem explorersCompass;
	private ResourceLocation foundStructureKey;
	private Button searchButton;
	private Button searchForNextButton;
	private Button searchForGroupButton;
	private Button sortByButton;
	private Button teleportButton;
	private Button cancelButton;
	private TransparentTextField searchTextField;
	private StructureSearchList selectionList;
	private ISorting sortingCategory;
    private FilterUi biomeFilter, dimensionFilter;
    private Button locateBiomeButton, clearCacheButton, landingModeButton;
    private int sidebarScroll, sidebarMaximum;
    private long catalogRevision = -1;
    private FilterUi activePopup() { return dimensionFilter != null && dimensionFilter.isOpen() ? dimensionFilter : biomeFilter; }
    @Override public boolean popupOpen() { return biomeFilter != null && biomeFilter.isOpen() || dimensionFilter != null && dimensionFilter.isOpen(); }
    @Override public boolean isPauseScreen() { return false; }

	public ExplorersCompassScreen(Level level, Player player, ItemStack stack, ExplorersCompassItem explorersCompass, List<ResourceLocation> allowedStructureKeys) {
		super(Component.translatable("string.explorerscompass.selectStructure"));
		this.level = level;
		this.player = player;
		this.stack = stack;
		this.explorersCompass = explorersCompass;

		this.allowedStructureKeys = new ArrayList<ResourceLocation>(allowedStructureKeys);
		structureKeysMatchingSearch = new ArrayList<ResourceLocation>(this.allowedStructureKeys);
		sortingCategory = new NameSorting();

		if (explorersCompass.getState(stack) == CompassState.FOUND) {
			foundStructureKey = explorersCompass.getStructureKey(stack);
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double delta) {
        if(popupOpen())return activePopup().mouseScrolled(delta);
        if(x<125 && y>=30 && y<height-35 && sidebarMaximum>0) { sidebarScroll=net.minecraft.util.Mth.clamp(sidebarScroll-(int)(delta*25),0,sidebarMaximum);layoutButtons();return true; }
        return selectionList.mouseScrolled(x,y,delta);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (popupOpen()) return activePopup().mouseClicked(x,y,button);
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return activePopup() != null && activePopup().mouseDragged(y) || super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        return activePopup() != null && activePopup().mouseReleased() || super.mouseReleased(x,y,button);
    }

	@Override
	protected void init() {
		setupWidgets();
	}

	@Override
	public void tick() {
        searchTextField.tick(); biomeFilter.tick(); dimensionFilter.tick();
        stack = com.chaosthedude.explorerscompass.util.ItemUtils.getHeldItem(player, explorersCompass);
        foundStructureKey = explorersCompass.getState(stack) == CompassState.FOUND ? explorersCompass.getStructureKey(stack) : null;
        if (catalogRevision != ClientSession.revision()) {
            allowedStructureKeys = new ArrayList<>(ExplorersCompass.allowedStructureKeys);
            catalogRevision = ClientSession.revision(); selectionList.setSelected(null); processSearchTerm();
        }
        boolean available = ClientSession.canSearch();
        searchButton.active = searchForGroupButton.active = available && selectionList.hasSelection();
        searchForNextButton.visible = ExplorersCompass.maxNextSearches > 0;
        searchForNextButton.active = available && !SearchService.isBiome(stack) && foundStructureKey != null
                && stack.getOrCreateTag().getInt("ECFiltersNextSearches") < ExplorersCompass.maxNextSearches;
        locateBiomeButton.active = available && ClientSession.selected() != null;
        var blocked = available ? null : net.minecraft.client.gui.components.Tooltip.create(Component.translatable(ClientSession.ready() ? "filters.enter_dimension" : "filters.loading"));
        searchButton.setTooltip(blocked); searchForGroupButton.setTooltip(blocked); searchForNextButton.setTooltip(blocked);
        locateBiomeButton.setTooltip(blocked == null ? net.minecraft.client.gui.components.Tooltip.create(Component.translatable("filters.locate_tip")) : blocked);
        boolean busy = LandingClientState.busy(stack), fallback = LandingClientState.fallback(stack);
        teleportButton.visible = ExplorersCompass.canTeleport;
        teleportButton.active = canTeleport() && !busy;
        teleportButton.setMessage(Component.translatable(busy ? "filters.teleport_preparing" : fallback ? "filters.teleport_fallback" : "string.explorerscompass.teleport"));
        teleportButton.setTooltip(fallback ? net.minecraft.client.gui.components.Tooltip.create(LandingClientState.confirmation(stack)) : null);
        landingModeButton.setMessage(Component.translatable("filters.landing_mode",Component.translatable(com.chaosthedude.explorerscompass.config.ConfigHandler.CLIENT.landingMode.get().key())));
        landingModeButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(com.chaosthedude.explorerscompass.config.ConfigHandler.CLIENT.landingMode.get().key()+"_tip")));
        layoutButtons();
    }

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics); super.render(graphics,mouseX,mouseY,partialTick);
        graphics.drawCenteredString(font, title, 65,15,0xffffff);
        if (selectionList.children().isEmpty()) graphics.drawCenteredString(font, Component.translatable("filters.no_structures"), selectionList.getRowLeft()+selectionList.getRowWidth()/2,55,0xaaaaaa);
        biomeFilter.render(graphics,mouseX,mouseY,partialTick); dimensionFilter.render(graphics,mouseX,mouseY,partialTick);
    }

	@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (activePopup() != null && activePopup().keyPressed(key,scanCode,modifiers)) return true;
        boolean handled = super.keyPressed(key,scanCode,modifiers);
        if (searchTextField.isFocused()) { processSearchTerm(); return true; } return handled;
    }

	@Override
	public boolean charTyped(char character, int modifiers) {
        if (activePopup() != null && activePopup().charTyped(character,modifiers)) return true;
        boolean handled = super.charTyped(character,modifiers);
        if (searchTextField.isFocused()) { processSearchTerm(); return true; } return handled;
    }

	public void searchForStructure(ResourceLocation key) { SearchRequests.structure(key, false); }

	public void searchForGroup(ResourceLocation key) { SearchRequests.structure(key, true); }

	public void searchForNext() { SearchRequests.next(); }

	public void teleport() {
        if (canTeleport() && !LandingClientState.busy(stack)) UnifiedNetwork.CHANNEL.sendToServer(new TeleportRequestPacket(SearchService.token(stack),java.util.UUID.randomUUID(),LandingClientState.fallback(stack),com.chaosthedude.explorerscompass.config.ConfigHandler.CLIENT.landingMode.get()));
    }
    private boolean canTeleport() {
        String dimension = stack.getOrCreateTag().getString(SearchService.TARGET_DIMENSION);
        return ExplorersCompass.canTeleport && explorersCompass.getState(stack) == CompassState.FOUND
                && (dimension.equals(player.level().dimension().location().toString()) || dimension.isEmpty() && !SearchService.isBiome(stack));
    }

	public void processSearchTerm() {
		structureKeysMatchingSearch = new ArrayList<ResourceLocation>();
		String searchTerm = searchTextField.getValue().toLowerCase();
		for (ResourceLocation key : allowedStructureKeys) {
			if (searchTerm.startsWith("@")) {
				if (StructureUtils.getPrettyStructureSource(key).toLowerCase().contains(searchTerm.substring(1))) {
					structureKeysMatchingSearch.add(key);
				}
			} else if (StructureUtils.getPrettyStructureName(key).toLowerCase().contains(searchTerm)) {
				structureKeysMatchingSearch.add(key);
			}
		}
		selectionList.refreshList(true);
	}

	public Player getPlayer() {
		return player;
	}

	public List<ResourceLocation> sortStructures() {
		final List<ResourceLocation> structures = structureKeysMatchingSearch;
		Collections.sort(structures, new NameSorting());
		Collections.sort(structures, sortingCategory);
		return ClientSession.filter(structures);
	}

	private void setupWidgets() {
        String saved = searchTextField == null ? "" : searchTextField.getValue();
        ClientSession.prepare();
		clearWidgets();
		searchButton = addRenderableWidget(new TransparentButton(10, 40, 110, 20, Component.translatable("string.explorerscompass.search"), (onPress) -> {
			if (selectionList.hasSelection()) {
				searchForStructure(selectionList.getSelected().getStructureKey());
			}
		}));
		searchButton.active = false;

		searchForGroupButton = addRenderableWidget(new TransparentButton(10, 65, 110, 20, Component.translatable("string.explorerscompass.searchForGroup"), (onPress) -> {
			if (selectionList.hasSelection()) {
				searchForGroup(selectionList.getSelected().getGroupKey());
			}
		}));
		searchForGroupButton.active = false;

		searchForNextButton = addRenderableWidget(new TransparentButton(10, 90, 110, 20, Component.translatable("string.explorerscompass.searchForNext"), (onPress) -> {
			searchForNext();
		}));
		searchForNextButton.visible = ExplorersCompass.maxNextSearches > 0;
		searchForNextButton.active = false;

		sortByButton = addRenderableWidget(new TransparentButton(10, 125, 110, 20, Component.translatable("string.explorerscompass.sortBy").append(Component.literal(": " + sortingCategory.getLocalizedName())), (onPress) -> {
			sortingCategory = sortingCategory.next();
			sortByButton.setMessage(Component.translatable("string.explorerscompass.sortBy").append(Component.literal(": " + sortingCategory.getLocalizedName())));
			selectionList.refreshList(true);
		}));
		if (!searchForNextButton.visible) {
			sortByButton.setPosition(10, 100);
		}

		teleportButton = addRenderableWidget(new TransparentButton(width - 120, 10, 110, 20, Component.translatable("string.explorerscompass.teleport"), (onPress) -> {
			teleport();
		}));
		teleportButton.visible = ExplorersCompass.canTeleport;
		teleportButton.active = false;

		cancelButton = addRenderableWidget(new TransparentButton(10, height - 30, 110, 20, Component.translatable("gui.cancel"), (onPress) -> {
			minecraft.setScreen(null);
		}));

		searchTextField = new TransparentTextField(font, 130, 10, 140, 20, Component.translatable("string.explorerscompass.search"));
		addRenderableWidget(searchTextField);

		selectionList = new StructureSearchList(this, minecraft, foundStructureKey, 130, 40, width - 140, height - 50, 50);
		addRenderableWidget(selectionList);
        biomeFilter = new FilterUi(this,10,150,this::processSearchTerm);
        dimensionFilter = new FilterUi(this,10,175,this::processSearchTerm,true);
        biomeFilter.onOpen(dimensionFilter::close); dimensionFilter.onOpen(biomeFilter::close);
        addRenderableWidget(biomeFilter.button()); addRenderableWidget(dimensionFilter.button());
        locateBiomeButton = addRenderableWidget(new TransparentButton(10,200,110,20,Component.translatable("filters.locate_biome"), ignored -> {
            if (ClientSession.canSearch() && ClientSession.selected() != null) {
                UnifiedNetwork.CHANNEL.sendToServer(new BiomeSearchPacket(ClientSession.selected(),ClientSession.currentDimension())); minecraft.setScreen(null);
            }
        }));
        landingModeButton=addRenderableWidget(new TransparentButton(10,225,110,20,Component.empty(),ignored -> {
            var config=com.chaosthedude.explorerscompass.config.ConfigHandler.CLIENT.landingMode;
            config.set(config.get().next());config.save();LandingClientState.clear();
            UnifiedNetwork.CHANNEL.sendToServer(new LandingModePacket(config.get()));
        }));
        clearCacheButton = addRenderableWidget(new TransparentButton(10,225,110,20,Component.translatable("filters.clear_cache"),ignored -> UnifiedNetwork.CHANNEL.sendToServer(new ClearCachePacket())));
        layoutButtons(); searchTextField.setValue(saved); processSearchTerm(); ClientSession.requestCatalog();
    }
    private void layoutButtons() {
        // 保持原按钮尺寸；小窗口中只滚动左栏，取消按钮和结构列表保持固定。
        boolean next=ExplorersCompass.maxNextSearches>0;
        var buttons=new ArrayList<Button>(List.of(searchButton,searchForGroupButton));
        if(next)buttons.add(searchForNextButton);else searchForNextButton.visible=false;
        buttons.addAll(List.of(sortByButton,biomeFilter.button(),dimensionFilter.button(),locateBiomeButton,landingModeButton,clearCacheButton));
        int top=30,bottom=height-35;
        int gap=Math.min(25,Math.max(21,(bottom-top-20)/Math.max(1,buttons.size()-1)));
        sidebarMaximum=Math.max(0,(buttons.size()-1)*gap+20-(bottom-top));
        sidebarScroll=net.minecraft.util.Mth.clamp(sidebarScroll,0,sidebarMaximum);
        for(int i=0;i<buttons.size();i++) {
            var button=buttons.get(i);int y=top+i*gap-sidebarScroll;
            button.setY(y);button.visible=y>=top && y+20<=bottom;
        }
    }

}
