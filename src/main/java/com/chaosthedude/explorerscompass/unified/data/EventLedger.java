package com.chaosthedude.explorerscompass.unified.data;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 连接内去重，断线清空；按事件去重，不能按目标去重。 */
public final class EventLedger {
    private final Set<UUID> events = new HashSet<>();
    public boolean first(UUID event) { return events.add(event); }
    public void clear() { events.clear(); }
}
