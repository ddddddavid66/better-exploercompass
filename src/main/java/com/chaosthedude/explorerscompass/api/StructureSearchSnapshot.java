package com.chaosthedude.explorerscompass.api;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import com.david.compass.runtime.game.search.SearchCandidate;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
/** Server-authoritative integration snapshot v1. */
@Mod.EventBusSubscriber(modid="explorerscompass")
public final class StructureSearchSnapshot {
 public static final String KEY="ExplorerStructureSnapshotV1";
 public static CompoundTag getSnapshot(ServerPlayer player){return player.getPersistentData().getCompound(KEY).copy();}
 public static void publish(ServerPlayer p,UUID revision,String state,String dimension,SearchCandidate result){
  var t=new CompoundTag();t.putInt("Version",1);t.putUUID("Revision",revision);t.putString("State",state);t.putString("Dimension",dimension);
  if(state.equals("FOUND")&&result!=null){t.putString("Structure",result.key().toString());t.putInt("StartX",result.chunk().x);t.putInt("StartZ",result.chunk().z);t.putLong("Anchor",result.position().asLong());}
  p.getPersistentData().put(KEY,t);
 }
 @SubscribeEvent public static void clonePlayer(PlayerEvent.Clone e){if(e.getOriginal().getPersistentData().contains(KEY))e.getEntity().getPersistentData().put(KEY,e.getOriginal().getPersistentData().getCompound(KEY).copy());}
 @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e){var t=e.getEntity().getPersistentData().getCompound(KEY);if(t.getString("State").equals("SEARCHING"))t.putString("State","CANCELLED");}
}
