package com.chaosthedude.explorerscompass.unified.search;

/** 墙钟只检测停滞；重复轮询或同阶段通知不能续期。 */
public final class ProgressWatchdog {
    private long samples=-1,checks=-1,changed;
    public ProgressWatchdog(long now){changed=now;}
    public boolean stalled(long sampleCount,long checkCount,long now){
        if(sampleCount!=samples || checkCount!=checks){samples=sampleCount;checks=checkCount;changed=now;}
        return now-changed>40_000_000_000L;
    }
}
