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
    private static Object connection;
    private static long pointRevision;
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
        state=n.getString("state");finished=n.getBoolean("finished");round=n.getInt("round");rounds=n.getInt("rounds");chunks=n.getInt("chunks");
        if(n.contains("points")){
            long revision=n.getLong("revision");if(revision!=pointRevision){pointRevision=revision;pendingParts.clear();}
            pendingParts.put(n.getInt("part"),n.getList("points",10).stream().map(t->MobPoint.decode((CompoundTag)t)).toList());
            int parts=Math.max(1,n.getInt("parts"));
            if(pendingParts.size()==parts){var combined=new ArrayList<MobPoint>();for(int i=0;i<parts;i++)combined.addAll(pendingParts.get(i));points=List.copyOf(combined);selection=0;if(selected!=null)for(int i=0;i<points.size();i++)if(points.get(i).uuid().equals(selected)){selection=i;break;}}
        }
        confirmation=n.getBoolean("confirmation");
        if(state.equals("teleport_moved"))confirmation=false;
    }
    public static void search(ResourceLocation type,ResourceLocation dimension,ResourceLocation biome) {
        query=UUID.randomUUID();pendingParts.clear();pointRevision=0;points=List.of();state="initial";finished=false;confirmation=false;selection=0;
        var n=base("search",query);n.putString("type",type.toString());n.putString("dimension",dimension==null?"":dimension.toString());n.putString("biome",biome==null?"":biome.toString());n.putInt("rounds",ConfigHandler.CLIENT.creatureRounds.get());send(n);
    }
    public static void stop(){if(query!=null)send(base("cancel",query));finished=true;state="cancelled";}
    public static void teleport(){MobPoint p=selected();if(p==null || query==null)return;var n=base("teleport",query);n.putUUID("entity",p.uuid());n.putBoolean("fallback",confirmation);send(n);}
    public static MobPoint selected(){return points.isEmpty()?null:points.get(Math.floorMod(selection,points.size()));}
    public static void cycle(int delta){if(!points.isEmpty())selection=Math.floorMod(selection+delta,points.size());}
    private static CompoundTag base(String action,UUID id){var n=new CompoundTag();n.putString("action",action);n.putUUID("request",id);return n;}
    private static void send(CompoundTag n){if(Minecraft.getInstance().getConnection()!=null)UnifiedNetwork.CHANNEL.sendToServer(new CreatureRequest(n));}
    public static void clear(){entries=List.of();points=List.of();pendingParts.clear();pointRevision=0;query=null;catalog=null;connection=null;state="idle";finished=true;CreatureScreen.clearPreviews();}
    public static void tick(){if(connection!=null && connection!=Minecraft.getInstance().getConnection())clear();com.chaosthedude.explorerscompass.unified.creature.map.CreatureMaps.tick();}
    public static boolean dots(){return ConfigHandler.CLIENT.creatureDots.get();}
    public static void toggleDots(){ConfigHandler.CLIENT.creatureDots.set(!dots());ConfigHandler.CLIENT.creatureDots.save();}
}
