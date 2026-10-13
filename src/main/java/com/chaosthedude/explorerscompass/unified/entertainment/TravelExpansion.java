package com.chaosthedude.explorerscompass.unified.entertainment;

/** 只允许原半径、两倍和四倍；额度随面积增长。 */
public final class TravelExpansion {
    private TravelExpansion() {}
    public static int radius(int base,int step){check(step);return (int)Math.min(1_000_000L,(long)base<<step);}
    public static long quota(long base,int step){check(step);return Math.multiplyExact(base,1L<<(step*2));}
    private static void check(int step){if(step<0 || step>2)throw new IllegalArgumentException("Expansion outside 0..2");}
}
