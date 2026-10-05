package com.chaosthedude.explorerscompass.unified.data;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpgradeAndReceiptTest {
    @TempDir Path directory;
    @Test void configurationMergePreservesPrecedenceAndOriginals() throws Exception {
        Path common=directory.resolve("common.toml"), old=directory.resolve("old.toml");
        String previous="[General]\nallowTeleport = false\n[Search]\nsearchRadius = 12345\n";
        String legacy="asyncStructureSearch = false\nmaxBiomeSamples = 99_000_000 # custom\nsearchRadius = 88888\n";
        Files.writeString(common,previous);Files.writeString(old,legacy);
        assertTrue(ConfigMigration.merge(common,old,ignored->{}));
        String merged=Files.readString(common);
        assertTrue(merged.contains("searchRadius = 12345"));assertFalse(merged.contains("88888"));
        assertTrue(merged.contains("allowTeleport = false"));assertTrue(merged.contains("maxBiomeSamples = 99_000_000"));
        assertTrue(merged.contains("asyncStructureSearch = false"));assertEquals(legacy,Files.readString(old));
        assertFalse(ConfigMigration.merge(common,old,ignored->{}));
        try(var files=Files.list(directory)){assertTrue(files.anyMatch(file->file.toString().endsWith(".bak")));}
    }
    @Test void newConfigurationHasFourtyThousandRangeAndQuotaDefaults() throws Exception {
        Path common=directory.resolve("new.toml");
        assertTrue(ConfigMigration.merge(common,directory.resolve("missing.toml"),ignored->{}));
        String text=Files.readString(common);
        assertTrue(text.contains("searchRadius = 40000"));assertTrue(text.contains("maxStructureSamples = 25000000"));
        assertTrue(text.contains("maxBiomeSamples = 8000000"));
    }
    @Test void staleMarkerIsReplacedAndMissingFieldsAreImported() throws Exception {
        Path common=directory.resolve("partial.toml"), old=directory.resolve("old.toml");
        Files.writeString(common,"[Search]\nconfigVersion = 0\nsearchRadius = 55555\n");
        Files.writeString(old,"asyncBiomeSearch = false\n");
        assertTrue(ConfigMigration.merge(common,old,ignored->{}));
        String text=Files.readString(common);
        assertTrue(text.contains("searchRadius = 55555"));assertTrue(text.contains("asyncBiomeSearch = false"));
        assertEquals(1,text.lines().filter(line->line.stripLeading().startsWith("configVersion")).count());
        assertFalse(text.contains("configVersion = 0"));
        Files.writeString(common,"[Search]\nconfigVersion = 1\nsearchRadius = 55555\n");
        assertTrue(ConfigMigration.merge(common,old,ignored->{}));
        assertTrue(Files.readString(common).contains("asyncBiomeSearch = false"));
    }
    @Test void importsStateByteForByteAndNeverOverwritesNewFile() throws Exception {
        Path old=directory.resolve("explorerscompassfilters/client-state.json");Files.createDirectories(old.getParent());
        String json="{\"version\":2,\"environments\":{}}";Files.writeString(old,json);
        assertTrue(ConfigMigration.importState(directory,ignored->{}));
        Path current=directory.resolve("explorerscompass/client-state.json");assertEquals(json,Files.readString(current));
        Files.writeString(current,"new-state");assertFalse(ConfigMigration.importState(directory,ignored->{}));
        assertEquals("new-state",Files.readString(current));assertEquals(json,Files.readString(old));
    }
    @Test void receiptsDeduplicateEventsButAllowRepeatedSuccessfulTeleports() {
        EventLedger ledger = new EventLedger(); UUID event = UUID.randomUUID();
        assertTrue(ledger.first(event)); assertFalse(ledger.first(event));
        assertTrue(ledger.first(UUID.randomUUID())); ledger.clear(); assertTrue(ledger.first(event));
    }
    @Test void spatialExclusionMatchesLinearRuleIncludingNegativeBoundaries() {
        Random random = new Random(42);
        var points = new java.util.ArrayList<ExclusionIndex.Point>();
        for (int i = 0; i < 2000; i++) points.add(new ExclusionIndex.Point(random.nextInt(8000) - 4000, random.nextInt(8000) - 4000));
        ExclusionIndex index = new ExclusionIndex(points);
        for (int i = 0; i < 2000; i++) {
            int x = random.nextInt(10000) - 5000, z = random.nextInt(10000) - 5000;
            boolean expected = points.stream().anyMatch(point -> {
                long dx = (long)x - point.x(), dz = (long)z - point.z(); return dx * dx + dz * dz < 512L * 512;
            });
            assertEquals(expected, index.excludes(x, z));
        }
        ExclusionIndex origin = new ExclusionIndex(List.of(new ExclusionIndex.Point(0, 0)));
        assertTrue(origin.excludes(-511, 0)); assertFalse(origin.excludes(-512, 0));
    }
}
