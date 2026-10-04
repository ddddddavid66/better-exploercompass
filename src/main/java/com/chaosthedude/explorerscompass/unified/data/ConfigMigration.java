package com.chaosthedude.explorerscompass.unified.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 只补统一配置缺少的键；旧扩展配置和旧统计始终保留。 */
public final class ConfigMigration {
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("asyncBiomeSearch", "true"); DEFAULTS.put("asyncStructureSearch", "true");
        DEFAULTS.put("searchThreads", "0"); DEFAULTS.put("maxSearchTimePerTick", "2");
        DEFAULTS.put("searchRadius", "40000"); DEFAULTS.put("maxStructureSamples", "25000000");
        DEFAULTS.put("maxBiomeSamples", "8000000"); DEFAULTS.put("biomeSampleSpacing", "32");
        DEFAULTS.put("biomeVerticalSampleSpacing", "64"); DEFAULTS.put("biomeDepthSampleInterval", "4");
    }
    private ConfigMigration() {}
    public static boolean merge(Path common, Path oldExtension, Consumer<String> report) {
        try {
            String text = Files.exists(common) ? Files.readString(common, StandardCharsets.UTF_8) : "";
            var block = Pattern.compile("(?ms)^\\s*\\[Search]\\s*\\R(.*?)(?=^\\s*\\[|\\z)").matcher(text);
            boolean hasBlock = block.find(); String current = hasBlock ? block.group(1) : "";
            if ("1".equals(value(current, "configVersion"))
                    && DEFAULTS.keySet().stream().allMatch(key -> value(current, key) != null)) return false;
            String legacy = Files.exists(oldExtension) ? Files.readString(oldExtension, StandardCharsets.UTF_8) : "";
            StringBuilder fields = new StringBuilder(current.replaceAll("(?m)^\\s*configVersion\\s*=.*(?:\\R|$)", "").stripTrailing());
            for (var entry : DEFAULTS.entrySet()) {
                if (value(current, entry.getKey()) != null) continue;
                String imported = value(legacy, entry.getKey());
                fields.append('\n').append(entry.getKey()).append(" = ").append(imported == null ? entry.getValue() : imported);
            }
            fields.append("\nconfigVersion = 1\n");
            String merged = hasBlock ? text.substring(0, block.start(1)) + fields + text.substring(block.end(1))
                    : text.stripTrailing() + "\n\n[Search]\n" + fields;
            AtomicFiles.backup(common); AtomicFiles.write(common, merged.getBytes(StandardCharsets.UTF_8));
            report.accept("已合并统一版搜索配置；旧配置保留，原 common 配置已备份。");
            return true;
        } catch (IOException | RuntimeException error) { report.accept("配置迁移失败，原文件保留: " + error.getMessage()); return false; }
    }
    private static String value(String text, String key) {
        var match = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*(true|false|[+-]?[0-9][0-9_]*)\\s*(?:#.*)?$").matcher(text);
        return match.find() ? match.group(1) : null;
    }
    public static boolean importState(Path config, Consumer<String> report) {
        Path original = config.resolve("explorerscompassfilters/client-state.json");
        Path destination = config.resolve("explorerscompass/client-state.json");
        if (Files.exists(destination) || !Files.exists(original)) return false;
        try {
            byte[] bytes = Files.readAllBytes(original);
            AtomicFiles.backup(original); AtomicFiles.write(destination, bytes);
            report.accept("已导入旧扩展次数和选择；旧统计文件保留。"); return true;
        } catch (IOException error) { report.accept("统计导入失败，旧文件保留: " + error.getMessage()); return false; }
    }
}
