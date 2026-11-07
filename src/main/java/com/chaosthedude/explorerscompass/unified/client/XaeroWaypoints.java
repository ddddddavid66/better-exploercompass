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
    private static final Map<UUID,Pending> pending=new LinkedHashMap<>();
    private record Pending(ResourceLocation dimension, ResourceLocation id, boolean biome,
                           int x, int y, int z, Object world, Object session, long expiresAt) {}
    private static final Set<Object> dirty=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<String,Method> methods=new HashMap<>();
    private static Connection connection;
    private static Method currentSession;
    private static Constructor<?> constructor;
    private static Object session;
    private static boolean failed,warned;
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
        Object active=currentSession.invoke(null);
        if(active==null)return false;
        if(session!=null && session!=active){flush();starts.clear();}
        session=active;return true;
    }
    private static Object world(ResourceLocation dimension)throws ReflectiveOperationException {
        Object manager=call(session,"getWaypointsManager"),world=call(manager,"getAutoWorld");
        if(world==null)return null;
        Object key=call(world,"getDimId");
        return dimension.equals(((net.minecraft.resources.ResourceKey<?>)key).location())?world:null;
    }
    public static void accept(Connection source,SearchLifecyclePacket packet) {
        var mc=Minecraft.getInstance();var data=packet.data();if(data.getBoolean("reportOnly"))return;
        if(mc.getConnection()==null || source!=mc.getConnection().getConnection() || mc.player==null
                || !data.hasUUID("player") || !mc.player.getUUID().equals(data.getUUID("player")))return;
        if(connection!=source){clear();connection=source;}
        if (!ModList.get().isLoaded("xaeroworldmap") || failed) return;
        try {
            if (!ModList.get().isLoaded("xaerominimap")) {
                if (packet.terminal() && data.getString("result").equals("found"))
                    warning("自动路径点需要同时安装 Xaero 的小地图。");
                return;
            }
            var dimension=ResourceLocation.tryParse(data.getString("dimension"));
            if (dimension==null || dimension.getPath().isEmpty()) return;
            boolean ready=available();
            if(!packet.terminal()) {
                starts.clear(); // 探险者每次只接受一个前台搜索。
                Object world=ready?world(dimension):null;
                if(world!=null)starts.put(packet.token(),world);
                return;
            }
            Object world=starts.remove(packet.token());
            int[] p=data.getIntArray("foundPos");
            var id=ResourceLocation.tryParse(data.getString("foundId"));
            if(!data.getString("result").equals("found") || p.length!=3 || id==null || id.getPath().isEmpty())return;
            if(world==null && ready)world=world(dimension);
            if(pending.size()>=64 && !pending.containsKey(packet.token()))pending.remove(pending.keySet().iterator().next());
            pending.putIfAbsent(packet.token(),new Pending(dimension,id,data.getString("kind").equals("biome"),
                    p[0],p[1],p[2],world,session,System.nanoTime()+10_000_000_000L));
            tick();
        }catch(ReflectiveOperationException|RuntimeException|LinkageError error){error(error);}
    }
    public static void tick() {
        if(connection==null || failed)return;
        try {
            var mc=Minecraft.getInstance();
            if(mc.getConnection()==null || mc.getConnection().getConnection()!=connection)return;
            long now=System.nanoTime();
            pending.values().removeIf(item -> now>=item.expiresAt());
            if(!available())return;
            for(var it=pending.values().iterator();it.hasNext();) {
                Pending item=it.next();
                if(item.session()!=null && item.session()!=session){it.remove();continue;}
                Object world=world(item.dimension());
                if(world==null)continue;
                if(item.world()!=null && item.world()!=world){it.remove();continue;}
                Object set=call(world,"getCurrentSet");
                if(set==null)continue;
                String name=item.biome()?FilterUi.biomeName(item.id()):StructureUtils.getPrettyStructureName(item.id());
                boolean exists=false;
                for(Object waypoint:(Iterable<?>)call(set,"getWaypoints"))
                    if(name.equals(call(waypoint,"getName")) && (int)call(waypoint,"getX")==item.x()
                            && (int)call(waypoint,"getY")==item.y() && (int)call(waypoint,"getZ")==item.z()){exists=true;break;}
                if(!exists) {
                    Object waypoint=constructor.newInstance(item.x(),item.y(),item.z(),name,"C",3);
                    call(waypoint,"setYIncluded",new Class<?>[]{boolean.class},true);
                    call(set,"add",new Class<?>[]{constructor.getDeclaringClass()},waypoint);
                    call(call(session,"getWaypointsManager"),"updateWaypoints");
                    dirty.add(world);
                    ExplorersCompass.LOGGER.info("Xaero waypoint {} ({}, {}, {}) {}",item.id(),item.x(),item.y(),item.z(),item.dimension());
                }
                it.remove();
            }
            // Xaero 26.1.0 会先销毁小地图模块，再触发 Forge 的退出事件。
            // 本次队列写入在仍拥有有效会话时合并保存，不把持久化推迟到退出。
            if(!dirty.isEmpty())flush();
        }catch(ReflectiveOperationException|RuntimeException|LinkageError error){error(error);}
    }
    private static void flush()throws ReflectiveOperationException {
        if(session==null || dirty.isEmpty())return;
        Object manager=call(session,"getWaypointsManager");
        if(manager==null)return;
        Object io=call(manager,"getWorldManagerIO");
        if(io==null)return;
        Class<?> worldClass=Class.forName("xaero.hud.minimap.world.MinimapWorld");
        for(Object world:dirty)call(io,"saveWorld",new Class<?>[]{worldClass},world);
        dirty.clear();
    }
    public static void clear() {
        try{flush();}catch(ReflectiveOperationException|RuntimeException|LinkageError error){error(error);}
        starts.clear();pending.clear();dirty.clear();connection=null;session=null;failed=false;warned=false;
    }
    private static void error(Throwable error){failed=true;ExplorersCompass.LOGGER.warn("Xaero 自动标记不可用",error);warning("Xaero 自动路径点失败，搜索与传送仍可使用；详情见日志。");}
    private static void warning(String message){if(warned)return;warned=true;var player=Minecraft.getInstance().player;if(player!=null)player.displayClientMessage(Component.literal(message),false);}
}
