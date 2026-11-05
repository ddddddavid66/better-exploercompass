package com.chaosthedude.explorerscompass.unified.creature.client;

import com.david.compass.runtime.game.client.BiomeNames;
import com.david.compass.runtime.game.client.DimensionNames;

import com.chaosthedude.explorerscompass.unified.creature.CreatureConfig;
import com.david.compass.runtime.game.data.SearchText;
import com.chaosthedude.explorerscompass.unified.creature.catalog.CreatureEntry;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.ChatFormatting;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.*;
import net.minecraftforge.registries.ForgeRegistries;

/** 原生绘制边框与卡片，不复制图鉴图片；预览实体不加入世界。 */
public final class CreatureScreen extends Screen {
    private static final Map<ResourceLocation,Entity> previews=new HashMap<>();
    private static Object previewLevel;
    private final Map<ResourceLocation,SearchText> texts=new HashMap<>();
    private EditBox input,popupInput,number;
    private Button search,teleport,dimensionButton,biomeButton,roundButton,modeButton,exploreButton;
    private ResourceLocation dimension,biome,selected;
    private String popup="";private int scroll,popupScroll,sideScroll,tipScroll,tipOverflow;
    private CreatureEntry hoveredEntry,tooltipEntry;
    private List<FormattedCharSequence> tooltipLines=List.of();
    private List<CreatureEntry> matches=List.of();private List<ResourceLocation> popupMatches=List.of();
    public CreatureScreen(){super(Component.literal("生物定位指南针"));dimension=parse(CreatureConfig.CLIENT.creatureDimension.get());biome=parse(CreatureConfig.CLIENT.creatureBiome.get());}
    private static ResourceLocation parse(String value){return value.isBlank()?null:ResourceLocation.tryParse(value);}
    public static void clearPreviews(){previews.clear();previewLevel=null;}
    @Override public boolean isPauseScreen(){return false;}
    @Override protected void init(){
        tooltipEntry=null;popup="";popupInput=null;number=null;
        input=new EditBox(font,26,19,Math.max(40,width-198),20,Component.literal("搜索生物"));input.setHint(Component.literal("搜索生物：中文 / English / 拼音"));input.setMaxLength(128);input.setResponder(s->{scroll=0;refresh();});addRenderableWidget(input);
        search=button(width-163,17,68,24,"搜索",b->{if(!CreatureClient.finished)CreatureClient.stop();else if(selected!=null)CreatureClient.search(selected,dimension,biome);});
        teleport=button(width-90,17,66,24,"传送",b->CreatureClient.teleport());
        int side=sidebar();dimensionButton=button(21,60,side-20,26,"",b->openPicker("dimension"));biomeButton=button(21,90,side-20,26,"",b->openPicker("biome"));
        roundButton=button(21,120,side-20,26,"",b->openPicker("rounds"));
        modeButton=button(21,150,side-20,26,"",b->{
            var mode=CreatureConfig.CLIENT.landingMode;
            mode.set(mode.get().next());mode.save();CreatureClient.confirmation=false;refresh();
        });
        exploreButton=button(21,180,side-20,26,"预探索当前维度",b->minecraft.setScreen(new CreaturePreExploreScreen(this)));
        button(21,height-38,side-20,22,"关闭",b->onClose());
        button(width-155,height-38,60,22,"上一只",b->CreatureClient.cycle(-1));button(width-89,height-38,65,22,"下一只",b->CreatureClient.cycle(1));
        refresh();
    }
    private int sidebar(){return Math.max(112,Math.min(155,width/5));}
    private int cardsBottom(){return Math.max(86,height-93);}
    private GoldButton button(int x,int y,int w,int h,String text,Button.OnPress action){GoldButton b=new GoldButton(x,y,w,h,Component.literal(text),action);addRenderableWidget(b);return b;}
    static final class GoldButton extends Button {
        GoldButton(int x,int y,int w,int h,Component text,OnPress action){super(x,y,w,h,text,action,DEFAULT_NARRATION);}
        @Override public void renderWidget(GuiGraphics g,int mx,int my,float delta){panel(g,getX(),getY(),getWidth(),getHeight(),isHoveredOrFocused()?0xff3f3b2d:0xff252627);g.drawCenteredString(Minecraft.getInstance().font,getMessage(),getX()+getWidth()/2,getY()+(getHeight()-8)/2,active?0xffe8ddb3:0xff777777);}
    }
    static void panel(GuiGraphics g,int x,int y,int w,int h,int fill){g.fill(x,y,x+w,y+h,0xff57472a);g.fill(x+1,y+1,x+w-1,y+h-1,0xffc5a659);g.fill(x+3,y+3,x+w-3,y+h-3,fill);}
    private String name(ResourceLocation key,boolean dim){return key==null?"默认":dim?DimensionNames.name(key):com.david.compass.runtime.game.client.BiomeNames.name(key);}
    private void refresh(){
        if(input==null)return;
        matches=CreatureClient.entries.stream().filter(e->e.matches(dimension,biome)).filter(e->texts.computeIfAbsent(e.entity(),id->SearchText.of(e.name()+" "+e.english()+" "+String.join(" ",e.aliases()),id.toString())).matches(input.getValue())).sorted(Comparator.comparing(CreatureEntry::name).thenComparing(e->e.entity().toString())).toList();
        if(selected!=null && matches.stream().noneMatch(e->e.entity().equals(selected)))selected=null;
        dimensionButton.setMessage(Component.literal(clip("维度 : "+name(dimension,true),dimensionButton.getWidth()-10)));
        biomeButton.setMessage(Component.literal(clip("群系 : "+name(biome,false),biomeButton.getWidth()-10)));
        dimensionButton.setTooltip(Tooltip.create(Component.literal("维度 : "+name(dimension,true)+"\n小窗口可在左栏滚动查看其他筛选。")));
        biomeButton.setTooltip(Tooltip.create(Component.literal("群系 : "+name(biome,false)+"\n小窗口可在左栏滚动查看其他筛选。")));
        int rounds=CreatureConfig.CLIENT.creatureRounds.get();roundButton.setMessage(Component.literal("搜索次数 : "+(rounds==4?"默认":rounds)));
        search.setMessage(Component.literal(CreatureClient.finished?"搜索":"停止"));search.active=!CreatureClient.finished || CreatureWorldCatalog.ready() && selected!=null && (dimension==null || dimension.equals(CreatureWorldCatalog.currentDimension())) && CreatureClient.entries.stream().anyMatch(e->e.entity().equals(selected) && e.dimensions().contains(CreatureWorldCatalog.currentDimension()));
        teleport.setMessage(Component.literal(CreatureClient.habitatReady?"前往生境":CreatureClient.teleportPending()?"确认中…":CreatureClient.confirmation?"降级传送":"传送"));teleport.active=(CreatureClient.habitatReady || CreatureClient.finished && CreatureClient.selected()!=null) && CreatureClient.canTeleport && !CreatureClient.teleportPending();
        var mode=CreatureConfig.CLIENT.landingMode.get();
        modeButton.setMessage(Component.translatable("filters.landing_mode",Component.translatable(mode.key())));
        modeButton.setTooltip(Tooltip.create(Component.translatable(mode.key()+"_tip")));
        modeButton.active=!CreatureClient.teleportPending();
        updateSideButtons();
    }
    private void updateSideButtons(){
        sideScroll=Math.max(0,Math.min(sideScroll,Math.max(0,206-cardsBottom())));
        Button[] buttons={dimensionButton,biomeButton,roundButton,modeButton,exploreButton};
        for(int i=0;i<buttons.length;i++){var b=buttons[i];b.setY(60+i*30-sideScroll);b.visible=b.getY()>=57 && b.getY()+b.getHeight()<=cardsBottom();}
    }
    private String clip(String s,int w){return font.width(s)<=w?s:font.plainSubstrByWidth(s,Math.max(0,w-8))+"…";}
    @Override public void tick(){super.tick();input.tick();refresh();if(popupInput!=null)popupInput.tick();if(number!=null)number.tick();}
    @Override public void render(GuiGraphics g,int mx,int my,float delta){
        g.fill(0,0,width,height,0xffc8b88d);panel(g,6,6,width-12,height-12,0xffbbaa80);panel(g,15,12,width-30,35,0xff252627);panel(g,15,55,sidebar()-7,cardsBottom()-55,0xff252627);
        int left=sidebar()+16,top=57,bottom=cardsBottom(),cell=78,cols=Math.max(1,(width-left-20)/cell),rows=Math.max(1,(bottom-top)/cell),start=scroll*cols;
        hoveredEntry=null;
        g.enableScissor(left,top,width-17,bottom);
        for(int i=start;i<Math.min(matches.size(),start+rows*cols+cols);i++) {
            var e=matches.get(i);int x=left+(i-start)%cols*cell,y=top+(i-start)/cols*cell;panel(g,x,y,cell-6,cell-6,e.entity().equals(selected)?0xff4a432b:0xff77776d);
            g.drawCenteredString(font,clip(e.name(),cell-12),x+(cell-6)/2,y+6,0xff171717);
            int previewTop=Math.max(top,y+18),previewBottom=Math.min(bottom,y+cell-10);
            if(popup.isEmpty() && previewTop<previewBottom){g.enableScissor(x+4,previewTop,x+cell-10,previewBottom);try{renderPreview(g,e,x+cell/2-3,y+cell-12,cell-24,mx,my);}finally{g.disableScissor();}}
            if(popup.isEmpty() && mx>=x && mx<x+cell-6 && my>=Math.max(top,y) && my<Math.min(bottom,y+cell-6))hoveredEntry=e;
        }
        g.disableScissor();
        if(matches.isEmpty())g.drawCenteredString(font,CreatureClient.entries.isEmpty()?"正在同步生物目录…":"没有匹配的生物",left+(width-left)/2,80,0xff28251a);
        renderProgress(g);
        super.render(g,mx,my,delta);if(!popup.isEmpty())renderPopup(g,mx,my,delta);
        // 必须在解除卡片裁剪并画完所有控件之后绘制，避免提示框被下张卡片盖住。
        if(popup.isEmpty() && hoveredEntry!=null)renderCreatureTooltip(g,mx,my);
    }
    private void renderProgress(GuiGraphics g){
        panel(g,15,height-87,width-30,46,0xff252627);int y=height-81;
        String mode=switch(CreatureClient.anchorMode){case "async"->"异步";case "hybrid","mixed"->"混合";case "sync","fallback"->"同步兼容";default->"";};
        g.drawString(font,clip("状态："+stage(CreatureClient.state)+(mode.isEmpty()?"":"（"+mode+"）"),width-46),23,y,0xffe8ddb3,false);
        String work=CreatureClient.anchorLimit>0?"群系采样："+CreatureClient.anchorSamples+" / "+CreatureClient.anchorLimit+"  "+stage(CreatureClient.anchorStage)
            :"已加载实体核对："+CreatureClient.captured+" / "+CreatureClient.captureTotal;
        if(CreatureClient.source.equals("saved"))work="存档区块："+CreatureClient.storedScanned+" / "+CreatureClient.storedTotal+"  区域："+CreatureClient.storedRegions+" / "+CreatureClient.storedRegionsTotal+"  核实："+CreatureClient.storedVerified;
        g.drawString(font,clip(work,width-46),23,y+10,0xffd9d5c5,false);
        g.drawString(font,clip("邻区轮次："+CreatureClient.round+" / "+CreatureClient.rounds+"  已检查区块："+CreatureClient.chunks+" / "+CreatureClient.chunksTotal+"  在途："+CreatureClient.inFlight+"  "+switch(CreatureClient.patchKind){case "small"->"小片选区";case "large"->"大片选区";case "ordinary"->"普通选区";default->"";},width-46),23,y+20,0xffd9d5c5,false);
        var p=CreatureClient.selected();String result=p==null?"搜索结果："+CreatureClient.points.size()+" 个实例":"实例 "+(CreatureClient.selection+1)+" / "+CreatureClient.points.size()+"  XYZ "+(int)p.x()+", "+(int)p.y()+", "+(int)p.z()+(p.live()?"":"（待复核）");
        g.drawString(font,clip(result,width-46),23,y+30,0xffa9d6ff,false);
    }
    private void renderCreatureTooltip(GuiGraphics g,int mx,int my){
        int w=Math.min(360,Math.max(100,width-24));
        if(tooltipEntry!=hoveredEntry){tooltipEntry=hoveredEntry;tipScroll=0;tooltipLines=tooltipLines(hoveredEntry,w-16);}
        int visible=Math.max(3,(height-38)/10);tipOverflow=Math.max(0,tooltipLines.size()-visible);tipScroll=Math.max(0,Math.min(tipScroll,tipOverflow));
        int count=Math.min(visible,tooltipLines.size()),h=count*10+12+(tipOverflow>0?12:0);
        int x=Math.max(8,Math.min(mx+12,width-w-8)),y=Math.max(8,Math.min(my+12,height-h-8));
        g.pose().pushPose();g.pose().translate(0,0,400);panel(g,x,y,w,h,0xf0181822);
        for(int i=0;i<count;i++)g.drawString(font,tooltipLines.get(tipScroll+i),x+8,y+7+i*10,0xffe8ddb3,false);
        if(tipOverflow>0)g.drawString(font,"滚轮查看更多（"+(tipScroll+1)+"/"+(tipOverflow+1)+"）",x+8,y+h-12,0xffa9d6ff,false);
        g.pose().popPose();
    }
    private List<FormattedCharSequence> tooltipLines(CreatureEntry entry,int width){
        List<Component> parts=new ArrayList<>();parts.add(Component.literal(entry.name()).withStyle(ChatFormatting.GOLD));
        if(entry.paired() && !entry.appearances().isEmpty())for(var appearance:entry.appearances()){
            String dimension=appearance.dimension()==null?"维度待核实":DimensionNames.name(appearance.dimension());
            String biomes=appearance.biomes().isEmpty()?"群系待核实":String.join("、",appearance.biomes().stream().map(com.david.compass.runtime.game.client.BiomeNames::name).toList());
            String verified=switch(appearance.verification()){case "verified"->"";case "partial"->"（部分核实）";default->"（待核实）";};
            parts.add(Component.literal("生成于 "+dimension+"["+biomes+"]"+verified));
            parts.add(Component.literal(appearance.description().isBlank()?"生成／召唤方式待补充":appearance.description()));
        }else{
            // 旧目录是两个独立集合，不能将其笛卡尔积当成已核实的逐维度生成规则。
            String dimensions=entry.dimensions().isEmpty()?"待核实":String.join("、",entry.dimensions().stream().map(DimensionNames::name).toList());
            parts.add(Component.literal("已知维度："+dimensions+"（群系对应待核实）"));
            if(!entry.biomes().isEmpty())parts.add(Component.literal("已知群系："+String.join("、",entry.biomes().stream().map(com.david.compass.runtime.game.client.BiomeNames::name).toList())));
        }
        if(!entry.note().isBlank())parts.add(Component.literal("备注："+entry.note()).withStyle(ChatFormatting.GRAY));
        parts.add(Component.literal(entry.entity().toString()).withStyle(ChatFormatting.DARK_GRAY));
        List<FormattedCharSequence> lines=new ArrayList<>();for(var part:parts)lines.addAll(font.split(part,width));return List.copyOf(lines);
    }
    private void renderPreview(GuiGraphics g,CreatureEntry e,int x,int y,int max,int mx,int my){
        if(previewLevel!=minecraft.level){clearPreviews();previewLevel=minecraft.level;}
        try {
            Entity entity=previews.computeIfAbsent(e.entity(),id->{var t=ForgeRegistries.ENTITY_TYPES.getValue(id);return t==null?null:t.create(minecraft.level);});
            if(entity instanceof LivingEntity living){int scale=(int)Math.max(1,Math.min(23,max/Math.max(living.getBbHeight(),living.getBbWidth()*1.3)));InventoryScreen.renderEntityInInventoryFollowsMouse(g,x,y,scale,x-mx,y-my,living);}
            else g.drawCenteredString(font,"生",x,y-25,0xffe8ddb3);
        }catch(Exception error){g.drawCenteredString(font,"生",x,y-25,0xffe8ddb3);}
    }
    private static String stage(String value){return switch(value){case ""->"";case "ranking_chunks"->"分析适生区域大小与连通性";case "checking_habitat"->"检查真实生境和安全观察点";case "habitat_ready"->"未发现现有生物；准备自动前往生境";case "waiting_natural_spawn"->"等待自然刷新（"+CreatureClient.naturalWaitMillis/1000+" / 60秒；分类数量 "+CreatureClient.spawnPopulation+"/"+CreatureClient.spawnCapacity+"）";case "natural_spawn_capped"->"分类生物额度已满，检查其他生境";case "natural_spawn_not_found"->"本轮未自然刷新，继续检查其他候选";case "verifying_loaded"->"核对加载边界的生物";case "initial"->"查询已加载生物";case "scanning_saved"->"异步检索存档实体";case "verifying_saved"->"加载并核实存档候选";case "searching","predicting","sampling"->"正在采样";case "confirming"->"核对目标群系";case "locating_biome"->"定位目标群系";case "loading_chunks"->"等待地形及实体加载";case "found"->"已找到";case "not_found","range_exhausted","biome_range_exhausted"->"本次范围未发现";case "sample_limit","biome_sample_limit"->"达到采样额度";case "idle"->"请选择生物";case "cancelled"->"已停止";case "reload_entity"->"重新核对实体";case "entity_gone"->"实体已离开或不存在";case "teleport_moved"->"传送成功";case "teleport_confirm"->"再次点击可降级传送";case "teleport_waiting_chunks"->"准备落点：等待区块";case "teleport_waiting_slots"->"准备落点：等待调度";case "biome_rules_unavailable"->"当前版本未能取得地物生成规则";case "biome_stalled"->"群系定位无进展超时";case "chunk_timeout","entity_load_timeout"->"区块加载超时";default->value.startsWith("teleport_")?"准备落点："+value.substring(9):value;};}
    private void openPicker(String kind){closePicker();popup=kind;popupScroll=0;int x=sidebar()+18;
        if(kind.equals("rounds")){number=new EditBox(font,x+12,91,180,20,Component.literal("兜底次数0～32"));number.setValue(Integer.toString(CreatureConfig.CLIENT.creatureRounds.get()));number.setMaxLength(2);number.setCanLoseFocus(false);addWidget(number);setFocused(number);return;}
        popupInput=new EditBox(font,x+12,70,190,20,Component.literal("搜索"));popupInput.setResponder(s->{popupScroll=0;pickerMatches();});popupInput.setCanLoseFocus(false);addWidget(popupInput);setFocused(popupInput);pickerMatches();
    }
    private void pickerMatches(){var options=popup.equals("dimension")?CreatureWorldCatalog.dimensions():CreatureWorldCatalog.biomesIn(dimension);popupMatches=options.stream().filter(id->SearchText.of(name(id,popup.equals("dimension")),id.toString()).matches(popupInput.getValue())).sorted(Comparator.<ResourceLocation,Boolean>comparing(id->popup.equals("dimension")&&!id.equals(CreatureWorldCatalog.currentDimension())).thenComparing(id->name(id,popup.equals("dimension")))).toList();}
    private int popupVisible(){int h=Math.min(260,height-75);return Math.max(0,(58+h-128)/20);}
    private void renderPopup(GuiGraphics g,int mx,int my,float d){
        int x=sidebar()+18,w=Math.min(240,width-x-12),h=Math.min(260,height-75);
        g.flush();g.pose().pushPose();g.pose().translate(0,0,400);
        try{
            g.fill(0,0,width,height,0x99000000);panel(g,x,58,w,h,0xff151719);
            if(popup.equals("rounds")){number.setWidth(w-24);g.drawString(font,clip("邻区兜底轮数（0～32）",w-24),x+12,73,0xffe8ddb3);number.render(g,mx,my,d);g.drawString(font,clip("Enter 保存；Esc 取消",w-24),x+12,122,0xffc8ba92);return;}
            popupInput.setWidth(w-24);popupInput.render(g,mx,my,d);g.drawString(font,"默认",x+12,100,0xffe8ddb3);
            g.enableScissor(x+8,120,x+w-8,58+h-8);
            try{for(int i=popupScroll;i<Math.min(popupMatches.size(),popupScroll+popupVisible());i++){
                var id=popupMatches.get(i);g.drawString(font,clip(name(id,popup.equals("dimension"))+(popup.equals("dimension")&&id.equals(CreatureWorldCatalog.currentDimension())?"（当前）":""),w-25),x+12,121+(i-popupScroll)*20,0xffe8ddb3);
            }}finally{g.disableScissor();}
        }finally{g.flush();g.pose().popPose();}
    }
    private void closePicker(){if(popupInput!=null)removeWidget(popupInput);if(number!=null)removeWidget(number);popupInput=null;number=null;popup="";if(input!=null)setFocused(input);}
    @Override public boolean mouseClicked(double x,double y,int b){
        if(!popup.isEmpty()){int px=sidebar()+18,w=Math.min(240,width-px-12),h=Math.min(260,height-75);if(x<px || x>px+w || y<58 || y>58+h){closePicker();return true;}if(b!=0)return true;if(popup.equals("rounds")){number.mouseClicked(x,y,b);return true;}if(popupInput.mouseClicked(x,y,b))return true;
            ResourceLocation id=null;int row=(int)((y-120)/20),at=popupScroll+row;
            if(y>=120 && row<popupVisible() && at>=0 && at<popupMatches.size())id=popupMatches.get(at);else if(!(y>=96 && y<116))return true;
            if(popup.equals("dimension")){dimension=id;CreatureConfig.CLIENT.creatureDimension.set(id==null?"":id.toString());CreatureConfig.CLIENT.creatureDimension.save();if(biome!=null && !CreatureWorldCatalog.biomesIn(dimension).contains(biome))biome=null;}
            else {biome=id;}CreatureConfig.CLIENT.creatureBiome.set(biome==null?"":biome.toString());CreatureConfig.CLIENT.creatureBiome.save();closePicker();scroll=0;refresh();return true;
        }
        if(super.mouseClicked(x,y,b))return true;int left=sidebar()+16,cell=78,cols=Math.max(1,(width-left-20)/cell);if(x>=left && x<width-17 && y>=57 && y<cardsBottom()){int index=scroll*cols+(int)((y-57)/cell)*cols+(int)((x-left)/cell);if(index<matches.size()){selected=matches.get(index).entity();refresh();return true;}}return false;
    }
    @Override public boolean mouseScrolled(double x,double y,double delta){
        if(!popup.isEmpty()){popupScroll=Math.max(0,Math.min(Math.max(0,popupMatches.size()-popupVisible()),popupScroll-(int)Math.signum(delta)));return true;}
        if(hoveredEntry!=null && tipOverflow>0){tipScroll=Math.max(0,Math.min(tipOverflow,tipScroll-(int)Math.signum(delta)));return true;}
        if(x<sidebar()+10 && y>=55 && y<cardsBottom()){sideScroll+=-(int)Math.signum(delta)*15;updateSideButtons();return true;}
        int cols=Math.max(1,(width-sidebar()-36)/78);scroll=Math.max(0,Math.min(Math.max(0,(matches.size()-1)/cols),scroll-(int)Math.signum(delta)));return true;
    }
    @Override public boolean keyPressed(int key,int scan,int mods){if(!popup.isEmpty()){if(key==256){closePicker();return true;}if(number!=null){if(key==257){try{int n=Integer.parseInt(number.getValue());if(n>=0&&n<=32){CreatureConfig.CLIENT.creatureRounds.set(n);CreatureConfig.CLIENT.creatureRounds.save();closePicker();}}catch(NumberFormatException ignored){}return true;}number.keyPressed(key,scan,mods);return true;}popupInput.keyPressed(key,scan,mods);return true;}return super.keyPressed(key,scan,mods);}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(!popup.isEmpty()){if(number!=null)number.mouseDragged(x,y,button,dx,dy);else if(popupInput!=null)popupInput.mouseDragged(x,y,button,dx,dy);return true;}return super.mouseDragged(x,y,button,dx,dy);}
    @Override public boolean mouseReleased(double x,double y,int button){if(!popup.isEmpty())return true;return super.mouseReleased(x,y,button);}
    @Override public boolean charTyped(char c,int mods){if(number!=null){number.charTyped(c,mods);return true;}if(popupInput!=null){popupInput.charTyped(c,mods);return true;}return super.charTyped(c,mods);}
}
