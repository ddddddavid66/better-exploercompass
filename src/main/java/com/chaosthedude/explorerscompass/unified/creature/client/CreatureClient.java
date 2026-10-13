package com.chaosthedude.explorerscompass.unified.creature.client;

import com.chaosthedude.explorerscompass.config.ConfigHandler;
import com.chaosthedude.explorerscompass.unified.creature.catalog.*;
import com.chaosthedude.explorerscompass.unified.creature.search.MobPoint;
import com.chaosthedude.explorerscompass.unified.creature.network.CreatureRequest;
import com.chaosthedude.explorerscompass.unified.network.UnifiedNetwork;
import com.chaosthedude.explorerscompass.unified.client.ClientSession;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

/** 连接级结果快照；关闭日志与界面均不影响地图蓝点。 */
public final class CreatureClient {
    public static List<CreatureEntry> entries=List.of();
    public static List<MobPoint> points=List.of();
    public static UUID query,catalog;
    public static String state="idle";public static boolean finished=true,confirmation,canTeleport;
    public static int round,rounds,chunks,selection;
    public static long anchorSamples,anchorLimit;
    public static String anchorMode="",anchorStage="";
    public static int captured,captureTotal,chunksTotal;
    private static Object connection;
    private static long pointRevision;
    private static UUID pendingTeleportRequest,pendingTeleportQuery;
    private static CreatureScreen pendingTeleportScreen;
    private static long pendingTeleportStarted;
    private static final Map<Integer,List<MobPoint>> pendingParts=new HashMap<>();
    private CreatureClient() {}
    public static void open(){var mc=Minecraft.getInstance();connection=mc.getConnection();ClientSession.requestCatalog();catalog=UUID.randomUUID();send(base("catalog",catalog));mc.setScreen(new CreatureScreen());}
    public static void accept(CompoundTag n) {
        var mc=Minecraft.getInstance();if(n==null || mc.getConnection()!=connection || !n.hasUUID("request"))return;
        if(n.getString("action").equals("catalog")) {
            if(!n.getUUID("request").equals(catalog))return;
            entries=n.getList("entries",10).stream().map(t->CreatureCatalog.decode((CompoundTag)t)).toList();canTeleport=n.getBoolean("canTeleport");return;
        }
        if(query==null || !query.equals(n.getUUID("request")))return;
        UUID selected=selected()==null?null:selected().uuid();
        if(n.contains("state"))state=n.getString("state");
        if(n.contains("finished"))finished=n.getBoolean("finished");
        if(n.contains("round"))round=n.getInt("round");
        if(n.contains("rounds"))rounds=n.getInt("rounds");
        if(n.contains("chunks"))chunks=n.getInt("chunks");
        if(n.contains("anchorSamples"))anchorSamples=n.getLong("anchorSamples");
        if(n.contains("anchorLimit"))anchorLimit=n.getLong("anchorLimit");
        if(n.contains("anchorMode"))anchorMode=n.getString("anchorMode");
        if(n.contains("anchorStage"))anchorStage=n.getString("anchorStage");
        if(n.contains("captured"))captured=n.getInt("captured");
        if(n.contains("captureTotal"))captureTotal=n.getInt("captureTotal");
        chunksTotal=n.contains("chunksTotal")?n.getInt("chunksTotal"):rounds*64;
        if(n.contains("points")){
            long revision=n.getLong("revision");if(revision!=pointRevision){pointRevision=revision;pendingParts.clear();}
            pendingParts.put(n.getInt("part"),n.getList("points",10).stream().map(t->MobPoint.decode((CompoundTag)t)).toList());
            int parts=Math.max(1,n.getInt("parts"));
            if(pendingParts.size()==parts){var combined=new ArrayList<MobPoint>();for(int i=0;i<parts;i++)combined.addAll(pendingParts.get(i));points=List.copyOf(combined);selection=0;if(selected!=null)for(int i=0;i<points.size();i++)if(points.get(i).uuid().equals(selected)){selection=i;break;}}
        }
        if(n.contains("confirmation"))confirmation=n.getBoolean("confirmation");
        if(state.equals("teleport_moved"))confirmation=false;
        acceptTeleport(n);
    }
    public static void search(ResourceLocation type,ResourceLocation dimension,ResourceLocation biome) {
        clearPendingTeleport();query=UUID.randomUUID();pendingParts.clear();pointRevision=0;points=List.of();state="initial";finished=false;confirmation=false;selection=0;
        anchorSamples=anchorLimit=0;anchorMode=anchorStage="";captured=captureTotal=chunks=round=0;rounds=ConfigHandler.CLIENT.creatureRounds.get();chunksTotal=rounds*64;
        var n=base("search",query);n.putString("type",type.toString());n.putString("dimension",dimension==null?"":dimension.toString());n.putString("biome",biome==null?"":biome.toString());n.putInt("rounds",ConfigHandler.CLIENT.creatureRounds.get());send(n);
    }
    public static void stop(){clearPendingTeleport();if(query!=null)send(base("cancel",query));finished=true;state="cancelled";}
    public static void teleport(){
        var mc=Minecraft.getInstance();MobPoint p=selected();
        if(p==null || query==null || !finished || !canTeleport || pendingTeleportRequest!=null || mc.getConnection()==null)return;
        pendingTeleportRequest=UUID.randomUUID();pendingTeleportQuery=query;
        pendingTeleportScreen=mc.screen instanceof CreatureScreen screen?screen:null;pendingTeleportStarted=System.nanoTime();
        var n=base("teleport",query);n.putUUID("entity",p.uuid());n.putUUID("teleportRequest",pendingTeleportRequest);n.putBoolean("fallback",confirmation);send(n);
    }
    public static boolean teleportPending(){return pendingTeleportRequest!=null;}
    private static void acceptTeleport(CompoundTag n){
        if(pendingTeleportRequest==null || !n.hasUUID("teleportRequest") || !pendingTeleportRequest.equals(n.getUUID("teleportRequest"))
                || !Objects.equals(query,pendingTeleportQuery) || !n.contains("teleportAccepted"))return;
        var mc=Minecraft.getInstance();
        // 回执必须属于这一次点击与同一个界面，迟到包不能关闭重开后的界面或世界地图。
        if(n.getBoolean("teleportAccepted") && pendingTeleportScreen!=null && mc.screen==pendingTeleportScreen)pendingTeleportScreen.onClose();
        clearPendingTeleport();
    }
    private static void clearPendingTeleport(){pendingTeleportRequest=null;pendingTeleportQuery=null;pendingTeleportScreen=null;pendingTeleportStarted=0;}
    public static MobPoint selected(){return points.isEmpty()?null:points.get(Math.floorMod(selection,points.size()));}
    public static void cycle(int delta){if(!points.isEmpty())selection=Math.floorMod(selection+delta,points.size());}
    private static CompoundTag base(String action,UUID id){var n=new CompoundTag();n.putString("action",action);n.putUUID("request",id);return n;}
    private static void send(CompoundTag n){if(Minecraft.getInstance().getConnection()!=null)UnifiedNetwork.CHANNEL.sendToServer(new CreatureRequest(n));}
    public static void clear(){entries=List.of();points=List.of();pendingParts.clear();pointRevision=0;query=null;catalog=null;connection=null;state="idle";finished=true;confirmation=canTeleport=false;clearPendingTeleport();anchorSamples=anchorLimit=0;anchorMode=anchorStage="";round=rounds=chunks=selection=captured=captureTotal=chunksTotal=0;CreatureScreen.clearPreviews();}
    public static void tick(){
        var mc=Minecraft.getInstance();if(connection!=null && connection!=mc.getConnection())clear();
        if(pendingTeleportRequest!=null && System.nanoTime()-pendingTeleportStarted>10_000_000_000L){clearPendingTeleport();if(mc.player!=null)mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("生物传送请求尚未确认，可重新尝试。"),true);}
        com.chaosthedude.explorerscompass.unified.creature.map.CreatureMaps.tick();
    }
    public static boolean dots(){return ConfigHandler.CLIENT.creatureDots.get();}
    public static void toggleDots(){ConfigHandler.CLIENT.creatureDots.set(!dots());ConfigHandler.CLIENT.creatureDots.save();}
}
