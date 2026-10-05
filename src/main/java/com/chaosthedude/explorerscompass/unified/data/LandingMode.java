package com.chaosthedude.explorerscompass.unified.data;

/** 用户策略与任务的严格/降级阶段分开，直接降级不需要伪造二次确认。 */
public enum LandingMode {
    DEFAULT, SAFE_ONLY, RELAXED_ONLY;
    public LandingMode next() { return values()[(ordinal() + 1) % values().length]; }
    public String key() { return "filters.landing_" + name().toLowerCase(java.util.Locale.ROOT); }
}
