package com.pcdoctor.stress;

import com.pcdoctor.core.Finding;
import com.pcdoctor.core.Report;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;

/**
 * Scrive un file di prova con dati pseudo-casuali, lo rilegge e verifica che sia identico.
 * Misura anche la velocità di scrittura e lettura. Il file viene cancellato a fine test.
 */
public class DiskStressTest implements StressTest {

    private static final int BLOCK = 4 * 1024 * 1024;

    private final Path directory;
    private final long sizeMb;

    public DiskStressTest(Path directory, long sizeMb) {
        this.directory = directory;
        this.sizeMb = sizeMb;
    }

    @Override
    public String name() {
        return "Stress disco (" + directory + ", " + sizeMb + " MB)";
    }

    @Override
    public void run(Duration duration, Report report) throws IOException {
        long free = Files.getFileStore(directory).getUsableSpace() / (1024 * 1024);
        if (free < sizeMb * 2) {
            report.add(Finding.warning("Disco", "Stress disco saltato: spazio insufficiente su " + directory,
                    free + " MB liberi", "Liberare spazio o usare --disk-dir su un altro disco."));
            return;
        }
        Path file = Files.createTempFile(directory, "pc-doctor-stress-", ".bin");
        long blocks = sizeMb * 1024 * 1024 / BLOCK;
        long errors = 0;
        int passes = 0;
        double writeMbs = 0;
        double readMbs = 0;
        long deadline = System.nanoTime() + duration.toNanos();
        try {
            do {
                long seed = 7919L * (passes + 1);
                long t0 = System.nanoTime();
                try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buf = ByteBuffer.allocateDirect(BLOCK);
                    for (long b = 0; b < blocks; b++) {
                        fill(buf, seed + b);
                        while (buf.hasRemaining()) {
                            ch.write(buf);
                        }
                    }
                    ch.force(true);
                }
                long t1 = System.nanoTime();
                try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                    ByteBuffer buf = ByteBuffer.allocateDirect(BLOCK);
                    ByteBuffer expected = ByteBuffer.allocateDirect(BLOCK);
                    for (long b = 0; b < blocks; b++) {
                        buf.clear();
                        while (buf.hasRemaining() && ch.read(buf) > 0) {
                            // continua a leggere il blocco
                        }
                        buf.flip();
                        fill(expected, seed + b);
                        if (buf.compareTo(expected) != 0) {
                            errors++;
                        }
                    }
                }
                long t2 = System.nanoTime();
                writeMbs = sizeMb / ((t1 - t0) / 1e9);
                readMbs = sizeMb / ((t2 - t1) / 1e9);
                passes++;
            } while (System.nanoTime() < deadline);
        } finally {
            Files.deleteIfExists(file);
        }

        report.data("Stress disco", "Cartella", directory.toString());
        report.data("Stress disco", "Passate", String.valueOf(passes));
        report.data("Stress disco", "Scrittura", String.format(Locale.ROOT, "%.0f MB/s", writeMbs));
        report.data("Stress disco", "Lettura (può includere cache)", String.format(Locale.ROOT, "%.0f MB/s", readMbs));
        if (errors > 0) {
            report.add(Finding.critical("Disco", errors + " blocchi letti diversi da quelli scritti su " + directory, "",
                    "Possibile disco, cavo SATA o controller difettoso: fare un backup e verificare il disco."));
        } else {
            report.add(Finding.ok("Disco", "Stress disco superato su " + directory,
                    String.format(Locale.ROOT, "scrittura %.0f MB/s, lettura %.0f MB/s", writeMbs, readMbs)));
        }
    }

    private static void fill(ByteBuffer buf, long seed) {
        buf.clear();
        XorShift rnd = new XorShift(seed);
        while (buf.remaining() >= Long.BYTES) {
            buf.putLong(rnd.next());
        }
        buf.flip();
    }
}
