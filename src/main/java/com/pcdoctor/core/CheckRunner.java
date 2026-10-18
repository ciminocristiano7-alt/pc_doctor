package com.pcdoctor.core;

import java.io.PrintStream;
import java.util.List;

/** Esegue una serie di controlli in sequenza; l'errore di un controllo non blocca gli altri. */
public final class CheckRunner {

    private CheckRunner() {
    }

    public static void runAll(List<DiagnosticCheck> checks, Report report, PrintStream progress) {
        int i = 0;
        for (DiagnosticCheck check : checks) {
            i++;
            progress.printf("[%d/%d] %s...%n", i, checks.size(), check.name());
            long start = System.currentTimeMillis();
            try {
                check.run(report);
            } catch (Exception e) {
                report.add(Finding.warning("Errore", "Controllo non completato: " + check.name(),
                        e.getClass().getSimpleName() + ": " + e.getMessage(), ""));
            }
            progress.printf("      fatto in %.1f s%n", (System.currentTimeMillis() - start) / 1000.0);
        }
    }
}
