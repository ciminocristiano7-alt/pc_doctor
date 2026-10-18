package com.pcdoctor.monitor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Legge un log del monitor. Usato soprattutto dopo un crash: gli ultimi secondi mostrano
 * temperature, consumi e throttling prima dello spegnimento.
 */
public class MonitorLogAnalyzer {

    private static final String MARKER = "monitor.running";

    private final List<Map<String, String>> rows;

    public MonitorLogAnalyzer(List<Map<String, String>> rows) {
        this.rows = rows;
    }

    public static MonitorLogAnalyzer load(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        List<Map<String, String>> rows = new ArrayList<>();
        if (lines.isEmpty()) {
            return new MonitorLogAnalyzer(rows);
        }
        String[] header = lines.get(0).split(";", -1);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] values = line.split(";", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.length; i++) {
                row.put(header[i], i < values.length ? values[i] : "");
            }
            rows.add(row);
        }
        return new MonitorLogAnalyzer(rows);
    }

    public List<Map<String, String>> rows() {
        return rows;
    }

    public List<Map<String, String>> lastRows(int n) {
        return rows.subList(Math.max(0, rows.size() - n), rows.size());
    }

    public PeakTracker peaks() {
        PeakTracker tracker = new PeakTracker();
        rows.forEach(tracker::accept);
        return tracker;
    }

    // --- marker: indica una sessione di monitor in corso; se resta dopo un riavvio, la sessione è finita con un crash

    public static Path markerFile(Path logsDir) {
        return logsDir.resolve(MARKER);
    }

    public static void writeMarker(Path logsDir, Path csv) throws IOException {
        Files.writeString(markerFile(logsDir), csv.toAbsolutePath().toString(), StandardCharsets.UTF_8);
    }

    public static void clearMarker(Path logsDir) {
        try {
            Files.deleteIfExists(markerFile(logsDir));
        } catch (IOException ignored) {
            // nulla da fare
        }
    }

    /** Se una sessione precedente non si è chiusa regolarmente, restituisce il suo log. */
    public static Optional<Path> interruptedSession(Path logsDir) throws IOException {
        Path marker = markerFile(logsDir);
        if (!Files.exists(marker)) {
            return Optional.empty();
        }
        Path csv = Path.of(Files.readString(marker, StandardCharsets.UTF_8).trim());
        return Files.exists(csv) ? Optional.of(csv) : Optional.empty();
    }

    public static Optional<Path> latestLog(Path logsDir) throws IOException {
        try (Stream<Path> files = Files.list(logsDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .max((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        }
    }
}
