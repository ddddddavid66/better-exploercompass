package com.chaosthedude.explorerscompass.unified.creature.map;
import com.chaosthedude.explorerscompass.unified.creature.client.CreatureClient;
import com.chaosthedude.explorerscompass.unified.creature.search.MobPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import java.util.*;
import xaero.map.element.*;
import xaero.map.WorldMap;
import xaero.map.gui.*;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
/** 公开地图扩展层；不修改原生雷达、路径点及洞穴模式。 */
public final class WorldMapAdapter extends MapElementRenderer<MobPoint,Object,WorldMapAdapter> {
    public static long rendered;
    public WorldMapAdapter(){super(new Object(),new Provider(),new Reader());}
    public static void install(){WorldMap.mapElementRenderHandler.add(new WorldMapAdapter());}
    public static boolean visible(MobPoint p){
        if(!CreatureClient.dots() || !p.live() || !(Minecraft.getInstance().screen instanceof GuiMap gui))return false;
        var processor=gui.getMapProcessor();var dim=processor.getMapWorld().getCurrentDimension();
        if(dim==null || !dim.getDimId().location().equals(p.dimension()))return false;
        int layer=processor.getCurrentCaveLayer();
        if(layer==Integer.MAX_VALUE || layer==Integer.MIN_VALUE)return true;
        var current=dim.getLayeredMapRegions().getLayer(layer);if(current==null)return false;
        int start=current.getCaveStart();return start==Integer.MAX_VALUE || start==Integer.MIN_VALUE || p.y()<=start && p.y()>start-WorldMap.settings.caveModeDepth;
    }
    public static void button(net.minecraft.client.gui.screens.Screen screen){
        if(!(screen instanceof GuiMap gui))return;
        var buttons=screen.children().stream().filter(w->w instanceof AbstractWidget).map(w->(AbstractWidget)w)
            .filter(w->w.visible && w.getWidth()==20 && w.getHeight()==20 && w.getX()>screen.width-55).toList();
        int x=buttons.stream().mapToInt(AbstractWidget::getX).max().orElse(screen.width-25);
        final int rightX=x;
        int y=buttons.stream().filter(w->w.getX()==rightX).mapToInt(AbstractWidget::getY).min().orElse(screen.height-160)-22;
        if(y<10){x-=22;y=10;}
        gui.addButton(new TooltipButton(x,y,20,20,Component.literal("生"),b->{CreatureClient.toggleDots();},()->new CursorBox("显示/隐藏生物定位蓝点（大小地图同步）")));
    }
    public int getOrder(){return 1000;}
    public boolean shouldBeDimScaled(){return false;}
    public boolean shouldRender(int location,boolean hovered){return location==MapElementRenderLocation.WORLD_MAP && CreatureClient.dots();}
    public void beforeRender(int location,Minecraft mc,GuiGraphics g,double a,double b,double c,double d,float partial,double e,double f,net.minecraft.client.renderer.texture.TextureManager textures,Font font,MultiBufferSource.BufferSource buffer,xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider provider,boolean shadow){}
    public void afterRender(int location,Minecraft mc,GuiGraphics g,double a,double b,double c,double d,float partial,double e,double f,net.minecraft.client.renderer.texture.TextureManager textures,Font font,MultiBufferSource.BufferSource buffer,xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider provider,boolean shadow){g.flush();}
    public void renderElementPre(int location,MobPoint point,boolean hovered,Minecraft mc,GuiGraphics g,double a,double b,double c,double d,float partial,double e,double f,net.minecraft.client.renderer.texture.TextureManager textures,Font font,MultiBufferSource.BufferSource buffer,xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider provider,float scale,double px,double pz,boolean shadow,float extra){}
    public boolean renderElement(int location,MobPoint point,boolean hovered,Minecraft mc,GuiGraphics g,double a,double b,double c,double d,float partial,double e,double f,net.minecraft.client.renderer.texture.TextureManager textures,Font font,MultiBufferSource.BufferSource buffer,xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider provider,int index,double optional,float scale,double px,double pz,boolean shadow,float extra){if(!visible(point))return false;g.pose().pushPose();g.pose().translate(px,pz,200);CreatureMaps.draw(g);g.pose().popPose();rendered++;return true;}
    private static final class Provider extends MapElementRenderProvider<MobPoint,Object> {
        private Iterator<MobPoint> iterator=Collections.emptyIterator();
        public void begin(int location,Object c){iterator=CreatureClient.points.iterator();}
        public boolean hasNext(int location,Object c){return iterator.hasNext();}
        public MobPoint getNext(int location,Object c){return iterator.next();}
        public void end(int location,Object c){iterator=Collections.emptyIterator();}
    }
    private static final class Reader extends MapElementReader<MobPoint,Object,WorldMapAdapter> {

        public boolean isHidden(MobPoint p,Object c){return !visible(p);}
        public double getRenderX(MobPoint p,Object c,float partial){return p.x();}
        public double getRenderZ(MobPoint p,Object c,float partial){return p.z();}
        public int getLeftSideLength(MobPoint p,Minecraft mc){return 4;}
        public String getMenuName(MobPoint p){return CreatureMaps.label(p);}
        public String getFilterName(MobPoint p){return getMenuName(p);}
        public int getMenuTextFillLeftPadding(MobPoint p){return 0;}
        public int getRightClickTitleBackgroundColor(MobPoint p){return 0xff125bbc;}
        public boolean shouldScaleBoxWithOptionalScale(){return false;}
        public int getInteractionBoxLeft(MobPoint p,Object c,float partial){return -4;}
        public int getInteractionBoxRight(MobPoint p,Object c,float partial){return 4;}
        public int getInteractionBoxTop(MobPoint p,Object c,float partial){return -4;}
        public int getInteractionBoxBottom(MobPoint p,Object c,float partial){return 4;}
        public int getRenderBoxLeft(MobPoint p,Object c,float partial){return -4;}
        public int getRenderBoxRight(MobPoint p,Object c,float partial){return 4;}
        public int getRenderBoxTop(MobPoint p,Object c,float partial){return -4;}
        public int getRenderBoxBottom(MobPoint p,Object c,float partial){return 4;}

        public CursorBox getTooltip(MobPoint p,Object c,boolean hovered){return new CursorBox(CreatureMaps.label(p)+"\nXYZ "+(int)p.x()+", "+(int)p.y()+", "+(int)p.z());}
        public boolean isRightClickValid(MobPoint p){return false;}
    }
}
