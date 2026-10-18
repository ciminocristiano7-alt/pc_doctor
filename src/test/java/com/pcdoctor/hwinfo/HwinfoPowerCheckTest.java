package com.pcdoctor.hwinfo;

import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;
import com.pcdoctor.core.Severity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HwinfoPowerCheckTest {

    private static final String HEADER = "﻿Date,Time,\"+12V [V]\",\"+5V [V]\",\"GPU 12VHPWR Voltaggi [V]\",\"GPU Potenza [W]\",\"CPU PPT [W]\",";

    @Test
    void parsesSamplesAndIgnoresTrailer() {
        HwinfoLog log = HwinfoLog.parse(HEADER + "\n"
                + "18.10.2026,18:24:9.871,12.076,5.140,12.144,9.3,52.9,\n"
                + "18.10.2026,18:24:11.873,12.076,5.100,11.957,223.4,57.2,\n"
                + "Date,Time,\"+12V [V]\",\"+5V [V]\",\n"
                + ",,\"Nuvoton\",\"Nuvoton\",\n");
        assertEquals(2, log.size());
        assertTrue(log.closedRegularly());
        assertEquals(1, log.start().getSecond() == 9 ? 1 : 0);
        assertEquals(871_000_000, log.start().getNano());
        assertEquals(2.0, log.averageIntervalSeconds(), 0.01);
        assertEquals(12.076, log.value(0, log.column("^\\+12V").getAsInt()).orElseThrow());
    }

    @Test
    void detectsLogInterruptedUnderLoad() {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (int s = 0; s < 10; s++) {
            boolean heavy = s >= 5;
            csv.append(String.format("18.10.2026,18:24:%d.000,%s,5.10,%s,%s,%s,\n", s,
                    heavy ? "11.98" : "12.08", heavy ? "11.95" : "12.14", heavy ? "318" : "10", heavy ? "125" : "45"));
        }
        HwinfoLog log = HwinfoLog.parse(csv.toString());
        assertFalse(log.closedRegularly());

        Report report = new Report();
        new HwinfoPowerCheck(Path.of("test.csv"), 850).analyze(log, report, false);
        assertEquals(0, report.count(Severity.CRITICAL));
        assertTrue(report.findings().stream().map(Finding::title).anyMatch(t -> t.contains("non è stato chiuso")));
        assertTrue(report.findings().stream().map(Finding::title).anyMatch(t -> t.startsWith("+12V (scheda madre) nelle tolleranze")));
    }

    @Test
    void flagsVoltageOutOfAtxRange() {
        String csv = HEADER + "\n"
                + "18.10.2026,20:00:0.000,12.05,5.10,12.10,300,120,\n"
                + "18.10.2026,20:00:1.000,11.31,5.10,12.10,320,125,\n"
                + "Date,Time,\n";
        Report report = new Report();
        new HwinfoPowerCheck(Path.of("test.csv"), 850).analyze(HwinfoLog.parse(csv), report, false);
        assertTrue(report.findings().stream()
                .anyMatch(f -> f.severity() == Severity.CRITICAL && f.title().startsWith("+12V (scheda madre) fuori")));
    }
}
