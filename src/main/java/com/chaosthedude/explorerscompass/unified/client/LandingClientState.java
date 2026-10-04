package com.chaosthedude.explorerscompass.unified.client;
import com.chaosthedude.explorerscompass.unified.network.TeleportStatusPacket;
import com.chaosthedude.explorerscompass.unified.search.SearchService;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.world.item.ItemStack;
/** 传送状态按当前连接和目标隔离，不能改变筛选目录或计数。 */
public final class LandingClientState {
    private record Entry(TeleportStatusPacket packet,long received) {}
    private static final Map<UUID,Entry> states=new HashMap<>();
    private static Connection connection;
    private LandingClientState() {}
    public static void accept(Connection source,TeleportStatusPacket packet) {
        var mc=Minecraft.getInstance();if(mc.getConnection()==null || source!=mc.getConnection().getConnection())return;
        if(connection!=source){clear();connection=source;}
        Entry old=states.get(packet.token());
        if(old!=null && packet.revision()<=old.packet.revision())return;
        states.put(packet.token(),new Entry(packet,System.nanoTime()));
        // 成功消息到达时关闭当时的界面；不能在下一 tick 关闭玩家刚重新打开的界面。
        if(packet.state()==TeleportStatusPacket.State.SUCCEEDED && mc.player!=null && mc.screen instanceof com.chaosthedude.explorerscompass.gui.ExplorersCompassScreen) {
            var held=com.chaosthedude.explorerscompass.util.ItemUtils.getHeldItem(mc.player,com.chaosthedude.explorerscompass.ExplorersCompass.explorersCompass);
            if(packet.token().equals(SearchService.token(held)))mc.setScreen(null);
        }
    }
    public static TeleportStatusPacket.State state(ItemStack stack) {
        var token=SearchService.token(stack);Entry entry=states.get(token);
        if(entry==null)return null;
        if(System.nanoTime()-entry.received>30_000_000_000L){states.remove(token);return null;}
        return entry.packet.state();
    }
    public static boolean fallback(ItemStack stack) {var s=state(stack);return com.chaosthedude.explorerscompass.config.ConfigHandler.CLIENT.landingMode.get()==com.chaosthedude.explorerscompass.unified.data.LandingMode.DEFAULT && (s==TeleportStatusPacket.State.NEEDS_CONFIRMATION || s==TeleportStatusPacket.State.FAILED_RETRYABLE);}
    public static boolean busy(ItemStack stack) {var s=state(stack);return s==TeleportStatusPacket.State.PREPARING || s==TeleportStatusPacket.State.FALLBACK_PREPARING;}
    public static net.minecraft.network.chat.Component confirmation(ItemStack stack) {
        var entry=states.get(SearchService.token(stack));
        var message=net.minecraft.network.chat.Component.translatable("filters.teleport_confirm");
        if(entry!=null)message.append(" ").append(net.minecraft.network.chat.Component.translatable("filters.teleport_reason_"+entry.packet.reason()));
        return message;
    }
    public static void tick() {
        var mc=Minecraft.getInstance();if(mc.getConnection()==null || mc.getConnection().getConnection()!=connection){clear();return;}
        if(mc.player==null)return;
        var stack=com.chaosthedude.explorerscompass.util.ItemUtils.getHeldItem(mc.player,com.chaosthedude.explorerscompass.ExplorersCompass.explorersCompass);
        state(stack);
    }
    public static void clear(){states.clear();connection=null;}
}
