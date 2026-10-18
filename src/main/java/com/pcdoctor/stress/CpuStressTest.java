package com.pcdoctor.stress;

import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Carica tutti i thread della CPU con calcoli in virgola mobile e hashing.
 * Ogni iterazione deve produrre esattamente lo stesso risultato di riferimento: una differenza
 * significa che la CPU ha sbagliato un calcolo (instabilità da tensione, temperatura o overclock).
 */
public class CpuStressTest implements StressTest {

    private static final int MATRIX = 96;
    private final int threads;

    public CpuStressTest(int threads) {
        this.threads = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
    }

    @Override
    public String name() {
        return "Stress CPU (" + threads + " thread)";
    }

    @Override
    public void run(Duration duration, Report report) throws Exception {
        final long reference = workload();
        AtomicLong iterations = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        long deadline = System.nanoTime() + duration.toNanos();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int t = 0; t < threads; t++) {
                futures[t] = pool.submit(() -> {
                    while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                        if (workload() != reference) {
                            errors.incrementAndGet();
                        }
                        iterations.incrementAndGet();
                    }
                    return null;
                });
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        report.data("Stress CPU", "Iterazioni", String.valueOf(iterations.get()));
        report.data("Stress CPU", "Errori di calcolo", String.valueOf(errors.get()));
        if (errors.get() > 0) {
            report.add(Finding.critical("CPU", "La CPU ha prodotto " + errors.get() + " risultati errati sotto sforzo",
                    iterations.get() + " iterazioni su " + threads + " thread",
                    "CPU instabile: disattivare PBO/overclock/undervolt (Curve Optimizer), aggiornare il BIOS, "
                            + "controllare il dissipatore."));
        } else {
            report.add(Finding.ok("CPU", "Stress CPU superato senza errori",
                    iterations.get() + " iterazioni in " + duration.toMinutes() + " min su " + threads + " thread"));
        }
    }

    /** Calcolo deterministico: prodotto di matrici + SHA-256. Restituisce un'impronta del risultato. */
    static long workload() {
        XorShift rnd = new XorShift(42);
        double[] a = new double[MATRIX * MATRIX];
        double[] b = new double[MATRIX * MATRIX];
        for (int i = 0; i < a.length; i++) {
            a[i] = (rnd.next() % 1000) / 7.0;
            b[i] = (rnd.next() % 1000) / 13.0;
        }
        double[] c = new double[MATRIX * MATRIX];
        for (int i = 0; i < MATRIX; i++) {
            for (int k = 0; k < MATRIX; k++) {
                double aik = a[i * MATRIX + k];
                for (int j = 0; j < MATRIX; j++) {
                    c[i * MATRIX + j] += aik * b[k * MATRIX + j];
                }
            }
        }
        long hash = Arrays.hashCode(c);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[256 * 1024];
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = (byte) rnd.next();
            }
            byte[] digest = sha.digest(buffer);
            for (int i = 0; i < 8; i++) {
                hash = hash * 31 + digest[i];
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return hash;
    }
}
