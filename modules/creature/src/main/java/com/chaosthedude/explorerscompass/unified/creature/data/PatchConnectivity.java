package com.chaosthedude.explorerscompass.unified.creature.data;

import java.util.*;

/** 有限采样窗口的连通提示，不用少数样本证明整片群系不存在。 */
public final class PatchConnectivity {
    public record Component(List<Integer> cells,boolean edge){
        public boolean small(){return cells.size()<=2 && !edge;}
        public boolean large(){return cells.size()>=9 || edge;}
    }
    private PatchConnectivity(){}
    public static Component connected(boolean[] mask,int width,int start){
        if(width<1 || mask.length!=width*width || start<0 || start>=mask.length)throw new IllegalArgumentException();
        if(!mask[start])return new Component(List.of(),false);
        var queue=new ArrayDeque<Integer>();var result=new ArrayList<Integer>();var visited=new boolean[mask.length];
        queue.add(start);visited[start]=true;boolean edge=false;
        while(!queue.isEmpty()){
            int at=queue.removeFirst(),x=at%width,z=at/width;result.add(at);
            edge|=x==0 || z==0 || x==width-1 || z==width-1;
            for(int next:new int[]{x>0?at-1:-1,x<width-1?at+1:-1,z>0?at-width:-1,z<width-1?at+width:-1})
                if(next>=0 && mask[next] && !visited[next]){visited[next]=true;queue.addLast(next);}
        }
        return new Component(List.copyOf(result),edge);
    }
}
