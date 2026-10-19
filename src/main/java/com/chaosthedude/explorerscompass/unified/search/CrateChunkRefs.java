package com.chaosthedude.explorerscompass.unified.search;

import java.util.*;
import java.util.function.LongConsumer;

/** 两个候选共享票据；切换时先准备新集合，再释放旧集合，重叠区块不来回卸载。 */
final class CrateChunkRefs {
    private final Map<Object,Set<Long>> owners=new IdentityHashMap<>();
    private final Map<Long,Integer> references=new HashMap<>();
    private final LongConsumer acquire,release;
    CrateChunkRefs(LongConsumer acquire,LongConsumer release) {this.acquire=acquire;this.release=release;}
    void replace(Object owner,Set<Long> chunks) {
        Set<Long> previous=owners.getOrDefault(owner,Set.of());
        for(long chunk:chunks)if(!previous.contains(chunk)) {
            int count=references.getOrDefault(chunk,0);
            if(count==0)acquire.accept(chunk);
            references.put(chunk,count+1);
        }
        owners.put(owner,Set.copyOf(chunks));
        for(long chunk:previous)if(!chunks.contains(chunk))decrement(chunk);
    }
    void remove(Object owner) {
        var previous=owners.remove(owner);
        if(previous!=null)for(long chunk:previous)decrement(chunk);
    }
    private void decrement(long chunk) {
        int count=references.get(chunk);
        if(count==1) {references.remove(chunk);release.accept(chunk);}
        else references.put(chunk,count-1);
    }
    void clear() {for(Object owner:List.copyOf(owners.keySet()))remove(owner);}
    int size() {return references.size();}
}
