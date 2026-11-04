package com.chaosthedude.explorerscompass.unified.search;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceCapability128Test {
    @Test void temporaryFailureRetriesOnlyAfterDeadline() {
        var pending=new BiomeSourceSafety.Result(BiomeSourceSafety.Status.NOT_READY,"initializing","source");
        assertFalse(pending.allowed());
        assertFalse(BiomeSourceSafety.shouldRefresh(pending,119,120));
        assertTrue(BiomeSourceSafety.shouldRefresh(pending,120,120));
    }
    @Test void unsupportedCannotBecomeSafeMerelyByWaiting() {
        var rejected=new BiomeSourceSafety.Result(BiomeSourceSafety.Status.UNSUPPORTED,"unknown provider","source");
        assertFalse(rejected.allowed());
        assertFalse(BiomeSourceSafety.shouldRefresh(rejected,100000,20));
    }
    @Test void safeSnapshotRemainsFrozenAndNewContextRequiresAudit() {
        var safe=new BiomeSourceSafety.Result(BiomeSourceSafety.Status.SAFE,"","source");
        assertTrue(safe.allowed());
        assertFalse(BiomeSourceSafety.shouldRefresh(safe,100000,20));
        assertTrue(BiomeSourceSafety.shouldRefresh(null,0,20));
    }
}
