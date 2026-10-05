package com.chaosthedude.explorerscompass.unified.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StateStoreTest {
    @TempDir Path temporary;
    private final String first = EnvironmentKey.of("account-a", "singleplayer", "C:/saves/world-a");
    private final String second = EnvironmentKey.of("account-a", "singleplayer", "C:/saves/world-b");

    @Test void countersAndSelectionPersistWithoutDuplicatingGroupKeys() throws Exception {
        Path file = temporary.resolve("config/client-state.json");
        StateStore store = new StateStore(file, fail -> fail(fail));
        assertEquals(0, store.count(first, "minecraft:stronghold"));
        store.record(first, List.of("minecraft:stronghold", "minecraft:stronghold", "minecraft:mineshaft"));
        store.record(first, List.of("minecraft:stronghold"));
        store.setBiome(first, "minecraft:plains");
        StateStore restored = new StateStore(file, fail -> fail(fail));
        assertEquals(2, restored.count(first, "minecraft:stronghold"));
        assertEquals(1, restored.count(first, "minecraft:mineshaft"));
        assertEquals("minecraft:plains", restored.biome(first));
        restored.setBiome(first, "");
        assertEquals("", new StateStore(file, fail -> fail(fail)).biome(first));
        try (var files = Files.list(file.getParent())) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test void accountsWorldsAndServersAreIsolated() {
        StateStore store = new StateStore(temporary.resolve("state.json"), fail -> fail(fail));
        store.record(first, List.of("example:tower"));
        assertEquals(0, store.count(second, "example:tower"));
        String account = EnvironmentKey.of("account-b", "singleplayer", "C:/saves/world-a");
        assertEquals(0, store.count(account, "example:tower"));
        assertNotEquals(first, EnvironmentKey.of("account-a", "multiplayer", "C:/saves/world-a"));
        assertEquals(EnvironmentKey.of("account-a", "multiplayer", "SERVER.EXAMPLE:25565"),
                EnvironmentKey.of("account-a", "multiplayer", "server.example:25565"));
    }

    @Test void malformedHistoryIsKeptAndNewHistoryWorks() throws Exception {
        Path file = temporary.resolve("client-state.json");
        Files.writeString(file, "{ broken history");
        List<String> reports = new ArrayList<>();
        StateStore store = new StateStore(file, reports::add);
        assertEquals(0, store.count(first, "example:tower"));
        store.record(first, List.of("example:tower"));
        assertEquals(1, new StateStore(file, fail -> fail(fail)).count(first, "example:tower"));
        try (var files = Files.list(temporary)) {
            Path backup = files.filter(path -> path.getFileName().toString().contains(".corrupt-")).findFirst().orElseThrow();
            assertEquals("{ broken history", Files.readString(backup));
        }
        assertEquals(1, reports.size());
    }

    @Test void unsupportedSchemaIsQuarantined() throws Exception {
        Path file = temporary.resolve("client-state.json");
        Files.writeString(file, "{\"version\":3,\"environments\":{}}");
        List<String> reports = new ArrayList<>();
        StateStore store = new StateStore(file, reports::add);
        assertEquals("", store.biome(first));
        assertFalse(reports.isEmpty());
        assertFalse(Files.exists(file));
    }

    @Test void invalidKeysAreIgnoredAndCountersDoNotOverflow() throws Exception {
        Path file = temporary.resolve("client-state.json");
        Files.writeString(file, "{\"version\":1,\"environments\":{\"" + first
                + "\":{\"biome\":\"\",\"counts\":{\"example:tower\":" + Long.MAX_VALUE + "}}}}");
        StateStore store = new StateStore(file, fail -> fail(fail));
        store.record(first, List.of("example:tower", "invalid key"));
        assertEquals(Long.MAX_VALUE, store.count(first, "example:tower"));
        assertEquals(0, store.count(first, "invalid key"));
    }

    @Test void diskWriteFailureDoesNotDiscardInMemoryCounts() throws Exception {
        Path blocker = temporary.resolve("not-a-directory");
        Files.writeString(blocker, "keep this file");
        List<String> reports = new ArrayList<>();
        StateStore store = new StateStore(blocker.resolve("client-state.json"), reports::add);
        store.record(first, List.of("example:tower"));
        assertEquals(1, store.count(first, "example:tower"));
        assertEquals("keep this file", Files.readString(blocker));
        assertFalse(reports.isEmpty());
    }

    @Test void versionOneMigratesWithoutLosingStructureCountsOrBiome() throws Exception {
        Path file = temporary.resolve("state.json");
        Files.writeString(file, "{\"version\":1,\"environments\":{\"" + first
                + "\":{\"biome\":\"minecraft:plains\",\"counts\":{\"example:tower\":7}}}}");
        StateStore store = new StateStore(file, message -> fail(message));
        assertEquals(7, store.count(first, "example:tower"));
        assertEquals("minecraft:plains", store.biome(first));
        assertEquals(0, store.biomeCount(first, "minecraft:plains"));
        assertEquals("", store.dimension(first));
        assertTrue(Files.readString(file).contains("\"version\": 2"));
    }

    @Test void biomeCountsAndDimensionPersistIndependently() {
        Path file = temporary.resolve("state.json");
        StateStore store = new StateStore(file, message -> fail(message));
        store.recordBiome(first, "minecraft:plains"); store.recordBiome(first, "minecraft:plains");
        store.record(first, List.of("minecraft:plains"));
        store.setDimension(first, "minecraft:the_nether");
        StateStore restored = new StateStore(file, message -> fail(message));
        assertEquals(2, restored.biomeCount(first, "minecraft:plains"));
        assertEquals(1, restored.count(first, "minecraft:plains"));
        assertEquals(0, restored.biomeCount(second, "minecraft:plains"));
        assertEquals("minecraft:the_nether", restored.dimension(first));
        restored.setDimension(first, ""); assertEquals(2, restored.biomeCount(first, "minecraft:plains"));
    }

    @Test void biomeCounterCannotOverflowAndRejectsInvalidIds() throws Exception {
        Path file = temporary.resolve("state.json");
        Files.writeString(file, "{\"version\":2,\"environments\":{\"" + first
                + "\":{\"biomeCounts\":{\"example:cave\":" + Long.MAX_VALUE + "}}}}");
        StateStore store = new StateStore(file, message -> fail(message));
        store.recordBiome(first, "example:cave"); store.recordBiome(first, "bad id");
        assertEquals(Long.MAX_VALUE, store.biomeCount(first, "example:cave"));
        assertEquals(0, store.biomeCount(first, "bad id"));
    }
}
