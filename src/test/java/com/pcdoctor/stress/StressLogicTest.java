package com.pcdoctor.stress;

import com.pcdoctor.core.Report;
import com.pcdoctor.core.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StressLogicTest {

    @Test
    void cpuWorkloadIsDeterministic() {
        assertEquals(CpuStressTest.workload(), CpuStressTest.workload());
    }

    @Test
    void memoryBlockVerifiesCleanlyForEveryPattern() {
        long[] block = new long[4096];
        for (int pass = 0; pass < 8; pass++) {
            assertEquals(0, MemoryStressTest.testBlock(block, 3, pass));
        }
    }

    @Test
    void shortStressRunsReportNoErrors(@TempDir Path dir) throws Exception {
        Report report = new Report();
        new CpuStressTest(2).run(Duration.ofMillis(300), report);
        new MemoryStressTest(64).run(Duration.ofMillis(300), report);
        new DiskStressTest(dir, 16).run(Duration.ofMillis(100), report);
        assertEquals(0, report.count(Severity.CRITICAL));
        assertEquals(3, report.count(Severity.OK));
    }
}
