package com.pcdoctor.sensors;

import com.pcdoctor.util.NvidiaSmi;

import java.util.Map;

/**
 * Valori di una GPU in un istante, indipendenti dal produttore. I valori non disponibili valgono
 * {@link Double#NaN} (numeri) o stringa vuota (testo).
 *
 * @param source da dove arrivano i dati: nvidia-smi, LibreHardwareMonitor o contatori di Windows
 */
public record GpuReading(String name, String source, double loadPct, double tempC, double hotspotC, double powerW,
                         double powerLimitW, double clockMhz, double memClockMhz, double vramUsedMib,
                         double vramTotalMib, double fanPct, String pstate, String throttle) {

    public static final String NVIDIA_SMI = "nvidia-smi";
    public static final String LHM = "LibreHardwareMonitor";
    public static final String WINDOWS = "contatori di Windows";

    static GpuReading fromNvidiaSmi(Map<String, String> gpu) {
        return new GpuReading(gpu.getOrDefault("name", "GPU NVIDIA"), NVIDIA_SMI,
                NvidiaSmi.number(gpu, "utilization.gpu"), NvidiaSmi.number(gpu, "temperature.gpu"), Double.NaN,
                NvidiaSmi.number(gpu, "power.draw"),
                Math.max(NvidiaSmi.number(gpu, "power.limit"), NvidiaSmi.number(gpu, "power.max_limit")),
                NvidiaSmi.number(gpu, "clocks.gr"), NvidiaSmi.number(gpu, "clocks.mem"),
                NvidiaSmi.number(gpu, "memory.used"), NvidiaSmi.number(gpu, "memory.total"),
                NvidiaSmi.number(gpu, "fan.speed"), gpu.getOrDefault("pstate", ""),
                String.join("+", NvidiaSmi.throttleReasons(gpu.getOrDefault("clocks_event_reasons.active", "0x0"))));
    }

    /** true se sono disponibili temperatura o consumo (non solo carico e memoria). */
    public boolean hasThermalData() {
        return !Double.isNaN(tempC) || !Double.isNaN(powerW);
    }
}
