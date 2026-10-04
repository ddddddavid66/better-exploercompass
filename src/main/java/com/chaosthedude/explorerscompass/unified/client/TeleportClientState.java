package com.chaosthedude.explorerscompass.unified.client;

import com.chaosthedude.explorerscompass.unified.data.EventLedger;
import com.chaosthedude.explorerscompass.unified.network.TeleportSucceededPacket;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;

/** 登入期间可排队；旧连接的事件绝不写入新存档或服务器。 */
public final class TeleportClientState {
    private record Pending(Connection connection, TeleportSucceededPacket packet) {}
    private static final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private static final EventLedger ledger = new EventLedger();
    private TeleportClientState() {}
    public static void accept(Connection connection, TeleportSucceededPacket packet) {
        pending.addLast(new Pending(connection, packet)); tick();
    }
    public static void tick() {
        var mc = Minecraft.getInstance(); var current = mc.getConnection();
        if (current == null) return;
        while (!pending.isEmpty()) {
            var event = pending.peekFirst();
            if (event.connection() != current.getConnection()) { pending.removeFirst(); continue; }
            if (mc.player == null) return;
            if (!mc.player.getUUID().equals(event.packet().playerId())) { pending.removeFirst(); continue; }
            if (!ClientSession.prepareCounters()) return;
            pending.removeFirst();
            if (ledger.first(event.packet().eventId())) ClientSession.recordTeleport(event.packet());
        }
    }
    public static void clear() { pending.clear(); ledger.clear(); }
}
