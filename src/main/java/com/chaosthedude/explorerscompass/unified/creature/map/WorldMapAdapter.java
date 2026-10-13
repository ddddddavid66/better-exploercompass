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
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import com.chaosthedude.explorerscompass.ExplorersCompass;
import java.lang.reflect.Field;
/** 公开地图扩展层；不修改原生雷达、路径点及洞穴模式。 */
public final class WorldMapAdapter extends MapElementRenderer<MobPoint,Object,WorldMapAdapter> {
    public static long rendered;
    private static Field cameraDestination;
    private static boolean cameraResolved,cameraFailed;
    private static GuiMap pendingMap;
    private static MobPoint pendingPoint;
    private static UUID pendingQuery;
    private static long centerDeadline;
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
        int buttonWidth=Math.min(Math.max(100,Minecraft.getInstance().font.width(actionLabel())+12),Math.max(60,screen.width-20));
        if(y<10){y=10;x=Math.max(10,x-22-buttonWidth);}
        else x=Math.max(10,x+20-buttonWidth);
        gui.addButton(new TooltipButton(x,y,buttonWidth,20,Component.literal(actionLabel()),b->{
            CreatureClient.toggleDots();b.setMessage(Component.literal(actionLabel()));centerLatest(gui);
        },()->new CursorBox(actionLabel()+"\n大小地图同步；跳转到最近一次搜索的最近生物")));
    }
    private static String actionLabel(){return CreatureClient.dots()?"隐藏生物定位蓝点":"显示生物定位蓝点";}
    private static void centerLatest(GuiMap gui){
        var mc=Minecraft.getInstance();if(mc.player==null || CreatureClient.query==null)return;
        var point=CreatureClient.points.stream().filter(MobPoint::live)
            .min(Comparator.<MobPoint>comparingDouble(p->p.distance(mc.player.getX(),mc.player.getZ())).thenComparing(p->p.uuid().toString())).orElse(null);
        if(point==null){mc.player.displayClientMessage(Component.literal("当前没有可居中的生物定位结果。"),true);return;}
        if(!resolveCamera())return;
        pendingMap=gui;pendingPoint=point;pendingQuery=CreatureClient.query;centerDeadline=System.nanoTime()+5_000_000_000L;
        var processor=gui.getMapProcessor();var world=processor.getMapWorld();
        if(world.getCurrentDimensionId()==null || !world.getCurrentDimensionId().location().equals(point.dimension())){
            // 只切换地图查看的维度，不移动玩家；沿用 Xaero 自己的维度切换入口。
            world.setCustomDimensionId(ResourceKey.create(Registries.DIMENSION,point.dimension()));processor.checkForWorldUpdate();
        }
    }
    private static boolean resolveCamera(){
        if(cameraResolved)return !cameraFailed;cameraResolved=true;
        try {
            var version=net.minecraftforge.fml.ModList.get().getModContainerById("xaeroworldmap").orElseThrow().getModInfo().getVersion().toString();
            if(!version.equals("1.39.2"))throw new IllegalStateException("未审核的地图版本："+version);
            // 1.39.2 没有公开居中 setter。仅写原生动画目的地，不改缩放、洞穴层或雷达状态。
            cameraDestination=GuiMap.class.getDeclaredField("cameraDestination");
            if(cameraDestination.getType()!=int[].class || !cameraDestination.trySetAccessible())throw new IllegalStateException("地图目的地字段不可访问");
            return true;
        }catch(ReflectiveOperationException|RuntimeException error){cameraFailure(error);return false;}
    }
    public static void afterScreenRender(net.minecraft.client.gui.screens.Screen screen){
        if(pendingMap==null)return;
        if(screen!=pendingMap || !Objects.equals(pendingQuery,CreatureClient.query) || Minecraft.getInstance().screen!=pendingMap
                || CreatureClient.points.stream().noneMatch(p->p.live() && p.uuid().equals(pendingPoint.uuid()))){clearCenter();return;}
        if(System.nanoTime()>centerDeadline){clearCenter();if(Minecraft.getInstance().player!=null)Minecraft.getInstance().player.displayClientMessage(Component.literal("地图维度尚未准备好，可再次点击生物蓝点按钮。"),true);return;}
        var dimension=pendingMap.getMapProcessor().getMapWorld().getCurrentDimension();
        if(dimension==null || !dimension.getDimId().location().equals(pendingPoint.dimension()))return;
        try {
            // 在当前帧地图绘制后设置，避免同帧维度切换清空目的地；下一帧交给原生平滑动画。
            cameraDestination.set(pendingMap,new int[]{net.minecraft.util.Mth.floor(pendingPoint.x()),net.minecraft.util.Mth.floor(pendingPoint.z())});
            clearCenter();
        }catch(ReflectiveOperationException|RuntimeException error){clearCenter();cameraFailure(error);}
    }
    private static void clearCenter(){pendingMap=null;pendingPoint=null;pendingQuery=null;}
    private static void cameraFailure(Exception error){
        if(cameraFailed)return;cameraFailed=true;ExplorersCompass.LOGGER.warn("生物地图自动居中不可用；蓝点和搜索仍可使用",error);
        var player=Minecraft.getInstance().player;if(player!=null)player.displayClientMessage(Component.literal("地图自动居中接口不可用，生物蓝点仍可显示，详情见日志。"),false);
    }
    public int getOrder(){return 1000;}
    public boolean shouldBeDimScaled(){return false;}
    public boolean shouldRender(int location,boolean hovered){return location==MapElementRenderLocation.WORLD_MAP && CreatureClient.dots();}
    public void beforeRender(int location,Minecraft mc,GuiGraphics g,double a,double b,double c,double d,float partial,double e,double f,net.minecraft.client.renderer.texture.TextureManager textures,Font font,MultiBufferSource.BufferSource buffer,xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider provider,boolean shadow){}
    public void afterRender(int location,Minecraft mc,GuiGraphics g,double a,double b,double c,double d,float partial,double e,double f,net.minecraft.client.renderer.texture.TextureManager textures,Font font,MultiBufferSource.BufferSource buffer,xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider provider,boolean shadow){g.flush();}
    public void renderElementPre(int location,MobPoint point,boolean hovered,Minecraft mc,GuiGraphics g,double a,double b,double c,double d,float partial,double e,double f,net.minecraft.client.renderer.texture.TextureManager textures,Font font,MultiBufferSource.BufferSource buffer,xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider provider,float scale,double px,double pz,boolean shadow,float extra){}
    public boolean renderElement(int location,MobPoint point,boolean hovered,Minecraft mc,GuiGraphics g,double a,double b,double c,double d,float partial,double e,double f,net.minecraft.client.renderer.texture.TextureManager textures,Font font,MultiBufferSource.BufferSource buffer,xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider provider,int index,double optional,float scale,double px,double pz,boolean shadow,float extra){if(!visible(point))return false;g.pose().pushPose();g.pose().translate(px,pz,200);CreatureMaps.drawWorld(g);g.pose().popPose();rendered++;return true;}
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
        public int getLeftSideLength(MobPoint p,Minecraft mc){return 13;}
        public String getMenuName(MobPoint p){return CreatureMaps.label(p);}
        public String getFilterName(MobPoint p){return getMenuName(p);}
        public int getMenuTextFillLeftPadding(MobPoint p){return 0;}
        public int getRightClickTitleBackgroundColor(MobPoint p){return 0xff125bbc;}
        public boolean shouldScaleBoxWithOptionalScale(){return false;}
        public int getInteractionBoxLeft(MobPoint p,Object c,float partial){return -4;}
        public int getInteractionBoxRight(MobPoint p,Object c,float partial){return 4;}
        public int getInteractionBoxTop(MobPoint p,Object c,float partial){return -4;}
        public int getInteractionBoxBottom(MobPoint p,Object c,float partial){return 4;}
        public int getRenderBoxLeft(MobPoint p,Object c,float partial){return -13;}
        public int getRenderBoxRight(MobPoint p,Object c,float partial){return 13;}
        public int getRenderBoxTop(MobPoint p,Object c,float partial){return -13;}
        public int getRenderBoxBottom(MobPoint p,Object c,float partial){return 13;}

        public CursorBox getTooltip(MobPoint p,Object c,boolean hovered){return new CursorBox(CreatureMaps.label(p)+"\nXYZ "+(int)p.x()+", "+(int)p.y()+", "+(int)p.z());}
        public boolean isRightClickValid(MobPoint p){return false;}
    }
}
