package com.chaosthedude.explorerscompass.benchmark;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 基准只反射正常公开请求入口，绝不手动推进 worker。不可观测指标返回 N/A。 */
final class EngineAccess {
    private static final String PREFIX = "com.chaosthedude.explorerscompass.";
    private final Class<?> unified;
    private Object session;
    private static boolean observing;
    EngineAccess() throws Exception {
        Class<?> type;
        try { type = Class.forName(PREFIX + "unified.search.SearchService"); }
        catch (ClassNotFoundException absent) { type = null; }
        unified = type;
    }
    boolean isUnified() { return unified != null; }
    void configure() throws Exception {
        if(!observing) {
            net.minecraftforge.fml.ModList.get().getModContainerById("explorerscompass").ifPresent(container->{
                if(container instanceof net.minecraftforge.fml.javafmlmod.FMLModContainer mod)
                    mod.getEventBus().addListener((net.minecraftforge.fml.event.config.ModConfigEvent.Reloading event)->
                        System.out.println("COMPASSBENCH config-reload "+System.nanoTime()+" "+event.getConfig().getFileName()));
            });observing=true;
        }
        Object general = Class.forName(PREFIX + "config.ConfigHandler").getField("GENERAL").get(null);
        for (String name : new String[]{"defaultXpLevels", "compassDurability", "searchRequestCooldownMillis"}) setOptional(general, name, 0);
        setOptional(general, "allowTeleport", false);
        if (unified == null) {
            setOptional(general, "maxRadius", 40000); setOptional(general, "maxSamples", 25000000);
            setOptional(general, "maxBiomeSamples", 8000000); setOptional(general, "maxSearchTimePerTick", 2);
            setOptional(general, "biomeSampleSpacing", 32); setOptional(general, "biomeVerticalSampleSpacing", 64);
            setOptional(general, "biomeDepthSampleInterval", 4);
            setOptional(general, "asyncStructureSearch", true); setOptional(general, "asyncBiomeSearch", true);
        } else {
            Class<?> config = Class.forName(PREFIX + "unified.search.SearchConfig");
            int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
            for (String name : new String[]{"ASYNC_BIOMES", "ASYNC_STRUCTURES"}) set(config.getField(name).get(null), true);
            String[] names = {"RADIUS", "STRUCTURE_SAMPLES", "BIOME_SAMPLES", "TICK_MILLIS", "THREADS", "SPACING", "VERTICAL", "DEPTH_INTERVAL"};
            int[] values = {40000, 25000000, 8000000, 2, threads, 32, 64, 4};
            for (int i = 0; i < names.length; i++) set(config.getField(names[i]).get(null), values[i]);
        }
    }
    private static void setOptional(Object owner, String field, Object value) throws Exception {
        try { set(owner.getClass().getField(field).get(owner), value); } catch (NoSuchFieldException optional) { }
    }
    private static void set(Object config, Object value) throws Exception { config.getClass().getMethod("set", Object.class).invoke(config, value); }
    boolean start(ServerPlayer player, ItemStack stack, ResourceLocation key, boolean biome) throws Exception {
        if (unified == null) {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object type = Enum.valueOf((Class)Class.forName(PREFIX + "util.SearchTarget"), biome ? "BIOME" : "STRUCTURE");
            stack.getItem().getClass().getMethod("searchForTargets", Level.class, Player.class, type.getClass(),
                    java.util.List.class, BlockPos.class, ItemStack.class).invoke(stack.getItem(), player.level(), player,
                    type, java.util.List.of(key), player.blockPosition(), stack);
            return stack.getOrCreateTag().getInt("State") == 1;
        }
        unified.getMethod("clearCache", UUID.class).invoke(null, player.getUUID());
        boolean accepted = (boolean)(biome
                ? unified.getMethod("startBiome", ServerPlayer.class, ItemStack.class, ResourceLocation.class).invoke(null, player, stack, key)
                : unified.getMethod("requestStructures", ServerPlayer.class, ItemStack.class, ResourceLocation.class,
                    boolean.class, boolean.class, ResourceLocation.class).invoke(null, player, stack, key, false, false, null));
        session = ((Map<?,?>)field(unified, null, "sessions")).get(player.getUUID());
        return accepted;
    }
    void cancel(ServerPlayer player) throws Exception {
        if (unified == null) Class.forName(PREFIX + "worker.SearchService").getMethod("stopSearch", Player.class).invoke(null, player);
        else {
            unified.getMethod("cancel", ServerPlayer.class, boolean.class).invoke(null, player, true);
            Class.forName(PREFIX + "unified.search.TeleportService").getMethod("cancel", UUID.class).invoke(null, player.getUUID());
        }
    }
    double mainNanos() throws Exception { return metric("mainNanos", "priorMain", true); }
    double metric(String name, String prior, boolean member) throws Exception {
        if (session == null || unified == null) return Double.NaN;
        Object job = field(session.getClass(), session, "job");
        Object current = member ? job.getClass().getField(name).get(job) : job.getClass().getMethod(name).invoke(job);
        return ((Number)current).doubleValue() + (prior.isEmpty() ? 0 : ((Number)field(session.getClass(), session, prior)).doubleValue());
    }
    String mode() throws Exception {
        if (session == null) return "N/A";
        Object job = field(session.getClass(), session, "job"); return (String)job.getClass().getMethod("mode").invoke(job);
    }
    private static Object field(Class<?> type, Object owner, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
}
