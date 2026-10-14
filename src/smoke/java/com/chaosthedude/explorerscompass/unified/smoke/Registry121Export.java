package com.chaosthedude.explorerscompass.unified.smoke;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.*;
import net.minecraftforge.registries.ForgeRegistries;

/** 仅隔离验收使用。创建未加入世界的对象检查类层级，不召唤、不tick、不改生成规则。 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid="filterschecks",value=Dist.CLIENT)
public final class Registry121Export {
    private static int phase,at;
    private static List<net.minecraft.resources.ResourceLocation> keys;
    private static final JsonArray rows=new JsonArray();
    private static volatile boolean finished;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!Boolean.getBoolean("unified.catalog121.export") || event.phase!=TickEvent.Phase.END || phase==3)return;
        var mc=Minecraft.getInstance();
        if(phase==0) {
            if(mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
            if(!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen))return;
            mc.options.renderDistance().set(3);mc.options.simulationDistance().set(5);
            mc.createWorldOpenFlows().loadLevel(mc.screen,"creature121-save");phase=1;return;
        }
        var server=mc.getSingleplayerServer();if(server==null || mc.player==null)return;
        if(phase==1){phase=2;server.execute(()->keys=ForgeRegistries.ENTITY_TYPES.getKeys().stream().sorted().toList());}
        if(keys!=null && !finished)server.execute(()->exportStep(server));
        if(finished){phase=3;mc.stop();}
    }
    private static void exportStep(MinecraftServer server) {
        if(finished)return;
        long until=System.nanoTime()+2_000_000L;
        while(at<keys.size() && System.nanoTime()<until) {
            var id=keys.get(at++);var type=ForgeRegistries.ENTITY_TYPES.getValue(id);var row=new JsonObject();row.addProperty("entity",id.toString());row.addProperty("category",type.getCategory().getName());row.addProperty("translation_key",type.getDescriptionId());
            try {
                Entity e=type.create(server.overworld());
                if(e==null){row.addProperty("classification","pending");row.addProperty("reason","factory returned null");}
                else {row.addProperty("entity_class",e.getClass().getName());row.addProperty("classification",e instanceof LivingEntity && !(e instanceof Player) && !(e instanceof ArmorStand)?"creature":"non_creature");row.addProperty("reason","isolated unspawned instance class hierarchy");}
            }catch(Throwable error){row.addProperty("classification","pending");row.addProperty("reason",error.toString());}
            rows.add(row);
        }
        if(at<keys.size())return;
        try {
            var root=new JsonObject();root.addProperty("minecraft","1.20.1");root.addProperty("registered_total",keys.size());root.add("entities",rows);
            var mods=new JsonArray();for(var m:ModList.get().getMods()){var n=new JsonObject();n.addProperty("id",m.getModId());n.addProperty("version",m.getVersion().toString());n.addProperty("name",m.getDisplayName());mods.add(n);}root.add("mods",mods);
            var dimensions=new JsonArray();var biomes=new JsonObject();
            for(var level:server.getAllLevels()) {
                var d=new JsonObject();d.addProperty("id",level.dimension().location().toString());d.addProperty("source",level.getChunkSource().getGenerator().getBiomeSource().getClass().getName());var ids=new JsonArray();
                for(var holder:level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
                    var key=holder.unwrapKey();if(key.isEmpty())continue;var id=key.get().location().toString();ids.add(id);
                    if(biomes.has(id))continue;var b=new JsonObject();var spawns=new JsonArray();
                    for(var category:MobCategory.values())for(var spawn:holder.value().getMobSettings().getMobs(category).unwrap()){var n=new JsonObject();n.addProperty("entity",ForgeRegistries.ENTITY_TYPES.getKey(spawn.type).toString());n.addProperty("weight",spawn.getWeight().asInt());n.addProperty("min",spawn.minCount);n.addProperty("max",spawn.maxCount);spawns.add(n);}b.add("spawns",spawns);biomes.add(id,b);
                }d.add("biomes",ids);dimensions.add(d);
            }
            root.add("dimensions",dimensions);root.add("biomes",biomes);
            var output=Path.of(System.getProperty("unified.catalog121.output","registry-snapshot.json"));Files.createDirectories(output.toAbsolutePath().getParent());Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(root),StandardCharsets.UTF_8);
            System.out.println("REGISTRY121 EXPORTED "+keys.size()+" "+output);finished=true;
        }catch(Exception error){error.printStackTrace();finished=true;}
    }
}
