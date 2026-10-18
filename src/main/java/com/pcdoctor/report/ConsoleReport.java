package com.pcdoctor.report;

import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;
import com.pcdoctor.core.Severity;

import java.io.PrintStream;
import java.util.Map;

/** Stampa il report a console in formato testuale. */
public final class ConsoleReport {

    private ConsoleReport() {
    }

    public static void print(Report report, PrintStream out, boolean withData) {
        if (withData) {
            for (Map.Entry<String, Map<String, String>> section : report.sections().entrySet()) {
                out.println();
                out.println("== " + section.getKey() + " ==");
                section.getValue().forEach((k, v) -> out.println("  " + k + ": " + v));
            }
        }
        out.println();
        out.println("================ RISULTATI ================");
        for (Finding f : report.findingsBySeverity()) {
            out.printf("[%s] %s - %s%n", f.severity().label(), f.category(), f.title());
            if (!f.detail().isBlank()) {
                out.println("      " + f.detail());
            }
            if (!f.recommendation().isBlank()) {
                out.println("      -> " + f.recommendation());
            }
        }
        out.println();
        out.printf("Critici: %d  Attenzione: %d  Info: %d  OK: %d%n",
                report.count(Severity.CRITICAL), report.count(Severity.WARNING),
                report.count(Severity.INFO), report.count(Severity.OK));
    }
}
