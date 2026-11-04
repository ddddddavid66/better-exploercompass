package com.chaosthedude.explorerscompass.unified.search;

/** 分片独占普通long，批次结束才发布快照；没有逐点原子操作或日志。 */
final class BiomeSamplingMetrics {
    long sourceCalls,originHits,columns;
    final Runnable sourceCall=()->sourceCalls++;
    record Snapshot(long sourceCalls,long originHits,long columns) {}
    private volatile Snapshot published=new Snapshot(0,0,0);
    void publish(){published=new Snapshot(sourceCalls,originHits,columns);}
    Snapshot snapshot(){return published;}
}
