package com.pcdoctor.monitor;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Campiona i sensori a intervalli regolari e scrive ogni riga su CSV forzandola su disco (fsync):
 * se il PC si spegne di colpo, il file contiene i valori fino all'ultimo secondo.
 */
public class SensorMonitor implements AutoCloseable {

    private final Path csv;
    private final Duration interval;
    private final Consumer<Map<String, String>> listener;
    private final PeakTracker peaks = new PeakTracker();
    private volatile boolean running;
    private Thread thread;

    public SensorMonitor(Path csv, Duration interval, Consumer<Map<String, String>> listener) {
        this.csv = csv;
        this.interval = interval;
        this.listener = listener;
    }

    public SensorMonitor start() {
        running = true;
        thread = new Thread(this::loop, "sensor-monitor");
        thread.setDaemon(true);
        thread.start();
        return this;
    }

    public PeakTracker peaks() {
        return peaks;
    }

    public Path csv() {
        return csv;
    }

    private void loop() {
        SensorSampler sampler = new SensorSampler();
        try (FileOutputStream fos = new FileOutputStream(csv.toFile(), true);
             Writer out = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            if (csv.toFile().length() == 0) {
                out.write(String.join(";", SensorSampler.COLUMNS) + System.lineSeparator());
            }
            while (running) {
                long start = System.nanoTime();
                Map<String, String> row = sampler.sample();
                out.write(String.join(";", row.values()) + System.lineSeparator());
                out.flush();
                fos.getFD().sync();
                peaks.accept(row);
                if (listener != null) {
                    listener.accept(row);
                }
                long sleep = interval.toMillis() - (System.nanoTime() - start) / 1_000_000;
                if (sleep > 0) {
                    Thread.sleep(sleep);
                }
            }
        } catch (IOException e) {
            System.err.println("Errore scrittura log monitor: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
