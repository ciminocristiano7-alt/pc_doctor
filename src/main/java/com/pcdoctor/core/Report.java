package com.pcdoctor.core;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Raccoglie i risultati di tutti i controlli e i dati grezzi (inventario, misure). */
public class Report {

    private final LocalDateTime createdAt = LocalDateTime.now();
    private final List<Finding> findings = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Map<String, String>> sections = Collections.synchronizedMap(new LinkedHashMap<>());

    public void add(Finding finding) {
        findings.add(finding);
    }

    /** Aggiunge un dato grezzo (es. "CPU" -> "Modello" = "Ryzen 9 5900X"). */
    public void data(String section, String key, String value) {
        sections.computeIfAbsent(section, s -> Collections.synchronizedMap(new LinkedHashMap<>())).put(key, value);
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public List<Finding> findings() {
        synchronized (findings) {
            return List.copyOf(findings);
        }
    }

    /** Risultati ordinati dal più grave al meno grave. */
    public List<Finding> findingsBySeverity() {
        List<Finding> sorted = new ArrayList<>(findings());
        sorted.sort(Comparator.comparing(Finding::severity).reversed());
        return sorted;
    }

    public Map<String, Map<String, String>> sections() {
        synchronized (sections) {
            Map<String, Map<String, String>> copy = new LinkedHashMap<>();
            sections.forEach((k, v) -> {
                synchronized (v) {
                    copy.put(k, new LinkedHashMap<>(v));
                }
            });
            return copy;
        }
    }

    public long count(Severity severity) {
        return findings().stream().filter(f -> f.severity() == severity).count();
    }
}
