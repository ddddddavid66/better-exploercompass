package com.chaosthedude.explorerscompass.unified.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 本地历史以注册 ID 保存，语言切换和更换指南针不会改变计数。 */
public final class StateStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private final Path file;
    private final Consumer<String> report;
    private Document document = new Document();
    private boolean writable = true;

    public StateStore(Path file, Consumer<String> report) {
        this.file = file.toAbsolutePath();
        this.report = report;
        load();
    }

    public synchronized long count(String environment, String structure) {
        Environment state = document.environments.get(environment);
        return state == null ? 0 : state.counts.getOrDefault(structure, 0L);
    }

    public synchronized String biome(String environment) {
        Environment state = document.environments.get(environment);
        return state == null ? "" : state.biome;
    }

    public synchronized String dimension(String environment) {
        Environment state = document.environments.get(environment);
        return state == null ? "" : state.dimension;
    }

    public synchronized void setDimension(String environment, String dimension) {
        if (!dimension.isEmpty() && !ID.matcher(dimension).matches()) throw new IllegalArgumentException("Invalid dimension ID");
        Environment state = state(environment);
        if (state.dimension.equals(dimension)) return;
        state.dimension = dimension; save();
    }

    public synchronized long biomeCount(String environment, String biome) {
        Environment state = document.environments.get(environment);
        return state == null ? 0 : state.biomeCounts.getOrDefault(biome, 0L);
    }

    public synchronized void recordBiome(String environment, String biome) {
        if (!ID.matcher(biome).matches()) return;
        Map<String, Long> counts = state(environment).biomeCounts;
        long previous = counts.getOrDefault(biome, 0L);
        counts.put(biome, previous == Long.MAX_VALUE ? previous : previous + 1); save();
    }

    public synchronized void setBiome(String environment, String biome) {
        if (!biome.isEmpty() && !ID.matcher(biome).matches()) {
            throw new IllegalArgumentException("Invalid biome ID");
        }
        Environment state = state(environment);
        if (state.biome.equals(biome)) return;
        state.biome = biome;
        save();
    }

    public synchronized void record(String environment, Collection<String> structures) {
        Environment state = state(environment);
        boolean changed = false;
        // 一个成功事件只写命中目标；重复事件在连接级 EventLedger 中去重。
        for (String id : new LinkedHashSet<>(structures)) {
            if (id == null || !ID.matcher(id).matches()) continue;
            long previous = state.counts.getOrDefault(id, 0L);
            state.counts.put(id, previous == Long.MAX_VALUE ? previous : previous + 1);
            changed = true;
        }
        if (changed) save();
    }

    private Environment state(String key) {
        if (!key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid environment key");
        return document.environments.computeIfAbsent(key, ignored -> new Environment());
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            Document loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Document.class);
            validate(loaded);
            document = loaded;
            // 版本 1 的结构历史原样保留；新增字段由缺省初始化提供。
            if (document.version == 1) { document.version = 2; save(); }
        } catch (IOException | RuntimeException exception) {
            Path backup = file.resolveSibling("client-state.corrupt-" + System.currentTimeMillis()
                    + "-" + UUID.randomUUID() + ".json");
            try {
                Files.move(file, backup);
                report.accept("历史文件损坏，已保留为 " + backup.getFileName() + ": " + exception.getMessage());
            } catch (IOException backupFailure) {
                // 无法保留原文件时停止写入，不能用空历史覆盖它。
                writable = false;
                report.accept("历史文件无法读取或备份，暂时只在内存计数: " + backupFailure.getMessage());
            }
        }
    }

    private static void validate(Document loaded) {
        if (loaded == null || (loaded.version != 1 && loaded.version != 2) || loaded.environments == null) {
            throw new IllegalArgumentException("Unsupported history document");
        }
        for (Map.Entry<String, Environment> entry : loaded.environments.entrySet()) {
            Environment state = entry.getValue();
            if (!entry.getKey().matches("[0-9a-f]{64}") || state == null || state.biome == null
                    || state.counts == null || state.biomeCounts == null || state.dimension == null
                    || (!state.dimension.isEmpty() && !ID.matcher(state.dimension).matches())
                    || (!state.biome.isEmpty() && !ID.matcher(state.biome).matches())) {
                throw new IllegalArgumentException("Invalid environment state");
            }
            for (Map.Entry<String, Long> count : state.counts.entrySet()) {
                if (!ID.matcher(count.getKey()).matches() || count.getValue() == null || count.getValue() < 0) {
                    throw new IllegalArgumentException("Invalid search counter");
                }
            }
            for (var count : state.biomeCounts.entrySet())
                if (!ID.matcher(count.getKey()).matches() || count.getValue() == null || count.getValue() < 0)
                    throw new IllegalArgumentException("Invalid biome counter");
        }
    }

    private void save() {
        if (!writable) return;
        try {
            AtomicFiles.write(file, GSON.toJson(document).getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            report.accept("搜索历史写入失败，内存中的统计仍然有效: " + exception.getMessage());
        }
    }

    private static final class Document {
        private int version = 2;
        private Map<String, Environment> environments = new LinkedHashMap<>();
    }

    private static final class Environment {
        private String biome = "";
        private String dimension = "";
        private Map<String, Long> counts = new LinkedHashMap<>();
        private Map<String, Long> biomeCounts = new LinkedHashMap<>();
    }
}
