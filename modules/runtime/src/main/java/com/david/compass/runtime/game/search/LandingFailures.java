package com.david.compass.runtime.game.search;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 仅记录已结束的手动区块准备请求；同目标只在第四次通知一次。 */
final class LandingFailures {
    private static final int ALERT=4;
    private record Entry(Object world,UUID target,int count){}
    private final Map<UUID,Entry> failures=new HashMap<>();
    boolean record(UUID player,Object world,UUID target){
        var previous=failures.get(player);
        int next=matches(previous,world,target)?Math.min(ALERT+1,previous.count()+1):1;
        failures.put(player,new Entry(world,target,next));
        return next==ALERT;
    }
    void target(UUID player,Object world,UUID target){
        if(!matches(failures.get(player),world,target))failures.remove(player);
    }
    void clear(UUID player){failures.remove(player);}
    void clear(){failures.clear();}
    private static boolean matches(Entry entry,Object world,UUID target){
        return entry!=null && entry.world()==world && Objects.equals(entry.target(),target);
    }
}
