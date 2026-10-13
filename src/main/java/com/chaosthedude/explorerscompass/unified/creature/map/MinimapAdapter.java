package com.chaosthedude.explorerscompass.unified.creature.map;
import com.chaosthedude.explorerscompass.unified.creature.client.CreatureClient;
import com.chaosthedude.explorerscompass.unified.creature.search.MobPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import java.util.*;
import xaero.hud.minimap.element.render.*;
import xaero.common.HudMod;
import xaero.common.XaeroMinimapSession;
/** 使用公开over-map处理器，坐标旋转和边界计算交由Xaero；不夹到地图边缘。 */
public final class MinimapAdapter extends MinimapElementRenderer<MobPoint,Object> {
    private static MinimapElementRenderInfo info;private GuiGraphics graphics;
    public static long rendered;
    public MinimapAdapter(){super(new Reader(),new Provider(),new Object());}
    public static void install(){HudMod.INSTANCE.getMinimap().getOverMapRendererHandler().add(new MinimapAdapter());}
    public int getOrder(){return 1000;}
    public boolean shouldRender(MinimapElementRenderLocation location){return location==MinimapElementRenderLocation.OVER_MINIMAP && CreatureClient.dots();}
    private static boolean visible(MobPoint p){
        if(!CreatureClient.dots() || !p.live() || info==null || !info.mapDimension.location().equals(p.dimension()))return false;
        if(!info.cave)return true;var session=XaeroMinimapSession.getCurrentSession();if(session==null)return false;
        int start=session.getMinimapProcessor().getMinimapWriter().getLoadedCaving();
        return start==Integer.MAX_VALUE || start==Integer.MIN_VALUE || p.y()<=start && p.y()>start-HudMod.INSTANCE.getSettings().getCaveMapsDepth();
    }
    public void preRender(MinimapElementRenderInfo i,MultiBufferSource.BufferSource b,xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider p){info=i;graphics=null;}
    public void postRender(MinimapElementRenderInfo i,MultiBufferSource.BufferSource b,xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider p){if(graphics!=null)graphics.flush();}
    public boolean renderElement(MobPoint point,boolean hovered,boolean outside,double optional,float scale,double px,double pz,MinimapElementRenderInfo i,GuiGraphics g,MultiBufferSource.BufferSource b){
        if(outside || !visible(point))return false;graphics=g;g.pose().pushPose();g.pose().translate(px,pz,200);CreatureMaps.draw(g);g.pose().popPose();rendered++;return true;
    }
    private static final class Provider extends MinimapElementRenderProvider<MobPoint,Object> {
        private Iterator<MobPoint> iterator=Collections.emptyIterator();
        public void begin(MinimapElementRenderLocation l,Object c){iterator=CreatureClient.points.iterator();}
        public boolean hasNext(MinimapElementRenderLocation l,Object c){return iterator.hasNext();}
        public MobPoint getNext(MinimapElementRenderLocation l,Object c){return iterator.next();}
        public void end(MinimapElementRenderLocation l,Object c){iterator=Collections.emptyIterator();}
    }
    private static final class Reader extends MinimapElementReader<MobPoint,Object> {

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
        public double getRenderY(MobPoint p,Object c,float partial){return p.y();}
        public double getCoordinateScale(MobPoint p,Object c,MinimapElementRenderInfo i){return i.backgroundCoordinateScale;}
    }
}
