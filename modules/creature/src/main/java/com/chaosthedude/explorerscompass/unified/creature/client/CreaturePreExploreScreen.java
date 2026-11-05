package com.chaosthedude.explorerscompass.unified.creature.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 同款金色控件，关闭页面不会取消后台任务。 */
public final class CreaturePreExploreScreen extends Screen {
    private final Screen previous;
    private EditBox radius;
    private Button start,pause,stop;
    public CreaturePreExploreScreen(Screen previous){super(Component.literal("预探索当前维度"));this.previous=previous;}
    @Override public boolean isPauseScreen(){return false;}
    @Override protected void init(){
        int x=Math.max(8,(width-320)/2),w=Math.min(320,width-16),y=Math.max(8,(height-216)/2);
        radius=new EditBox(font,x+82,y+32,Math.max(50,w-100),20,Component.literal("半径"));radius.setMaxLength(4);radius.setValue(Integer.toString(CreaturePreExploreClient.radius));addRenderableWidget(radius);
        int b=Math.max(45,(w-32)/3);
        start=button(x+8,y+157,b,"开始",v->{int value=value();if(value>=128 && value<=1024 && value%16==0)CreaturePreExploreClient.command("start",value);});
        pause=button(x+16+b,y+157,b,"暂停",v->CreaturePreExploreClient.command(CreaturePreExploreClient.paused?"resume":"pause",0));
        stop=button(x+24+2*b,y+157,b,"停止",v->CreaturePreExploreClient.command("stop",0));
        button(x+8,y+186,w-16,"返回指南针",v->onClose());CreaturePreExploreClient.command("status",0);tick();
    }
    private Button button(int x,int y,int w,String name,Button.OnPress action){return addRenderableWidget(new CreatureScreen.GoldButton(x,y,w,22,Component.literal(name),action));}
    private int value(){try{return Integer.parseInt(radius.getValue());}catch(NumberFormatException e){return -1;}}
    @Override public void tick(){radius.tick();int r=value();start.active=!CreaturePreExploreClient.pending() && CreaturePreExploreClient.finished && r>=128 && r<=1024 && r%16==0;
        radius.setEditable(CreaturePreExploreClient.finished);pause.active=stop.active=!CreaturePreExploreClient.pending() && !CreaturePreExploreClient.finished;pause.setMessage(Component.literal(CreaturePreExploreClient.paused?"继续":"暂停"));}
    @Override public void render(GuiGraphics g,int mx,int my,float delta){
        g.fill(0,0,width,height,0xff252627);int x=Math.max(8,(width-320)/2),w=Math.min(320,width-16),y=Math.max(8,(height-216)/2);
        CreatureScreen.panel(g,x,y,w,216,0xff252627);g.drawCenteredString(font,title,x+w/2,y+12,0xffe8ddb3);
        line(g,x,y+38,"半径（格）：");line(g,x,y+60,"128～1024，16的整数倍；默认512");
        line(g,x,y+77,"状态："+CreaturePreExploreClient.label());
        line(g,x,y+94,"已准备："+CreaturePreExploreClient.prepared+" / "+CreaturePreExploreClient.total+"；依赖区块："+CreaturePreExploreClient.dependencies);
        line(g,x,y+111,"登记生物："+CreaturePreExploreClient.entities+"；耗时："+CreaturePreExploreClient.elapsedMillis/1000+"秒");
        line(g,x,y+128,"玩家留在原地；不保证所有生物出现。");line(g,x,y+141,"关闭继续运行，停止不删除已生成地形。");
        super.render(g,mx,my,delta);
    }
    private void line(GuiGraphics g,int x,int y,String text){g.drawString(font,font.plainSubstrByWidth(text,Math.min(304,width-32)),x+8,y,0xffe8ddb3,false);}
    @Override public void onClose(){minecraft.setScreen(previous);}
}
