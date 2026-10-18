package com.pcdoctor.monitor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tiene traccia dei valori massimi osservati per ogni colonna numerica. */
public class PeakTracker {

    private final Map<String, Double> peaks = new LinkedHashMap<>();

    public synchronized void accept(Map<String, String> row) {
        row.forEach((key, value) -> {
            if (key.equals("time") || value == null || value.isEmpty()) {
                return;
            }
            try {
                double v = Double.parseDouble(value);
                peaks.merge(key, v, Math::max);
            } catch (NumberFormatException ignored) {
                // colonne testuali (pstate, throttle)
            }
        });
    }

    public synchronized Map<String, Double> peaks() {
        return new LinkedHashMap<>(peaks);
    }

    public synchronized double peak(String column) {
        return peaks.getOrDefault(column, Double.NaN);
    }
}
