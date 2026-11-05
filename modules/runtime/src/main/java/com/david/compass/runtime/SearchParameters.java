package com.david.compass.runtime;

/** 请求创建时冻结参数，公共层不得读取任一产品的静态配置。 */
public record SearchParameters(int radius, long sampleLimit, int horizontalSpacing,
                               int verticalSpacing, int depthInterval, boolean asynchronous) {
    public SearchParameters {
        if (radius < 0 || sampleLimit < 0 || horizontalSpacing < 1
                || verticalSpacing < 0 || depthInterval < 1)
            throw new IllegalArgumentException("Invalid search parameters");
    }
}
