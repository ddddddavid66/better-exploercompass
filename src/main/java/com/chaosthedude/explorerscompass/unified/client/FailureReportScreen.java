package com.chaosthedude.explorerscompass.unified.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** 独立滚动区最后绘制，避免指南针模型及底层滚动事件穿透。 */
public final class FailureReportScreen extends Screen {
    private final Screen parent;
    private final FailureReports.Report report;
    private List<FormattedCharSequence> lines=List.of();
    private double scroll;
    public FailureReportScreen(Screen parent,FailureReports.Report report){super(Component.literal("搜索失败报告"));this.parent=parent;this.report=report;}
    @Override protected void init(){
        int left=Math.max(8,(width-Math.min(840,width-16))/2),panelWidth=width-left*2;
        addRenderableWidget(Button.builder(Component.literal("在文件夹中显示报告"),b->FailureReports.reveal(report)).bounds(left,27,Math.min(220,panelWidth-76),20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(width-left-65,27,65,20).build());
        lines=font.split(Component.literal(report.text()),Math.max(40,panelWidth-24));clamp();
    }
    private int top(){return 55;}
    private int bottom(){return height-12;}
    private int maximum(){return Math.max(0,lines.size()*font.lineHeight-(bottom()-top()-12));}
    private void clamp(){scroll=Math.max(0,Math.min(scroll,maximum()));}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        renderBackground(graphics);int left=Math.max(8,(width-Math.min(840,width-16))/2),right=width-left;
        graphics.drawCenteredString(font,title,width/2,10,0xffe5c582);
        graphics.fill(left-1,top()-1,right+1,bottom()+1,0xffb89a57);graphics.fill(left,top(),right,bottom(),0xff171717);
        graphics.enableScissor(left+4,top()+4,right-10,bottom()-4);
        int first=Math.max(0,(int)scroll/font.lineHeight);
        for(int i=first;i<lines.size();i++){
            int y=top()+6+i*font.lineHeight-(int)scroll;if(y>=bottom())break;
            graphics.drawString(font,lines.get(i),left+7,y,0xffdedede,false);
        }
        graphics.disableScissor();
        if(maximum()>0){int track=bottom()-top()-8,thumb=Math.max(16,track*track/Math.max(track,lines.size()*font.lineHeight));
            int y=top()+4+(int)((track-thumb)*scroll/maximum());graphics.fill(right-7,y,right-3,y+thumb,0xffb89a57);}
        super.render(graphics,mouseX,mouseY,partial);
    }
    @Override public boolean mouseScrolled(double x,double y,double amount){scroll-=amount*font.lineHeight*3;clamp();return true;}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(button==0 && x>=width-Math.max(8,(width-Math.min(840,width-16))/2)-12){scroll+=dy*Math.max(1.0,(double)maximum()/Math.max(1,bottom()-top()-24));clamp();return true;}
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
