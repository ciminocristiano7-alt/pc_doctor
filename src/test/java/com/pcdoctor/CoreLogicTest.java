package com.pcdoctor;

import com.pcdoctor.checks.events.BugCheckCatalog;
import com.pcdoctor.monitor.MonitorLogAnalyzer;
import com.pcdoctor.monitor.PeakTracker;
import com.pcdoctor.util.NvidiaSmi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreLogicTest {

    @Test
    void bugCheckCatalogClassifiesKnownCodes() {
        assertEquals(BugCheckCatalog.Area.GPU, BugCheckCatalog.lookup(0x116).area());
        assertEquals(BugCheckCatalog.Area.POWER, BugCheckCatalog.lookup(0).area());
        assertEquals(BugCheckCatalog.Area.UNKNOWN, BugCheckCatalog.lookup(0xDEAD).area());
        assertTrue(BugCheckCatalog.ntStatus(0xffffffffc000009aL).startsWith("STATUS_INSUFFICIENT_RESOURCES"));
    }

    @Test
    void throttleReasonsDecodeMask() {
        assertTrue(NvidiaSmi.throttleReasons("0x0000000000000001").isEmpty());
        List<String> reasons = NvidiaSmi.throttleReasons("0x0000000000000088");
        assertEquals(2, reasons.size());
        assertTrue(reasons.get(0).startsWith("HW slowdown"));
    }

    @Test
    void peakTrackerKeepsMaximumAndIgnoresText() {
        PeakTracker peaks = new PeakTracker();
        peaks.accept(Map.of("gpu_temp_c", "70", "gpu_pstate", "P0"));
        peaks.accept(Map.of("gpu_temp_c", "82.5", "gpu_pstate", "P2"));
        assertEquals(82.5, peaks.peak("gpu_temp_c"));
        assertTrue(Double.isNaN(peaks.peak("gpu_pstate")));
    }

    @Test
    void monitorLogRoundTripAndInterruptedSession(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("monitor-1.csv");
        Files.writeString(csv, "time;gpu_temp_c;gpu_power_w\n2026-10-18 10:00:00;60;200\n2026-10-18 10:00:01;75;340\n");
        MonitorLogAnalyzer log = MonitorLogAnalyzer.load(csv);
        assertEquals(2, log.rows().size());
        assertEquals(340.0, log.peaks().peak("gpu_power_w"));
        assertEquals(1, log.lastRows(1).size());

        assertTrue(MonitorLogAnalyzer.interruptedSession(dir).isEmpty());
        MonitorLogAnalyzer.writeMarker(dir, csv);
        assertEquals(csv.toAbsolutePath(), MonitorLogAnalyzer.interruptedSession(dir).orElseThrow());
        MonitorLogAnalyzer.clearMarker(dir);
        assertTrue(MonitorLogAnalyzer.interruptedSession(dir).isEmpty());
    }
}
