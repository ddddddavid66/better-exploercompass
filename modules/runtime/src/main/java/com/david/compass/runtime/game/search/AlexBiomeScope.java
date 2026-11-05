package com.david.compass.runtime.game.search;

import java.lang.reflect.*;
import java.util.function.Supplier;
import net.minecraftforge.fml.ModList;

/** 只替换搜索调用中的官方可变对象；公式、注入与事件仍由 Alex 原代码执行。 */
public final class AlexBiomeScope {
    private static final ThreadLocal<Object> CURRENT=new ThreadLocal<>();
    private static Context prepared;
    private static volatile Object originalObject;
    private static boolean observed;
    private AlexBiomeScope() {}
    public static final class Context {
        private final Constructor<?> constructor;
        private final Field offset,distance;
        private final Object original,distanceValue;
        private final double offsetValue;
        private final ThreadLocal<Object> workers=ThreadLocal.withInitial(this::create);
        private Context(long worldSeed) throws ReflectiveOperationException {
            Class<?> rarity=Class.forName("com.github.alexmodguy.alexscaves.server.level.biome.ACBiomeRarity");
            Field field=rarity.getDeclaredField("VORONOI_GENERATOR");field.setAccessible(true);original=field.get(null);
            Class<?> type=original.getClass();constructor=type.getConstructor(long.class);
            offset=type.getDeclaredField("offsetAmount");distance=type.getDeclaredField("distanceType");
            offset.setAccessible(true);distance.setAccessible(true);offsetValue=offset.getDouble(original);distanceValue=distance.get(original);
            // 缺少注入或未来版本字段变更时明确拒绝后台，不静默访问原共享对象。
            Method query=rarity.getMethod("getRareBiomeInfoForQuad",long.class,int.class,int.class);
            // 首次探针只使用当前世界种子；即使注入缺失，也不把任意测试种子写入正常生成对象。
            with(()->invoke(query,worldSeed,0,0));
            if(!observed)throw new IllegalStateException("Alex 搜索对象重定向未应用");
            Object reference=create();
            for(int i=0;i<24;i++) {
                long seed=42L+i*98765431L;int x=i*137-1600,z=1200-i*311;
                Object serial=bind(reference,()->invoke(query,seed,x,z));
                Object local=with(()->invoke(query,seed,x,z));
                if(!java.util.Objects.equals(serial,local))throw new IllegalStateException("Alex 搜索对象与官方查询不一致");
            }
        }
        private Object create() {
            try {Object value=constructor.newInstance(0L);offset.setDouble(value,offsetValue);distance.set(value,distanceValue);return value;}
            catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        }
        public <T> T with(Supplier<T> action) {
            return bind(workers.get(),action);
        }
        private <T> T bind(Object value,Supplier<T> action) {
            Object previous=CURRENT.get();CURRENT.set(value);
            try{return action.get();}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
        }
    }
    private static Object invoke(Method query,long seed,int x,int z){try{return query.invoke(null,seed,x,z);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    static Context prepare(long worldSeed) {
        if(!ModList.get().isLoaded("alexscaves"))return null;
        String version=ModList.get().getModContainerById("alexscaves").orElseThrow().getModInfo().getVersion().toString();
        if(!version.equals("2.0.2"))throw new IllegalStateException("未经核对的 Alex 洞穴版本 "+version);
        if(prepared==null)try{prepared=new Context(worldSeed);}catch(ReflectiveOperationException e){throw new IllegalStateException("无法准备 Alex 搜索对象",e);}
        return prepared;
    }
    public static Object scoped(){Object current=CURRENT.get();if(current!=null){observed=true;return current;}return original();}
    private static Object original() {
        Object value=originalObject;if(value!=null)return value;
        try {Field f=Class.forName("com.github.alexmodguy.alexscaves.server.level.biome.ACBiomeRarity").getDeclaredField("VORONOI_GENERATOR");f.setAccessible(true);return originalObject=f.get(null);}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    static void clear(){prepared=null;observed=false;}
}
