package com.chaosthedude.explorerscompass.unified.creature.client;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.unified.client.*;
import com.chaosthedude.explorerscompass.unified.data.SearchText;
import com.chaosthedude.explorerscompass.unified.creature.catalog.CreatureEntry;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.*;
import net.minecraftforge.registries.ForgeRegistries;

/** 原生绘制边框与卡片，不复制图鉴图片；预览实体不加入世界。 */
public final class CreatureScreen extends Screen {
    private static final Map<ResourceLocation,Entity> previews=new HashMap<>();
    private static Object previewLevel;
    private final Map<ResourceLocation,SearchText> texts=new HashMap<>();
    private EditBox input,popupInput,number;
    private Button search,teleport,dimensionButton,biomeButton,roundButton;
    private ResourceLocation dimension,biome,selected;
    private String popup="";private int scroll,popupScroll;
    private List<CreatureEntry> matches=List.of();private List<ResourceLocation> popupMatches=List.of();
    public CreatureScreen(){super(Component.literal("生物定位指南针"));dimension=parse(ConfigHandler.CLIENT.creatureDimension.get());biome=parse(ConfigHandler.CLIENT.creatureBiome.get());}
    private static ResourceLocation parse(String value){return value.isBlank()?null:ResourceLocation.tryParse(value);}
    public static void clearPreviews(){previews.clear();previewLevel=null;}
    @Override public boolean isPauseScreen(){return false;}
    @Override protected void init(){
        input=new EditBox(font,26,19,Math.max(40,width-198),20,Component.literal("搜索生物"));input.setHint(Component.literal("搜索生物：中文 / English / 拼音"));input.setMaxLength(128);input.setResponder(s->{scroll=0;refresh();});addRenderableWidget(input);
        search=button(width-163,17,68,24,"搜索",b->{if(!CreatureClient.finished)CreatureClient.stop();else if(selected!=null)CreatureClient.search(selected,dimension,biome);});
        teleport=button(width-90,17,66,24,"传送",b->CreatureClient.teleport());
        int side=sidebar();dimensionButton=button(21,64,side-20,26,"",b->openPicker("dimension"));biomeButton=button(21,98,side-20,26,"",b->openPicker("biome"));
        roundButton=button(21,132,side-20,26,"",b->openPicker("rounds"));
        button(21,height-38,side-20,22,"关闭",b->onClose());
        button(width-155,height-38,60,22,"上一只",b->CreatureClient.cycle(-1));button(width-89,height-38,65,22,"下一只",b->CreatureClient.cycle(1));
        refresh();
    }
    private int sidebar(){return Math.max(112,Math.min(155,width/5));}
    private GoldButton button(int x,int y,int w,int h,String text,Button.OnPress action){GoldButton b=new GoldButton(x,y,w,h,Component.literal(text),action);addRenderableWidget(b);return b;}
    private static final class GoldButton extends Button {
        GoldButton(int x,int y,int w,int h,Component text,OnPress action){super(x,y,w,h,text,action,DEFAULT_NARRATION);}
        @Override public void renderWidget(GuiGraphics g,int mx,int my,float delta){panel(g,getX(),getY(),getWidth(),getHeight(),isHoveredOrFocused()?0xff3f3b2d:0xff252627);g.drawCenteredString(Minecraft.getInstance().font,getMessage(),getX()+getWidth()/2,getY()+(getHeight()-8)/2,active?0xffe8ddb3:0xff777777);}
    }
    private static void panel(GuiGraphics g,int x,int y,int w,int h,int fill){g.fill(x,y,x+w,y+h,0xff57472a);g.fill(x+1,y+1,x+w-1,y+h-1,0xffc5a659);g.fill(x+3,y+3,x+w-3,y+h-3,fill);}
    private String name(ResourceLocation key,boolean dim){return key==null?"默认":dim?DimensionNames.name(key):FilterUi.biomeName(key);}
    private void refresh(){
        if(input==null)return;
        matches=CreatureClient.entries.stream().filter(e->e.matches(dimension,biome)).filter(e->texts.computeIfAbsent(e.entity(),id->SearchText.of(e.name()+" "+e.english()+" "+String.join(" ",e.aliases()),id.toString())).matches(input.getValue())).sorted(Comparator.comparing(CreatureEntry::name).thenComparing(e->e.entity().toString())).toList();
        if(selected!=null && matches.stream().noneMatch(e->e.entity().equals(selected)))selected=null;
        dimensionButton.setMessage(Component.literal(clip("维度 : "+name(dimension,true),dimensionButton.getWidth()-10)));
        biomeButton.setMessage(Component.literal(clip("群系 : "+name(biome,false),biomeButton.getWidth()-10)));
        int rounds=ConfigHandler.CLIENT.creatureRounds.get();roundButton.setMessage(Component.literal("搜索次数 : "+(rounds==4?"默认":rounds)));
        search.setMessage(Component.literal(CreatureClient.finished?"搜索":"停止"));search.active=!CreatureClient.finished || selected!=null && (dimension==null || dimension.equals(ClientSession.currentDimension())) && CreatureClient.entries.stream().anyMatch(e->e.entity().equals(selected) && e.dimensions().contains(ClientSession.currentDimension()));
        teleport.setMessage(Component.literal(CreatureClient.confirmation?"降级传送":"传送"));teleport.active=CreatureClient.finished && CreatureClient.selected()!=null && CreatureClient.canTeleport;
    }
    private String clip(String s,int w){return font.width(s)<=w?s:font.plainSubstrByWidth(s,Math.max(0,w-8))+"…";}
    @Override public void tick(){super.tick();input.tick();refresh();if(popupInput!=null)popupInput.tick();if(number!=null)number.tick();}
    @Override public void render(GuiGraphics g,int mx,int my,float delta){
        g.fill(0,0,width,height,0xffc8b88d);panel(g,6,6,width-12,height-12,0xffbbaa80);panel(g,15,12,width-30,35,0xff252627);panel(g,15,55,sidebar()-7,height-105,0xff252627);
        int left=sidebar()+16,top=57,bottom=height-65,cell=78,cols=Math.max(1,(width-left-20)/cell),rows=Math.max(1,(bottom-top)/cell),start=scroll*cols;
        g.enableScissor(left,top,width-17,bottom);
        for(int i=start;i<Math.min(matches.size(),start+rows*cols+cols);i++) {
            var e=matches.get(i);int x=left+(i-start)%cols*cell,y=top+(i-start)/cols*cell;panel(g,x,y,cell-6,cell-6,e.entity().equals(selected)?0xff4a432b:0xff77776d);
            g.drawCenteredString(font,clip(e.name(),cell-12),x+(cell-6)/2,y+6,0xff171717);
            g.enableScissor(x+4,Math.max(top,y+18),x+cell-10,Math.min(bottom,y+cell-10));renderPreview(g,e,x+cell/2-3,y+cell-12,cell-24,mx,my);g.disableScissor();
            if(popup.isEmpty() && mx>=x && mx<x+cell-6 && my>=y && my<y+cell-6){var tip=new ArrayList<Component>();tip.add(Component.literal(e.name()));tip.add(Component.literal(e.entity().toString()));tip.add(Component.literal(e.biomes().isEmpty()?"群系：待补充（仅默认筛选可见）":"群系："+e.biomes().stream().map(FilterUi::biomeName).toList()));if(!e.note().isBlank())tip.add(Component.literal(e.note()));g.renderComponentTooltip(font,tip,mx,my);}
        }
        g.disableScissor();
        if(matches.isEmpty())g.drawCenteredString(font,CreatureClient.entries.isEmpty()?"正在同步生物目录…":"没有匹配的生物",left+(width-left)/2,80,0xff28251a);
        String state=stage(CreatureClient.state);g.drawString(font,clip(state+"  邻区兜底 "+CreatureClient.round+"/"+ConfigHandler.CLIENT.creatureRounds.get()+"  已检查 "+CreatureClient.chunks+" 区块",width-42),22,height-60,0xff25221a,false);
        var point=CreatureClient.selected();if(point!=null)g.drawString(font,clip("实例 "+(CreatureClient.selection+1)+"/"+CreatureClient.points.size()+"  XYZ "+(int)point.x()+", "+(int)point.y()+", "+(int)point.z()+(point.live()?"":"（待复核）"),width-205),22,height-48,0xff25221a,false);
        super.render(g,mx,my,delta);if(!popup.isEmpty())renderPopup(g,mx,my,delta);
    }
    private void renderPreview(GuiGraphics g,CreatureEntry e,int x,int y,int max,int mx,int my){
        if(previewLevel!=minecraft.level){clearPreviews();previewLevel=minecraft.level;}
        try {
            Entity entity=previews.computeIfAbsent(e.entity(),id->{var t=ForgeRegistries.ENTITY_TYPES.getValue(id);return t==null?null:t.create(minecraft.level);});
            if(entity instanceof LivingEntity living){int scale=(int)Math.max(1,Math.min(23,max/Math.max(living.getBbHeight(),living.getBbWidth()*1.3)));InventoryScreen.renderEntityInInventoryFollowsMouse(g,x,y,scale,x-mx,y-my,living);}
            else g.drawCenteredString(font,"生",x,y-25,0xffe8ddb3);
        }catch(Exception error){g.drawCenteredString(font,"生",x,y-25,0xffe8ddb3);}
    }
    private static String stage(String value){return switch(value){case "initial"->"查询已加载生物";case "locating_biome"->"定位目标群系";case "loading_chunks"->"等待地形及实体加载";case "found"->"已找到";case "not_found"->"本次范围未发现";case "idle"->"请选择生物";case "cancelled"->"已停止";case "reload_entity"->"重新核对实体";case "entity_gone"->"实体已离开或不存在";case "teleport_moved"->"传送成功";case "teleport_confirm"->"再次点击可降级传送";default->value.startsWith("teleport_")?"准备落点："+value.substring(9):"搜索结束："+value;};}
    private void openPicker(String kind){closePicker();popup=kind;popupScroll=0;int x=sidebar()+18;
        if(kind.equals("rounds")){number=new EditBox(font,x+12,91,180,20,Component.literal("兜底次数0～32"));number.setValue(Integer.toString(ConfigHandler.CLIENT.creatureRounds.get()));number.setMaxLength(2);number.setFocused(true);addWidget(number);return;}
        popupInput=new EditBox(font,x+12,70,190,20,Component.literal("搜索"));popupInput.setResponder(s->{popupScroll=0;pickerMatches();});popupInput.setFocused(true);addWidget(popupInput);pickerMatches();
    }
    private void pickerMatches(){var options=popup.equals("dimension")?ClientSession.dimensions():ClientSession.biomesIn(dimension);popupMatches=options.stream().filter(id->SearchText.of(name(id,popup.equals("dimension")),id.toString()).matches(popupInput.getValue())).sorted(Comparator.<ResourceLocation,Boolean>comparing(id->popup.equals("dimension")&&!id.equals(ClientSession.currentDimension())).thenComparing(id->name(id,popup.equals("dimension")))).toList();}
    private void renderPopup(GuiGraphics g,int mx,int my,float d){int x=sidebar()+18,w=Math.min(240,width-x-12),h=Math.min(260,height-75);panel(g,x,58,w,h,0xff252627);
        if(popup.equals("rounds")){g.drawString(font,"初查后的兜底轮数（0～32）",x+12,73,0xffe8ddb3);number.render(g,mx,my,d);g.drawString(font,"Enter 保存；Esc 取消",x+12,122,0xffc8ba92);return;}
        popupInput.setWidth(w-24);popupInput.render(g,mx,my,d);g.drawString(font,"默认",x+12,100,0xffe8ddb3);int visible=Math.max(1,(h-68)/20);
        for(int i=popupScroll;i<Math.min(popupMatches.size(),popupScroll+visible);i++){var id=popupMatches.get(i);g.drawString(font,clip(name(id,popup.equals("dimension"))+(popup.equals("dimension")&&id.equals(ClientSession.currentDimension())?"（当前）":""),w-25),x+12,121+(i-popupScroll)*20,0xffe8ddb3);}
    }
    private void closePicker(){if(popupInput!=null)removeWidget(popupInput);if(number!=null)removeWidget(number);popupInput=null;number=null;popup="";}
    @Override public boolean mouseClicked(double x,double y,int b){
        if(!popup.isEmpty()){int px=sidebar()+18,w=Math.min(240,width-px-12),h=Math.min(260,height-75);if(x<px || x>px+w || y<58 || y>58+h){closePicker();return true;}if(popup.equals("rounds"))return number.mouseClicked(x,y,b);if(popupInput.mouseClicked(x,y,b))return true;
            ResourceLocation id=null;int at=popupScroll+(int)((y-120)/20);if(y>=120 && at>=0 && at<popupMatches.size())id=popupMatches.get(at);else if(y<96)return true;
            if(popup.equals("dimension")){dimension=id;ConfigHandler.CLIENT.creatureDimension.set(id==null?"":id.toString());ConfigHandler.CLIENT.creatureDimension.save();if(biome!=null && !ClientSession.biomesIn(dimension).contains(biome))biome=null;}
            else {biome=id;}ConfigHandler.CLIENT.creatureBiome.set(biome==null?"":biome.toString());ConfigHandler.CLIENT.creatureBiome.save();closePicker();scroll=0;refresh();return true;
        }
        if(super.mouseClicked(x,y,b))return true;int left=sidebar()+16,cell=78,cols=Math.max(1,(width-left-20)/cell);if(x>=left && x<width-17 && y>=57 && y<height-65){int index=scroll*cols+(int)((y-57)/cell)*cols+(int)((x-left)/cell);if(index<matches.size()){selected=matches.get(index).entity();refresh();return true;}}return false;
    }
    @Override public boolean mouseScrolled(double x,double y,double delta){if(!popup.isEmpty()){popupScroll=Math.max(0,Math.min(Math.max(0,popupMatches.size()-3),popupScroll-(int)Math.signum(delta)));return true;}int cols=Math.max(1,(width-sidebar()-36)/78);scroll=Math.max(0,Math.min(Math.max(0,(matches.size()-1)/cols),scroll-(int)Math.signum(delta)));return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){if(!popup.isEmpty()){if(key==256){closePicker();return true;}if(number!=null){if(key==257){try{int n=Integer.parseInt(number.getValue());if(n>=0&&n<=32){ConfigHandler.CLIENT.creatureRounds.set(n);ConfigHandler.CLIENT.creatureRounds.save();closePicker();}}catch(NumberFormatException ignored){}return true;}return number.keyPressed(key,scan,mods);}return popupInput.keyPressed(key,scan,mods);}return super.keyPressed(key,scan,mods);}
    @Override public boolean charTyped(char c,int mods){if(number!=null)return number.charTyped(c,mods);if(popupInput!=null)return popupInput.charTyped(c,mods);return super.charTyped(c,mods);}
}
