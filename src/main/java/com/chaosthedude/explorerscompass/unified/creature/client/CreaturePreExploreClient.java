package com.chaosthedude.explorerscompass.unified.creature.client;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import com.chaosthedude.explorerscompass.unified.creature.network.CreatureRequest;
import com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork;

/** 与生物查询分离，预探索通知不能覆盖实体结果或地图蓝点。 */
public final class CreaturePreExploreClient {
    public static UUID task;
    private static UUID pending;
    public static String state="idle",dimension="";
    public static boolean finished=true,paused;
    public static int prepared,total,dependencies,entities,radius=512;
    public static long elapsedMillis;
    private CreaturePreExploreClient() {}
    public static void command(String action,int value){
        if(Minecraft.getInstance().getConnection()==null || pending!=null)return;
        pending=UUID.randomUUID();var n=new CompoundTag();n.putString("action","preexplore_"+action);n.putUUID("request",pending);
        if(task!=null)n.putUUID("task",task);n.putInt("radius",value);UnifiedNetwork.CHANNEL.sendToServer(new CreatureRequest(n));
    }
    public static boolean pending(){return pending!=null;}
    public static void accept(CompoundTag n){
        boolean receipt=pending!=null && n.hasUUID("receipt") && pending.equals(n.getUUID("receipt"));
        if(!receipt && (task==null || !task.equals(n.getUUID("request"))))return;
        boolean running=!finished;if(receipt){task=n.getUUID("request");pending=null;}
        state=n.getString("state");finished=n.getBoolean("finished");paused=n.getBoolean("paused");
        prepared=n.getInt("prepared");total=n.getInt("total");dependencies=n.getInt("dependencies");entities=n.getInt("entities");elapsedMillis=n.getLong("elapsedMillis");dimension=n.getString("dimension");
        if(n.contains("radius"))radius=n.getInt("radius");
        if(running && finished && Minecraft.getInstance().player!=null)Minecraft.getInstance().player.displayClientMessage(Component.literal("区块预探索："+label()+"；准备 "+prepared+" 个区块，登记 "+entities+" 个真实生物。"),false);
    }
    public static String label(){return switch(state){case "planning"->"规划范围";case "preparing","waiting_slots"->"等待调度";case "waiting_chunks"->"等待地形与实体数据";case "indexing"->"登记真实生物";case "paused"->"已暂停";case "backpressure"->"服务器繁忙，自动暂停";case "complete"->"已完成";case "stopped","cancelled"->"已停止";case "chunk_timeout"->"区块等待超过60秒";case "rejected"->"半径无效或服务端已关闭功能";case "busy"->"其他玩家正在预探索";case "error"->"发生异常，请查看日志";default->"尚未开始";};}
    public static void clear(){task=pending=null;state="idle";dimension="";finished=true;paused=false;prepared=total=dependencies=entities=0;elapsedMillis=0;radius=512;}
}
