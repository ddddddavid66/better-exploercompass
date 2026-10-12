package com.chaosthedude.explorerscompass.unified.entertainment;

import com.chaosthedude.explorerscompass.gui.TransparentButton;
import com.chaosthedude.explorerscompass.unified.client.PopupLayer;
import com.chaosthedude.explorerscompass.unified.data.SearchText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import com.chaosthedude.explorerscompass.ExplorersCompass;

/** 选择与执行分离；新增娱乐项目可以继续在此列表添加独立功能。 */
public final class EntertainmentUi implements PopupLayer {
    private final Screen screen;
    private final Button button;
    private final Runnable onOpen;
    private EditBox search;
    private Button confirm, cancel;
    private int left, top, width, height;
    private boolean open;
    private EntertainmentFeature selected;
    private List<EntertainmentFeature> matches=List.of(EntertainmentFeature.values());
    public EntertainmentUi(Screen screen, Runnable onOpen) {
        this.screen=screen;this.onOpen=onOpen;
        button=new TransparentButton(10,250,110,20,Component.translatable("entertainment.title"),ignored->toggle());
        button.setTooltip(Tooltip.create(Component.translatable("entertainment.description")));
    }
    public Button button(){return button;}
    public boolean isOpen(){return open;}
    public void close(){open=false;}
    private void toggle() {
        if(open){close();return;}
        onOpen.run();selected=null;matches=List.of(EntertainmentFeature.values());open=true;
        width=Math.min(260,screen.width-10);height=Math.min(230,screen.height-10);
        left=Math.max(5,Math.min(button.getX()+button.getWidth()+5,screen.width-width-5));
        top=Math.max(5,Math.min(button.getY(),screen.height-height-5));
        var font=Minecraft.getInstance().font;
        search=new EditBox(font,left+7,top+7,width-14,18,Component.translatable("entertainment.search"));
        search.setHint(Component.translatable("entertainment.search"));search.setMaxLength(128);search.setFocused(true);
        search.setResponder(query->{
            matches=Arrays.stream(EntertainmentFeature.values()).filter(feature->SearchText.of(
                    Component.translatable(feature.key()).getString(),feature.key()).matches(query)).toList();
            if(!matches.contains(selected))selected=null;update();
        });
        int half=(width-18)/2;
        confirm=new TransparentButton(left+6,top+height-26,half,20,Component.translatable("gui.done"),ignored->execute());
        cancel=new TransparentButton(left+12+half,top+height-26,half,20,Component.translatable("gui.cancel"),ignored->close());
        EntertainmentClient.request(EntertainmentRequest.Action.STATUS);update();
    }
    private void execute() {
        if(!confirm.active)return;
        if(EntertainmentClient.active())EntertainmentClient.request(EntertainmentRequest.Action.STOP);
        else if(selected!=null)EntertainmentClient.request(selected==EntertainmentFeature.ADVENTURING_TIME
                ?EntertainmentRequest.Action.ADVENTURING_TIME:EntertainmentRequest.Action.AETHER_BIOMES);
        selected=null;update();
    }
    private void update() {
        if(confirm==null)return;
        var state=EntertainmentClient.state();
        boolean returning=state!=null && (state.phase().equals("return") || state.phase().equals("returning"));
        confirm.active=EntertainmentClient.active()?!returning:selected!=null && matches.contains(selected) && available(selected);
        confirm.setTooltip(!EntertainmentClient.active() && selected!=null && !available(selected)
                ?Tooltip.create(Component.translatable(unavailable(selected))):null);
        confirm.setMessage(Component.translatable(EntertainmentClient.active()?"entertainment.stop_return":"entertainment.confirm"));
    }
    private static boolean available(EntertainmentFeature feature) {
        var mc=Minecraft.getInstance();
        return mc.level!=null && mc.player!=null && mc.player.isAlive() && ExplorersCompass.canTeleport
                && mc.level.dimension().location().equals(feature.dimension())
                && (mc.player.getMainHandItem().getItem()==ExplorersCompass.explorersCompass
                    || mc.player.getOffhandItem().getItem()==ExplorersCompass.explorersCompass);
    }
    private static String unavailable(EntertainmentFeature feature) {
        var level=Minecraft.getInstance().level;
        return level==null || !level.dimension().location().equals(feature.dimension())?feature.unavailableKey():"entertainment.unavailable";
    }
    public void tick(){if(open){search.tick();update();}}
    public void render(GuiGraphics graphics,int mouseX,int mouseY,float delta) {
        if(!open)return;
        update();var font=Minecraft.getInstance().font;
        graphics.pose().pushPose();graphics.pose().translate(0,0,400);
        graphics.fill(left,top,left+width,top+height,0xEF101010);
        graphics.renderOutline(left,top,width,height,0xFF999999);search.render(graphics,mouseX,mouseY,delta);
        for(int i=0;i<matches.size();i++) {
            var feature=matches.get(i);int y=top+32+i*24;
            if(selected==feature || inside(mouseX,mouseY,left+4,y,width-8,24))
                graphics.fill(left+4,y,left+width-4,y+24,0x60404040);
            graphics.drawString(font,Component.translatable(feature.key()),left+9,y+8,
                    selected==feature?0xFFFFAA:available(feature)?0xFFFFFF:0xAAAAAA);
        }
        if(matches.isEmpty())graphics.drawString(font,Component.translatable("entertainment.no_matches"),left+9,top+40,0xAAAAAA);
        int summaryTop=top+37+Math.max(1,matches.size())*24;
        graphics.enableScissor(left+5,summaryTop,left+width-5,Math.max(summaryTop,top+height-31));
        int y=summaryTop+4;
        var summary=!EntertainmentClient.active() && selected!=null
                ?Component.translatable(available(selected)?selected.key()+"_description":unavailable(selected)):EntertainmentClient.summary();
        for(var line:font.split(summary,width-18)) {graphics.drawString(font,line,left+9,y,0xAAAAAA);y+=11;}
        graphics.disableScissor();confirm.render(graphics,mouseX,mouseY,delta);cancel.render(graphics,mouseX,mouseY,delta);
        graphics.pose().popPose();
    }
    public boolean mouseClicked(double x,double y,int mouseButton) {
        if(!open)return false;
        if(!inside(x,y,left,top,width,height)){close();return true;}
        if(mouseButton!=0)return true;
        if(confirm.mouseClicked(x,y,mouseButton) || cancel.mouseClicked(x,y,mouseButton))return true;
        if(!EntertainmentClient.active())for(int i=0;i<matches.size();i++)
            if(inside(x,y,left+4,top+32+i*24,width-8,24)) {selected=matches.get(i);update();return true;}
        search.mouseClicked(x,y,mouseButton);return true;
    }
    public boolean mouseScrolled(double delta){return open;}
    public boolean mouseDragged(double y){return open;}
    public boolean mouseReleased(){return open;}
    public boolean keyPressed(int key,int scan,int modifiers) {
        if(!open)return false;
        if(key==GLFW.GLFW_KEY_ESCAPE){close();return true;}
        if(key==GLFW.GLFW_KEY_ENTER || key==GLFW.GLFW_KEY_KP_ENTER){execute();return true;}
        if((key==GLFW.GLFW_KEY_DOWN || key==GLFW.GLFW_KEY_UP) && !matches.isEmpty() && !EntertainmentClient.active()) {
            int index=matches.indexOf(selected);
            selected=matches.get(Math.floorMod(index+(key==GLFW.GLFW_KEY_DOWN?1:-1),matches.size()));update();return true;
        }
        search.keyPressed(key,scan,modifiers);return true;
    }
    public boolean charTyped(char character,int modifiers){if(!open)return false;search.charTyped(character,modifiers);return true;}
    private static boolean inside(double x,double y,int left,int top,int width,int height) {
        return x>=left && y>=top && x<left+width && y<top+height;
    }
}
