package com.chaosthedude.explorerscompass.unified.search;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** 地形提示只改变处理顺序；每三个高分候选插入一个低分候选，二者都不丢弃。 */
final class CrateCandidateOrder<T> {
    private final PriorityQueue<T> known,likely,other;
    private final Predicate<T> indexed;
    private final ToIntFunction<T> score;
    private int streak;
    CrateCandidateOrder(Comparator<T> distance,Predicate<T> indexed,ToIntFunction<T> score) {
        this.indexed=indexed;this.score=score;
        known=new PriorityQueue<>(distance);
        likely=new PriorityQueue<>(distance);
        other=new PriorityQueue<>(Comparator.<T>comparingInt(score).reversed().thenComparing(distance));
    }
    void add(T value) {
        if(indexed.test(value))known.add(value);
        else if(score.applyAsInt(value)>=6)likely.add(value);
        else other.add(value);
    }
    T poll() {
        if(!known.isEmpty())return known.poll();
        if(!likely.isEmpty() && (streak<3 || other.isEmpty())) {streak++;return likely.poll();}
        streak=0;return other.poll();
    }
    int size() {return known.size()+likely.size()+other.size();}
    boolean preferred() {return !known.isEmpty() || !likely.isEmpty();}
}
