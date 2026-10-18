package com.pcdoctor.stress;

import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Riempie la RAM con schemi di bit noti e li rilegge per trovare celle che cambiano valore.
 * La quantità testata dipende dall'heap della JVM (il launcher usa -XX:MaxRAMPercentage).
 * Non sostituisce MemTest86 (che testa anche la RAM usata da Windows), ma trova le instabilità più comuni.
 */
public class MemoryStressTest implements StressTest {

    private static final int CHUNK_LONGS = 8 * 1024 * 1024; // 64 MB per blocco
    private static final long[] PATTERNS = {
            0x0000000000000000L, 0xFFFFFFFFFFFFFFFFL, 0xAAAAAAAAAAAAAAAAL, 0x5555555555555555L,
            0xCCCCCCCCCCCCCCCCL, 0x3333333333333333L};

    private final long requestedMb;

    /** @param requestedMb MB da testare; 0 = automatico (circa 75% dell'heap disponibile) */
    public MemoryStressTest(long requestedMb) {
        this.requestedMb = requestedMb;
    }

    @Override
    public String name() {
        return "Stress RAM";
    }

    @Override
    public void run(Duration duration, Report report) throws Exception {
        long availableMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        long targetMb = requestedMb > 0 ? Math.min(requestedMb, availableMb * 85 / 100) : availableMb * 75 / 100;
        int chunks = (int) Math.max(1, targetMb / 64);

        List<long[]> blocks = new ArrayList<>();
        try {
            for (int i = 0; i < chunks; i++) {
                blocks.add(new long[CHUNK_LONGS]);
            }
        } catch (OutOfMemoryError e) {
            // usa i blocchi già allocati
        }
        long testedMb = blocks.size() * 64L;

        AtomicLong errors = new AtomicLong();
        AtomicLong passes = new AtomicLong();
        List<String> errorSamples = new ArrayList<>();
        long deadline = System.nanoTime() + duration.toNanos();
        int threads = Math.min(Runtime.getRuntime().availableProcessors(), Math.max(1, blocks.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            int pass = 0;
            do {
                final int p = pass++;
                List<Future<?>> futures = new ArrayList<>();
                for (int b = 0; b < blocks.size(); b++) {
                    final int blockIndex = b;
                    final long[] block = blocks.get(b);
                    futures.add(pool.submit(() -> {
                        long found = testBlock(block, blockIndex, p);
                        if (found > 0) {
                            errors.addAndGet(found);
                            synchronized (errorSamples) {
                                if (errorSamples.size() < 10) {
                                    errorSamples.add("blocco " + blockIndex + ", passata " + p + ": " + found + " errori");
                                }
                            }
                        }
                    }));
                }
                for (Future<?> f : futures) {
                    f.get();
                }
                passes.incrementAndGet();
            } while (System.nanoTime() < deadline);
        } finally {
            pool.shutdownNow();
            blocks.clear();
        }

        report.data("Stress RAM", "Memoria testata", testedMb + " MB");
        report.data("Stress RAM", "Passate complete", String.valueOf(passes.get()));
        report.data("Stress RAM", "Errori", String.valueOf(errors.get()));
        if (errors.get() > 0) {
            report.add(Finding.critical("RAM", "Errori di memoria rilevati: " + errors.get(), String.join("; ", errorSamples),
                    "RAM instabile o difettosa: disattivare XMP e ripetere; poi testare un modulo alla volta "
                            + "(o eseguire MemTest86 da chiavetta USB) per trovare il banco difettoso."));
        } else {
            report.add(Finding.ok("RAM", "Stress RAM superato senza errori",
                    testedMb + " MB testati, " + passes.get() + " passate"));
        }
    }

    /** Scrive e verifica uno schema sul blocco. Restituisce il numero di valori errati. */
    static long testBlock(long[] block, int blockIndex, int pass) {
        long errors = 0;
        int variant = pass % (PATTERNS.length + 2);
        if (variant < PATTERNS.length) {
            long pattern = PATTERNS[variant];
            java.util.Arrays.fill(block, pattern);
            for (long v : block) {
                if (v != pattern) {
                    errors++;
                }
            }
        } else if (variant == PATTERNS.length) {
            // indirizzo nel valore: trova errori di indirizzamento
            long base = (long) blockIndex * block.length;
            for (int i = 0; i < block.length; i++) {
                block[i] = (base + i) * 0x9E3779B97F4A7C15L;
            }
            for (int i = 0; i < block.length; i++) {
                if (block[i] != (base + i) * 0x9E3779B97F4A7C15L) {
                    errors++;
                }
            }
        } else {
            long seed = 1L + blockIndex * 31L + pass;
            XorShift write = new XorShift(seed);
            for (int i = 0; i < block.length; i++) {
                block[i] = write.next();
            }
            XorShift verify = new XorShift(seed);
            for (long v : block) {
                if (v != verify.next()) {
                    errors++;
                }
            }
        }
        return errors;
    }
}
