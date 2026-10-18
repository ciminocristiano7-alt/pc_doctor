package com.pcdoctor.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Legge i sensori delle GPU NVIDIA tramite nvidia-smi (installato con il driver). */
public final class NvidiaSmi {

    public static final List<String> FIELDS = List.of(
            "name", "driver_version", "temperature.gpu", "power.draw", "power.limit", "power.max_limit",
            "clocks.gr", "clocks.mem", "clocks.max.gr", "utilization.gpu", "memory.used", "memory.total",
            "fan.speed", "pstate", "pcie.link.gen.current", "pcie.link.gen.max",
            "pcie.link.width.current", "pcie.link.width.max", "clocks_event_reasons.active");

    private NvidiaSmi() {
    }

    public static Optional<Path> executable() {
        for (String p : List.of("C:\\Windows\\System32\\nvidia-smi.exe",
                "C:\\Program Files\\NVIDIA Corporation\\NVSMI\\nvidia-smi.exe")) {
            Path path = Path.of(p);
            if (Files.isRegularFile(path)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    public static boolean available() {
        return executable().isPresent();
    }

    /** Una mappa campo -> valore per ogni GPU NVIDIA presente. */
    public static List<Map<String, String>> query() {
        List<Map<String, String>> gpus = new ArrayList<>();
        Optional<Path> exe = executable();
        if (exe.isEmpty()) {
            return gpus;
        }
        try {
            Process p = new ProcessBuilder(exe.get().toString(),
                    "--query-gpu=" + String.join(",", FIELDS), "--format=csv,noheader,nounits")
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return gpus;
            }
            for (String line : out.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = line.split(",\\s*");
                Map<String, String> gpu = new LinkedHashMap<>();
                for (int i = 0; i < FIELDS.size() && i < values.length; i++) {
                    gpu.put(FIELDS.get(i), values[i].trim());
                }
                gpus.add(gpu);
            }
        } catch (IOException e) {
            // nvidia-smi non eseguibile
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return gpus;
    }

    public static double number(Map<String, String> gpu, String field) {
        try {
            return Double.parseDouble(gpu.getOrDefault(field, ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Traduce la maschera clocks_event_reasons in motivi leggibili. */
    public static List<String> throttleReasons(String hexMask) {
        List<String> reasons = new ArrayList<>();
        long mask;
        try {
            mask = Long.decode(hexMask.trim());
        } catch (NumberFormatException e) {
            return reasons;
        }
        if ((mask & 0x2) != 0) reasons.add("limite applicativo");
        if ((mask & 0x4) != 0) reasons.add("limite di potenza (SW power cap)");
        if ((mask & 0x8) != 0) reasons.add("HW slowdown (temperatura o alimentazione esterna)");
        if ((mask & 0x20) != 0) reasons.add("SW thermal slowdown");
        if ((mask & 0x40) != 0) reasons.add("HW thermal slowdown");
        if ((mask & 0x80) != 0) reasons.add("HW power brake (segnale dall'alimentatore)");
        return reasons;
    }
}
