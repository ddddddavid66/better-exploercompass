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

/** 选择与执行分离；新增娱乐项目可以继续在此列表添加独立功能。 */
public final class EntertainmentUi implements PopupLayer {
    private final Screen screen;
    private final Button button;
    private final Runnable onOpen;
    private EditBox search;
    private Button confirm, cancel;
    private int left, top, width, height;
    private boolean open, selected, matches;
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
        onOpen.run();selected=false;matches=true;open=true;
        width=Math.min(260,screen.width-10);height=Math.min(230,screen.height-10);
        left=Math.max(5,Math.min(button.getX()+button.getWidth()+5,screen.width-width-5));
        top=Math.max(5,Math.min(button.getY(),screen.height-height-5));
        var font=Minecraft.getInstance().font;
        search=new EditBox(font,left+7,top+7,width-14,18,Component.translatable("entertainment.search"));
        search.setHint(Component.translatable("entertainment.search"));search.setMaxLength(128);search.setFocused(true);
        search.setResponder(query->{matches=SearchText.of(Component.translatable("entertainment.adventuring_time").getString(),
                "minecraft:adventure/adventuring_time").matches(query);if(!matches)selected=false;update();});
        int half=(width-18)/2;
        confirm=new TransparentButton(left+6,top+height-26,half,20,Component.translatable("gui.done"),ignored->execute());
        cancel=new TransparentButton(left+12+half,top+height-26,half,20,Component.translatable("gui.cancel"),ignored->close());
        EntertainmentClient.request(EntertainmentRequest.Action.STATUS);update();
    }
    private void execute() {
        if(!confirm.active)return;
        if(EntertainmentClient.active())EntertainmentClient.request(EntertainmentRequest.Action.STOP);
        else if(selected)EntertainmentClient.request(EntertainmentRequest.Action.ADVENTURING_TIME);
        selected=false;update();
    }
    private void update() {
        if(confirm==null)return;
        var state=EntertainmentClient.state();
        boolean returning=state!=null && (state.phase().equals("return") || state.phase().equals("returning"));
        confirm.active=EntertainmentClient.active()?!returning:selected && matches;
        confirm.setMessage(Component.translatable(EntertainmentClient.active()?"entertainment.stop_return":"entertainment.confirm"));
    }
    public void tick(){if(open){search.tick();update();}}
    public void render(GuiGraphics graphics,int mouseX,int mouseY,float delta) {
        if(!open)return;
        update();var font=Minecraft.getInstance().font;
        graphics.pose().pushPose();graphics.pose().translate(0,0,400);
        graphics.fill(left,top,left+width,top+height,0xEF101010);
        graphics.renderOutline(left,top,width,height,0xFF999999);search.render(graphics,mouseX,mouseY,delta);
        if(matches) {
            if(selected || inside(mouseX,mouseY,left+4,top+32,width-8,24))
                graphics.fill(left+4,top+32,left+width-4,top+56,0x60404040);
            graphics.drawString(font,Component.translatable("entertainment.adventuring_time"),left+9,top+40,selected?0xFFFFAA:0xFFFFFF);
        } else graphics.drawString(font,Component.translatable("entertainment.no_matches"),left+9,top+40,0xAAAAAA);
        graphics.enableScissor(left+5,top+61,left+width-5,top+height-31);
        int y=top+65;
        for(var line:font.split(EntertainmentClient.summary(),width-18)) {graphics.drawString(font,line,left+9,y,0xAAAAAA);y+=11;}
        graphics.disableScissor();confirm.render(graphics,mouseX,mouseY,delta);cancel.render(graphics,mouseX,mouseY,delta);
        graphics.pose().popPose();
    }
    public boolean mouseClicked(double x,double y,int mouseButton) {
        if(!open)return false;
        if(!inside(x,y,left,top,width,height)){close();return true;}
        if(mouseButton!=0)return true;
        if(confirm.mouseClicked(x,y,mouseButton) || cancel.mouseClicked(x,y,mouseButton))return true;
        if(matches && !EntertainmentClient.active() && inside(x,y,left+4,top+32,width-8,24)) {selected=true;update();return true;}
        search.mouseClicked(x,y,mouseButton);return true;
    }
    public boolean mouseScrolled(double delta){return open;}
    public boolean mouseDragged(double y){return open;}
    public boolean mouseReleased(){return open;}
    public boolean keyPressed(int key,int scan,int modifiers) {
        if(!open)return false;
        if(key==GLFW.GLFW_KEY_ESCAPE){close();return true;}
        if(key==GLFW.GLFW_KEY_ENTER || key==GLFW.GLFW_KEY_KP_ENTER){execute();return true;}
        if(key==GLFW.GLFW_KEY_DOWN && matches && !EntertainmentClient.active()){selected=true;update();return true;}
        search.keyPressed(key,scan,modifiers);return true;
    }
    public boolean charTyped(char character,int modifiers){if(!open)return false;search.charTyped(character,modifiers);return true;}
    private static boolean inside(double x,double y,int left,int top,int width,int height) {
        return x>=left && y>=top && x<left+width && y<top+height;
    }
}
