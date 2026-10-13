package com.chaosthedude.explorerscompass.unified.client;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.util.StructureUtils;
import com.chaosthedude.explorerscompass.unified.network.SearchLifecyclePacket;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

/** 可选客户端桥接：仅调用公开 API，不读取或重写 Xaero 私有文件格式。 */
public final class XaeroWaypoints {
    private static final Map<UUID,Object> starts=new HashMap<>();
    private static final Map<UUID,Object[]> pending=new LinkedHashMap<>();
    private static final Set<Object> dirty=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<String,Method> methods=new HashMap<>();
    private static Connection connection;
    private static Method currentSession;
    private static Constructor<?> constructor;
    private static Object session;
    private static boolean failed,warned;
    private static long saveAt;
    private XaeroWaypoints() {}
    private static Object call(Object object,String name,Class<?>[] types,Object...args)throws ReflectiveOperationException {
        String key=object.getClass().getName()+"#"+name+Arrays.toString(types);
        Method method=methods.get(key);
        if(method==null){method=object.getClass().getMethod(name,types);methods.put(key,method);}
        return method.invoke(object,args);
    }
    private static Object call(Object object,String name)throws ReflectiveOperationException{return call(object,name,new Class<?>[0]);}
    private static boolean available()throws ReflectiveOperationException {
        if(failed || !ModList.get().isLoaded("xaeroworldmap"))return false;
        if(!ModList.get().isLoaded("xaerominimap")){warning("自动路径点需要同时安装 Xaero 的小地图。");return false;}
        if(currentSession==null) {
            currentSession=Class.forName("xaero.common.XaeroMinimapSession").getMethod("getCurrentSession");
            constructor=Class.forName("xaero.common.minimap.waypoints.Waypoint").getConstructor(int.class,int.class,int.class,String.class,String.class,int.class);
        }
        session=currentSession.invoke(null);return session!=null;
    }
    private static Object world(ResourceLocation dimension)throws ReflectiveOperationException {
        Object manager=call(session,"getWaypointsManager"),world=call(manager,"getAutoWorld");
        if(world==null)return null;
        Object key=call(world,"getDimId");
        return dimension.equals(((net.minecraft.resources.ResourceKey<?>)key).location())?world:null;
    }
    public static void accept(Connection source,SearchLifecyclePacket packet) {
        var mc=Minecraft.getInstance();var data=packet.data();
        if(mc.getConnection()==null || source!=mc.getConnection().getConnection() || mc.player==null
                || !data.hasUUID("player") || !mc.player.getUUID().equals(data.getUUID("player")))return;
        if(connection!=source){clear();connection=source;}
        try {
            if(!available())return;
            var dimension=ResourceLocation.tryParse(data.getString("dimension"));if(dimension==null)return;
            if(!packet.terminal()){Object world=world(dimension);if(world!=null)starts.put(packet.token(),world);return;}
            Object world=starts.remove(packet.token());
            if(!data.getString("result").equals("found") || data.getIntArray("foundPos").length!=3)return;
            if(world==null)world=world(dimension);
            pending.putIfAbsent(packet.token(),new Object[]{packet,world});tick();
        }catch(ReflectiveOperationException|RuntimeException error){error(error);}
    }
    public static void tick() {
        if(connection==null || failed)return;
        try {
            if(!available())return;
            for(var it=pending.entrySet().iterator();it.hasNext();) {
                var entry=it.next();var item=entry.getValue();var data=((SearchLifecyclePacket)item[0]).data();
                Object world=item[1]!=null?item[1]:world(new ResourceLocation(data.getString("dimension")));
                if(world==null)continue;
                var key=new ResourceLocation(data.getString("foundId"));
                String name=data.getString("kind").equals("biome")?FilterUi.biomeName(key):StructureUtils.getPrettyStructureName(key);
                int[] p=data.getIntArray("foundPos");Object set=call(world,"getCurrentSet");
                boolean exists=false;
                for(Object waypoint:(Iterable<?>)call(set,"getWaypoints"))
                    if(name.equals(call(waypoint,"getName")) && (int)call(waypoint,"getX")==p[0]
                            && (int)call(waypoint,"getY")==p[1] && (int)call(waypoint,"getZ")==p[2]){exists=true;break;}
                if(!exists) {
                    Object waypoint=constructor.newInstance(p[0],p[1],p[2],name,"C",3);
                    call(waypoint,"setYIncluded",new Class<?>[]{boolean.class},true);
                    call(set,"add",new Class<?>[]{constructor.getDeclaringClass()},waypoint);
                    call(call(session,"getWaypointsManager"),"updateWaypoints");dirty.add(world);saveAt=System.nanoTime()+2_000_000_000L;
                    ExplorersCompass.LOGGER.info("Xaero waypoint {} {} {}",key,Arrays.toString(p),data.getString("dimension"));
                }
                it.remove();
            }
            if(!dirty.isEmpty() && System.nanoTime()>=saveAt)flush();
        }catch(ReflectiveOperationException|RuntimeException error){error(error);}
    }
    private static void flush()throws ReflectiveOperationException {
        if(session==null)return;
        Object manager=call(session,"getWaypointsManager"),io=call(manager,"getWorldManagerIO");
        Class<?> worldClass=Class.forName("xaero.hud.minimap.world.MinimapWorld");
        for(Object world:dirty)call(io,"saveWorld",new Class<?>[]{worldClass},world);
        dirty.clear();
    }
    public static void clear() {
        try{flush();}catch(ReflectiveOperationException|RuntimeException error){error(error);}
        starts.clear();pending.clear();dirty.clear();connection=null;session=null;failed=false;warned=false;
    }
    private static void error(Exception error){failed=true;ExplorersCompass.LOGGER.warn("Xaero 自动标记不可用",error);warning("Xaero 自动路径点失败，搜索与传送仍可使用；详情见日志。");}
    private static void warning(String message){if(warned)return;warned=true;var player=Minecraft.getInstance().player;if(player!=null)player.displayClientMessage(Component.literal(message),false);}
}
