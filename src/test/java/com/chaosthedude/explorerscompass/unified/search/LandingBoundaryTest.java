package com.chaosthedude.explorerscompass.unified.search;

import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import com.chaosthedude.explorerscompass.unified.network.TeleportStatusPacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandingBoundaryTest {
    @Test void missingExplorerAdapterRejectsRecordedTargets() {
        var absent=new TeleportService.RecordedCompass(){};
        assertNull(absent.read(null,null));
        assertFalse(absent.allowedReturn(null,null));
        assertNull(absent.biome(null));
    }
    @Test void coreStatusMapsEveryExistingWireValue() {
        assertArrayEquals(Arrays.stream(TeleportStatusPacket.State.values()).map(Enum::name).toArray(),
                Arrays.stream(TeleportService.State.values()).map(Enum::name).toArray());
    }
    @Test void coreLandingPathsDoNotReferenceProductServices() throws Exception {
        var root=Path.of("src/main/java/com/chaosthedude/explorerscompass/unified/search");
        for(String file:List.of("TeleportService","LandingChecks","AetherHeightProbe","LandingSafety")) {
            String source=Files.readString(root.resolve(file+".java"));
            for(String forbidden:List.of("ExplorersCompass.","SearchService.","ConfigHandler.","TeleportStatusPacket",
                    "TeleportSucceededPacket","UnifiedNetwork","CreatureLanding.","CreatureConfig."))
                assertFalse(source.contains(forbidden),file+": "+forbidden);
        }
    }
}
