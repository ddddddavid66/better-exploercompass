package com.chaosthedude.explorerscompass.unified.creature;

import java.nio.file.*;
import java.util.ArrayList;
import com.electronwill.nightconfig.toml.TomlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CreatureConfigMigrationTest {
    @TempDir Path config;
    @Test void importsOnlyCreatureSettingsAndKeepsOriginals() throws Exception {
        String common="[General]\nallowTeleport=false\nallowCreaturePreExplore=false\nstructureBlacklist=['example:foo']\n"
                +"[Search]\nsearchRadius=160000\nmaxBiomeSamples=5120000000\nsearchThreads=3\n";
        String client="creatureRounds=9\ncreatureDots=false\ncreatureDimension='aether:the_aether'\n"
                +"[Client]\nlandingMode='SAFE_ONLY'\nsearchLogEnabled=true\n";
        var originalCommon=config.resolve("explorerscompass-common.toml");
        var originalClient=config.resolve("explorerscompass-client.toml");
        Files.writeString(originalCommon,common);Files.writeString(originalClient,client);
        var warnings=new ArrayList<String>();CreatureConfig.migrate(config,warnings::add);
        assertTrue(warnings.isEmpty());
        assertEquals(common,Files.readString(originalCommon));assertEquals(client,Files.readString(originalClient));
        var migrated=new TomlParser().parse(Files.readString(config.resolve("creaturecompass-common.toml")));
        assertEquals(false,(Boolean)migrated.get("General.allowTeleport"));
        assertEquals(5120000000L,((Number)migrated.get("Search.maxBiomeSamples")).longValue());
        assertFalse(migrated.contains("General.structureBlacklist"));assertFalse(migrated.contains("Search.searchThreads"));
        var view=new TomlParser().parse(Files.readString(config.resolve("creaturecompass-client.toml")));
        assertEquals("SAFE_ONLY",(String)view.get("Client.landingMode"));
        assertEquals("aether:the_aether",(String)view.get("creatureDimension"));assertFalse(view.contains("Client.searchLogEnabled"));
    }
    @Test void existingProductConfigTakesPrecedence() throws Exception {
        Files.writeString(config.resolve("explorerscompass-client.toml"),"creatureRounds=4\n");
        var product=config.resolve("creaturecompass-client.toml");Files.writeString(product,"creatureRounds=12\n");
        CreatureConfig.migrate(config,warning->fail(warning));
        assertEquals("creatureRounds=12\n",Files.readString(product));
    }
}
