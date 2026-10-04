package com.chaosthedude.explorerscompass.unified.data;

import java.util.Objects;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/** 可淘汰的有界缓存；Guava 由 Forge 提供，不打包第三方实现。 */
public final class BoundedMemoizer<K,V> {
    private final Cache<K,V> values;
    public BoundedMemoizer(int capacity) {
        if(capacity < 0) throw new IllegalArgumentException();
        values = CacheBuilder.newBuilder().maximumSize(capacity).concurrencyLevel(1).build();
    }
    public V get(K key, Function<K,V> loader) {
        try { return values.get(key, () -> Objects.requireNonNull(loader.apply(key))); }
        catch (ExecutionException | UncheckedExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException(cause);
        }
    }
    public int size() { values.cleanUp(); return Math.toIntExact(values.size()); }
    /** 调度提示只读取计数，不在请求线程触发维护或清理。 */
    public boolean hasEntries() { return values.size()!=0; }
    /** 查询拦截器只能读取已完成值，不能在缓存未命中时递归调用被拦截的方法。 */
    public V peek(K key) { return values.getIfPresent(key); }
    public void remember(K key, V value) { values.put(key, Objects.requireNonNull(value)); }
}
