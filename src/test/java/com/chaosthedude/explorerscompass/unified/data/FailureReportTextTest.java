package com.chaosthedude.explorerscompass.unified.data;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;

class FailureReportTextTest {
    @Test void onlyFinalFailuresAreReported(){
        for(String reason:new String[]{"range_exhausted","sample_limit","error","travel_failed"})assertTrue(FailureReportText.reportable(reason));
        for(String reason:new String[]{"found","cancelled","replaced","rejected","searching"})assertFalse(FailureReportText.reportable(reason));
    }
    @Test void utf8BudgetDoesNotSplitCharacters(){
        String report=FailureReportText.bounded("高中文🙂".repeat(20000));
        assertTrue(report.getBytes(StandardCharsets.UTF_8).length<=FailureReportText.MAX_BYTES);
        assertFalse(report.contains("�"));assertTrue(report.contains("截断"));
    }
    @Test void causesFramesAndSecretsAreBounded(){
        RuntimeException error=new RuntimeException("password=secret",new IllegalArgumentException("accessToken=private"));
        error.setStackTrace(java.util.stream.IntStream.range(0,100).mapToObj(i->new StackTraceElement("Example","method"+i,"X.java",i)).toArray(StackTraceElement[]::new));
        String text=FailureReportText.exception(error);
        assertFalse(text.contains("secret"));assertFalse(text.contains("private"));
        assertEquals(12,text.lines().filter(line->line.startsWith("  at ")).count());
    }
}
