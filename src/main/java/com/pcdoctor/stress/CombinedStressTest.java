package com.pcdoctor.stress;

import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Esegue più stress test contemporaneamente per caricare l'intero sistema (CPU + RAM + disco):
 * mette alla prova anche l'alimentatore e il raffreddamento del case nel loro insieme.
 */
public class CombinedStressTest implements StressTest {

    private final List<StressTest> tests;

    public CombinedStressTest(List<StressTest> tests) {
        this.tests = tests;
    }

    @Override
    public String name() {
        return "Stress combinato (" + tests.stream().map(StressTest::name).toList() + ")";
    }

    @Override
    public void run(Duration duration, Report report) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tests.size());
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (StressTest test : tests) {
                futures.add(pool.submit(() -> {
                    test.run(duration, report);
                    return null;
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get();
                } catch (Exception e) {
                    report.add(Finding.critical("Stress", tests.get(i).name() + " interrotto da un errore",
                            String.valueOf(e.getCause() != null ? e.getCause() : e), ""));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
