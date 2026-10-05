package com.chaosthedude.explorerscompass.unified.client;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.chaosthedude.explorerscompass.unified.data.*;
import com.chaosthedude.explorerscompass.unified.network.SearchLifecyclePacket;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;

/** 开关按请求开始时快照；终态独立于物品和当前筛选，关闭后补全已收录请求。 */
public final class SearchLogClient {
    private static AsyncSearchLog writer;
    private static Connection connection;
    private static final Map<UUID,Map<String,Object>> active=new HashMap<>();
    private static final LinkedHashSet<UUID> finished=new LinkedHashSet<>();
    private SearchLogClient() {}
    public static AsyncSearchLog writer() {
        if(writer==null) {
            writer=new AsyncSearchLog(FMLPaths.GAMEDIR.get().resolve("logs/explorerscompass-unified/search-history.jsonl"),SearchLogClient::message);
            Runtime.getRuntime().addShutdownHook(new Thread(()->writer.close(),"Compass-log-close"));
        }
        return writer;
    }
    public static void message(String text) {
        var mc=Minecraft.getInstance();mc.execute(()->{if(mc.player!=null)mc.player.displayClientMessage(Component.literal("[指南针日志] "+text),false);});
    }
    public static void accept(Connection source,SearchLifecyclePacket packet) {
        var mc=Minecraft.getInstance();if(mc.getConnection()==null || source!=mc.getConnection().getConnection())return;
        if(connection!=source){disconnect();connection=source;}
        var data=packet.data();
        if(mc.player==null || !data.hasUUID("player") || !mc.player.getUUID().equals(data.getUUID("player")))return;
        if(!packet.terminal()) {
            if(!ConfigHandler.CLIENT.searchLogEnabled.get() || writer().paused() || active.containsKey(packet.token()) || finished.contains(packet.token()))return;
            var fields=new LinkedHashMap<String,Object>();
            fields.put("requestId",packet.token().toString());fields.put("startedAt",data.getLong("startedAt"));
            fields.put("time",java.time.Instant.ofEpochMilli(data.getLong("startedAt")).toString());
            fields.put("account",Objects.toString(mc.getUser().getProfileId(),mc.player.getUUID().toString()));
            fields.put("environment",environment());fields.put("kind",data.getString("kind"));
            var target=TargetDimension.parse(data.getString("category"));
            fields.put("targetName",target==null?data.getString("category"):data.getString("kind").equals("biome")?FilterUi.biomeName(target):StructureUtils.getPrettyStructureName(target));
            var targets=data.getList("targets",Tag.TAG_STRING);var keys=new ArrayList<String>();for(int i=0;i<targets.size();i++)keys.add(targets.getString(i));
            fields.put("targets",List.copyOf(keys));fields.put("origin",data.getIntArray("origin"));fields.put("requestKind",data.getString("requestKind"));
            fields.put("dimension",data.getString("dimension"));fields.put("dimensionName",DimensionNames.name(TargetDimension.parse(data.getString("dimension"))));
            fields.put("mode",data.getString("mode"));active.put(packet.token(),fields);return;
        }
        var fields=active.remove(packet.token());if(fields==null || !finished.add(packet.token()))return;
        while(finished.size()>1024)finished.remove(finished.iterator().next());
        fields.put("mode",data.getString("mode"));fields.put("result",data.getString("result"));fields.put("foundId",data.getString("foundId"));
        fields.put("completed",true);fields.put("durationNanos",data.getLong("durationNanos"));
        fields.put("durationMs",data.getLong("durationNanos")/1_000_000.0);
        for(String key:List.of("samples","candidates","mainNanos","workerNanos","preparationNanos","confirmationNanos","waitingNanos"))fields.put(key,data.getLong(key));
        for(String key:List.of("checks","chunkRequests"))fields.put(key,data.getInt(key));
        writer().append(new SearchLogEntry(fields));
    }
    private static String environment() {
        var mc=Minecraft.getInstance();
        if(mc.getSingleplayerServer()!=null)return mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
        return mc.getCurrentServer()==null?"unknown":mc.getCurrentServer().ip;
    }
    public static void disconnect() {
        for(var fields:active.values()) {
            fields.put("completed",false);fields.put("result","disconnected");fields.put("durationNanos",-1L);
            writer().append(new SearchLogEntry(fields));
        }
        active.clear();finished.clear();connection=null;
        if(writer!=null)writer.flushAsync();
    }
    public static void enabled(boolean enabled) {
        var config=ConfigHandler.CLIENT.searchLogEnabled;config.set(enabled);config.save();
        if(enabled)writer().open();else writer().flushAsync();
        message(enabled?"已开启，新搜索将被记录":"已关闭，已收录的搜索结束后仍会补全日志");
    }
    public static void status() {
        var log=writer();message((ConfigHandler.CLIENT.searchLogEnabled.get()?"开启":"关闭")+(log.paused()?"（暂停收录）":"")
                +"，"+log.bytes()+" / 100000000 字节；"+log.file()+"；open / close（clsoe） / sort");
    }
}
