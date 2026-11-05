package com.david.compass.runtime;

import java.util.Objects;
import java.util.UUID;

/** 产品、玩家、请求及重载代次共同标识一次前台操作。 */
public record TaskKey(String product, UUID player, UUID request, long generation) {
    public TaskKey {
        Objects.requireNonNull(product);
        Objects.requireNonNull(player);
        Objects.requireNonNull(request);
        if (product.isBlank() || generation < 0) throw new IllegalArgumentException("Invalid task key");
    }
}
