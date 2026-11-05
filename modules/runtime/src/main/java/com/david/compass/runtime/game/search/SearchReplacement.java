package com.david.compass.runtime.game.search;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;

/** 产品在启动阶段注册取消回调，互不加载对方的任务实现。 */
public final class SearchReplacement {
    private static final ConcurrentHashMap<String,Consumer<ServerPlayer>> PRODUCTS=new ConcurrentHashMap<>();
    private SearchReplacement() {}
    public static void register(String product,Consumer<ServerPlayer> cancel) {
        if (PRODUCTS.putIfAbsent(product,cancel)!=null) throw new IllegalStateException("Duplicate search product: "+product);
    }
    public static void replace(String source,ServerPlayer player) {
        if(!player.serverLevel().getServer().isSameThread())throw new IllegalStateException("搜索替换必须在服务端线程");
        PRODUCTS.forEach((product,cancel)->{if(!product.equals(source))cancel.accept(player);});
    }
}
